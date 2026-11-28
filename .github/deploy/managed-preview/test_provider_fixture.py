"""FAST synthetic grant checks; no listener, credentials or live provider."""
import base64
import hashlib
import importlib.util
import json
import concurrent.futures
import http.client
import socket
import threading
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('provider_fixture',Path(__file__).with_name('provider_fixture.py'))
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)

class GrantTests(unittest.TestCase):
    def setUp(self):
        self.state=fixture.Fixture('synthetic-public-modulus',lambda value:b'synthetic-signature')
        self.verifier='v'*43;self.nonce='n'*43
        self.code=self.state.authorize(self.nonce,fixture.encoded(hashlib.sha256(self.verifier.encode()).digest()),'owner')
        self.client='Basic '+base64.b64encode((fixture.CLIENT+':'+fixture.CLIENT_SECRET).encode()).decode()
        self.form={'code':[self.code],'code_verifier':[self.verifier],'grant_type':['authorization_code'],
            'redirect_uri':['https://preview.example.test/api/auth/oidc/google/callback']}

    def test_valid_one_time_grant_preserves_nonce_and_audience(self):
        response=self.state.exchange(self.client,self.form)
        payload=response['id_token'].split('.')[1]
        claims=json.loads(base64.urlsafe_b64decode(payload+'='*(-len(payload)%4)))
        self.assertEqual(self.nonce,claims['nonce']);self.assertEqual(fixture.CLIENT,claims['aud'])
        self.assertEqual('fixture-owner',claims['sub'])
        self.assertEqual('fixture.example.test',claims['hd'])
        self.assertEqual('owner@fixture.example.test',claims['email'])
        with self.assertRaises(ValueError):self.state.exchange(self.client,self.form)

    def test_wrong_client_cannot_exchange(self):
        with self.assertRaises(ValueError):self.state.exchange('Basic invalid',self.form)

    def test_wrong_proof_key_cannot_exchange(self):
        self.form['code_verifier']=['x'*43]
        with self.assertRaises(ValueError):self.state.exchange(self.client,self.form)

    def test_wrong_redirect_cannot_exchange(self):
        self.form['redirect_uri']=['https://evil.invalid/callback']
        with self.assertRaises(ValueError):self.state.exchange(self.client,self.form)

    def test_unapproved_fixture_identity_is_rejected(self):
        with self.assertRaises(ValueError):self.state.authorize(self.nonce,'c'*43,'unapproved')

    def test_concurrent_conditional_put_has_one_winner(self):
        ready=threading.Barrier(8)
        def put(index):
            ready.wait(timeout=5)
            return self.state.put_if_absent('/synthetic-private-bucket/fixture',bytes([index]))
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            statuses=list(pool.map(put,range(8)))
        self.assertEqual(1,statuses.count(200));self.assertEqual(7,statuses.count(412))
        self.assertEqual(1,self.state.snapshot_counts()['objectPut'])
        self.assertEqual(1,len(self.state.objects))

    def test_slow_tls_handshake_does_not_block_another_connection(self):
        blocked=threading.Event();release=threading.Event();lock=threading.Lock();accepted=0
        class Connection:
            def __init__(self,raw,first):self.raw=raw;self.first=first
            def __getattr__(self,name):return getattr(self.raw,name)
            def __enter__(self):return self
            def __exit__(self,*args):self.raw.close()
            def do_handshake(self):
                if self.first:
                    blocked.set()
                    if not release.wait(5):raise TimeoutError('Synthetic handshake coordination expired')
        class Tls:
            def wrap_socket(self,raw,*,server_side,do_handshake_on_connect):
                nonlocal accepted
                assert server_side and not do_handshake_on_connect
                with lock:
                    accepted+=1;first=accepted==1
                return Connection(raw,first)
        server=fixture.FixtureServer(('127.0.0.1',0),fixture.handler(self.state,True),Tls())
        worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
        idle=socket.create_connection(server.server_address,timeout=5)
        client=http.client.HTTPConnection(*server.server_address,timeout=5)
        try:
            self.assertTrue(blocked.wait(5))
            client.request('GET','/counts');response=client.getresponse()
            self.assertEqual(200,response.status)
            self.assertEqual(self.state.snapshot_counts(),json.loads(response.read()))
            self.assertFalse(release.is_set())
        finally:
            client.close();idle.close();release.set();server.shutdown();server.server_close();worker.join(timeout=5)
        self.assertFalse(worker.is_alive())

if __name__=='__main__':unittest.main()
