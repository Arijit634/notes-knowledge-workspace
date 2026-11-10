"""FAST synthetic grant checks; no listener, credentials or live provider."""
import base64
import hashlib
import importlib.util
import json
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

if __name__=='__main__':unittest.main()
