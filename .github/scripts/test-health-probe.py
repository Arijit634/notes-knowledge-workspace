#!/usr/bin/env python3
"""FAST/SECURITY: actual native fixed-loopback probe, no provider or application secrets."""
import os,pathlib,platform,socketserver,subprocess,tempfile,threading,unittest

ROOT=pathlib.Path(__file__).resolve().parents[2]

class Server(socketserver.TCPServer):
    allow_reuse_address=True

class HealthProbeContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if platform.system()!='Linux':raise RuntimeError('Native Linux qualification required')
        cls.temporary=tempfile.TemporaryDirectory(prefix='health-probe-',dir=os.environ['RUNNER_TEMP'])
        cls.binary=pathlib.Path(cls.temporary.name)/'probe'
        subprocess.run(['gcc','-std=c11','-O2','-Wall','-Wextra','-Werror',
            str(ROOT/'src/main/native/health-probe.c'),'-o',str(cls.binary)],check=True)
    @classmethod
    def tearDownClass(cls):cls.temporary.cleanup()
    def result(self,response,arguments=(),stall=False):
        release=threading.Event();requests=[]
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                self.request.settimeout(3)
                requests.append(self.request.recv(512))
                if stall:release.wait(5)
                elif response:self.request.sendall(response)
        with Server(('127.0.0.1',8081),Handler) as server:
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            try:result=subprocess.run([str(self.binary),*arguments],capture_output=True,timeout=5)
            finally:release.set();server.shutdown();thread.join(timeout=5)
        self.assertEqual(b'',result.stdout);self.assertEqual(b'',result.stderr)
        if not arguments:self.assertTrue(requests[0].startswith(b'GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\n'))
        return result.returncode
    def test_ready(self):self.assertEqual(0,self.result(b'HTTP/1.1 200 OK\r\n\r\n'))
    def test_http10_ready(self):self.assertEqual(0,self.result(b'HTTP/1.0 200\r\n\r\n'))
    def test_down_and_auth_errors(self):
        for status in (401,403,404,500,503):
            with self.subTest(status=status):self.assertEqual(1,self.result(f'HTTP/1.1 {status} No\r\n\r\n'.encode()))
    def test_redirect_is_not_followed(self):self.assertEqual(1,self.result(b'HTTP/1.1 302 Found\r\nLocation: https://invalid.example.test\r\n\r\n'))
    def test_malformed_and_truncated(self):
        for response in (b'HTTP/1.1 2000 bad\r\n',b'HTTP/1.1 20',b'not http at all'):
            with self.subTest(response=response):self.assertEqual(1,self.result(response))
    def test_closed_connection(self):self.assertEqual(1,self.result(b''))
    def test_unavailable_listener(self):
        result=subprocess.run([str(self.binary)],capture_output=True,timeout=5)
        self.assertEqual(1,result.returncode)
        self.assertEqual(b'',result.stdout);self.assertEqual(b'',result.stderr)
    def test_silent_peer_is_bounded(self):self.assertEqual(1,self.result(b'',stall=True))
    def test_target_cannot_be_changed(self):self.assertEqual(1,self.result(b'HTTP/1.1 200 OK\r\n\r\n',arguments=('https://invalid.example.test',)))

if __name__=='__main__':unittest.main()
