"""CI-only OIDC/object transport fixture. Never packaged or used by the application."""
import argparse
import base64
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
from pathlib import Path
import re
import secrets
import signal
import ssl
import subprocess
import threading
import time
import urllib.parse

ISSUER = 'https://accounts.google.com'
STORAGE_HOST = 'syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com'
CLIENT = 'synthetic.apps.googleusercontent.com'
CLIENT_SECRET = 'synthetic-client-secret'


class FixtureServer(ThreadingHTTPServer):
    """A bounded slow connection must not stall every synthetic provider."""
    def __init__(self, address, handler_type, tls=None):
        self.tls = tls
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(address, handler_type)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            request.settimeout(10)
            super().process_request(request, address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.slots.release()

    def finish_request(self, request, address):
        if self.tls is None:
            return super().finish_request(request, address)
        # TLS negotiation belongs to this connection's worker, not accept().
        with self.tls.wrap_socket(request, server_side=True, do_handshake_on_connect=False) as secured:
            secured.do_handshake()
            return super().finish_request(secured, address)


def encoded(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


class Fixture:
    def __init__(self, modulus, sign):
        self.modulus = modulus
        self.sign = sign
        self.codes = {}
        self.objects = {}
        self.counts = {'metadata': 0, 'token': 0, 'jwks': 0, 'objectPut': 0, 'objectGet': 0}
        self.lock = threading.Lock()

    def count(self, name):
        with self.lock:
            self.counts[name] += 1

    def snapshot_counts(self):
        with self.lock:
            return dict(self.counts)

    def put_if_absent(self, path, body):
        with self.lock:
            if path in self.objects:
                return 412
            if len(self.objects) >= 8:
                return 507
            self.objects[path] = body
            self.counts['objectPut'] += 1
            return 200

    def get_object(self, path):
        with self.lock:
            body = self.objects.get(path)
            if body is not None:
                self.counts['objectGet'] += 1
            return body

    def remove_object(self, path):
        with self.lock:
            self.objects.pop(path, None)

    def authorize(self, nonce, challenge, identity):
        if identity not in ('owner', 'other') or not all(isinstance(v, str) and re.fullmatch(r'[A-Za-z0-9_-]{32,128}', v)
                for v in (nonce, challenge)):
            raise ValueError('Invalid synthetic authorization input')
        code = secrets.token_urlsafe(24)
        with self.lock:
            self.codes[code] = (nonce, challenge, identity)
        return code

    def exchange(self, authorization, form):
        expected = 'Basic ' + base64.b64encode((CLIENT + ':' + CLIENT_SECRET).encode()).decode()
        if not hmac.compare_digest(authorization, expected):
            raise ValueError('Invalid synthetic client')
        code = form.get('code', [''])[0]
        with self.lock:
            record = self.codes.pop(code, None)
        verifier = form.get('code_verifier', [''])[0]
        if record is None or form.get('grant_type') != ['authorization_code'] or not re.fullmatch(r'[A-Za-z0-9._~-]{43,128}', verifier):
            raise ValueError('Invalid synthetic grant')
        nonce, challenge, identity = record
        if not hmac.compare_digest(encoded(hashlib.sha256(verifier.encode()).digest()), challenge):
            raise ValueError('Invalid synthetic proof key')
        if form.get('redirect_uri') != ['https://preview.example.test/api/auth/oidc/google/callback']:
            raise ValueError('Invalid synthetic redirect')
        now = int(time.time())
        # Reserved Workspace identity: Google's authority policy intentionally
        # rejects a verified third-party email without a hosted-domain claim.
        claims = dict(iss=ISSUER, sub='fixture-' + identity, aud=CLIENT, nonce=nonce, hd='fixture.example.test',
            email=identity + '@fixture.example.test', email_verified=True, iat=now, exp=now + 300, auth_time=now)
        header = encoded(json.dumps(dict(alg='RS256', kid='fixture-key', typ='JWT')).encode())
        payload = encoded(json.dumps(claims).encode())
        content = (header + '.' + payload).encode()
        self.count('token')
        return dict(access_token='synthetic-access-with-no-external-authority', token_type='Bearer',
            expires_in=300, scope='openid email', id_token=content.decode() + '.' + encoded(self.sign(content)))


def handler(fixture, control=False):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def read_body(self, maximum):
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 <= length <= maximum or self.headers.get('Transfer-Encoding'):
                raise ValueError('Unbounded fixture request')
            return self.rfile.read(length)

        def reply(self, code, body=b'', headers=None):
            self.send_response(code)
            self.send_header('Content-Length', str(len(body)))
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(body)

        def json_reply(self, value, status=200):
            self.reply(status, json.dumps(value).encode(), {'Content-Type': 'application/json'})

        def dispatch(self):
            path = urllib.parse.urlsplit(self.path).path
            if control:
                if self.command == 'POST' and path == '/authorize':
                    value = json.loads(self.read_body(8192))
                    self.json_reply({'code': fixture.authorize(value['nonce'], value['challenge'], value['identity'])})
                elif self.command == 'GET' and path == '/counts':
                    self.json_reply(fixture.snapshot_counts())
                else:
                    self.reply(404)
                return
            host = self.headers.get('Host', '').split(':')[0]
            if host == 'accounts.google.com':
                if self.command == 'GET' and path == '/.well-known/openid-configuration':
                    fixture.count('metadata')
                    self.json_reply(dict(issuer=ISSUER, authorization_endpoint=ISSUER + '/fixture/authorize',
                        token_endpoint=ISSUER + '/fixture/token', jwks_uri=ISSUER + '/fixture/jwks',
                        response_types_supported=['code'], subject_types_supported=['public'],
                        id_token_signing_alg_values_supported=['RS256'], code_challenge_methods_supported=['S256'],
                        token_endpoint_auth_methods_supported=['client_secret_basic']))
                elif self.command == 'GET' and path == '/fixture/jwks':
                    fixture.count('jwks')
                    self.json_reply({'keys': [dict(kty='RSA', kid='fixture-key', use='sig', alg='RS256', n=fixture.modulus, e='AQAB')]})
                elif self.command == 'POST' and path == '/fixture/token':
                    form = urllib.parse.parse_qs(self.read_body(8192).decode())
                    self.json_reply(fixture.exchange(self.headers.get('Authorization', ''), form))
                else:
                    self.reply(404)
                return
            if host != STORAGE_HOST or not path.startswith('/synthetic-private-bucket/'):
                self.reply(404)
                return
            if not self.headers.get('Authorization', '').startswith('AWS4-HMAC-SHA256 Credential=synthetic-access-key/'):
                self.reply(403)
                return
            if self.command == 'PUT':
                body = self.read_body(20_000_000)
                checksum=base64.b64encode(hashlib.md5(body,usedforsecurity=False).digest()).decode()
                if self.headers.get('Content-MD5')!=checksum or self.headers.get('If-None-Match')!='*':
                    self.reply(400)
                    return
                status = fixture.put_if_absent(path, body)
                if status != 200:
                    self.reply(status)
                    return
                self.reply(200, headers={'ETag': '"' + hashlib.md5(body, usedforsecurity=False).hexdigest() + '"'})
            elif self.command in ('GET', 'HEAD'):
                body = fixture.get_object(path)
                if body is None:
                    self.reply(404)
                else:
                    headers={'ETag': '"' + hashlib.md5(body, usedforsecurity=False).hexdigest() + '"',
                        'Content-Type': 'application/octet-stream', 'Last-Modified': 'Mon, 09 Nov 2026 00:00:00 GMT'}
                    requested=self.headers.get('Range')
                    if requested:
                        match=re.fullmatch(r'bytes=(\d+)-(\d+)',requested)
                        if not match or not 0<=int(match[1])<=int(match[2])<len(body):
                            self.reply(416)
                            return
                        start,end=int(match[1]),int(match[2])
                        headers['Content-Range']=f'bytes {start}-{end}/{len(body)}'
                        self.reply(206,body[start:end+1],headers)
                    else:self.reply(200,body,headers)
            elif self.command == 'DELETE':
                fixture.remove_object(path)
                self.reply(204)
            else:
                self.reply(405)

        def do_request(self):
            try:
                self.dispatch()
            except (ValueError, KeyError, TypeError):
                self.json_reply({'error': 'invalid_grant'}, 400)

        do_GET = do_HEAD = do_POST = do_PUT = do_DELETE = do_request
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('gateway'); parser.add_argument('certificate'); parser.add_argument('key'); parser.add_argument('ready')
    args = parser.parse_args()
    if not ipaddress.ip_address(args.gateway).is_private:
        raise ValueError('Private fixture bridge required')
    public = subprocess.run(['openssl', 'rsa', '-in', args.key, '-noout', '-modulus'],
        check=True, capture_output=True, timeout=10).stdout.decode().strip().split('=', 1)[1]
    def sign(value):
        return subprocess.run(['openssl', 'dgst', '-sha256', '-sign', args.key],
            input=value, capture_output=True, check=True, timeout=10).stdout
    fixture = Fixture(encoded(bytes.fromhex(public)), sign)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); tls.load_cert_chain(args.certificate, args.key)
    provider = FixtureServer((args.gateway, 443), handler(fixture), tls)
    control = FixtureServer(('127.0.0.1', 0), handler(fixture, True))
    done = threading.Event()
    signal.signal(signal.SIGTERM, lambda *args: done.set())
    signal.signal(signal.SIGINT, lambda *args: done.set())
    threads = [threading.Thread(target=s.serve_forever, daemon=True) for s in (provider, control)]
    for thread in threads:thread.start()
    Path(args.ready).write_text(json.dumps({'controlPort': control.server_port}))
    try:done.wait()
    finally:
        for server in (provider, control):server.shutdown();server.server_close()


if __name__ == '__main__':main()
