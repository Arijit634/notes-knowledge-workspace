"""Offline POSIX transport checks. The tiny pinned ZIP is a labelled test fixture,
not a Maven distribution. Real Maven is verified separately by the CI bootstrap.
Only a loopback HTTP server is contacted; no provider or external repository.
"""
import hashlib
import http.server
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class WrapperTransportTest(unittest.TestCase):
    def run_case(self, statuses, corrupt=False, retry_after='1', archive_status=None,
                 archive_corrupt=False, missing_checksum=False):
        self.assertIsNotNone(shutil.which('curl'))
        with tempfile.TemporaryDirectory(prefix='nkw-wrapper-test-') as temporary:
            directory = Path(temporary)
            (directory / '.mvn/wrapper').mkdir(parents=True)
            shutil.copyfile(ROOT / 'mvnw', directory / 'mvnw')
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, 'w') as target:
                entry = zipfile.ZipInfo('apache-maven-3.9.16/bin/mvn')
                entry.external_attr = 0o100755 << 16
                target.writestr(entry, '#!/bin/sh\n: > "$NKW_WRAPPER_TEST_MARKER"\nprintf "synthetic Maven fixture executed\\n"\n')
            approved = archive.getvalue()
            payload = approved + b'corruption' if corrupt else approved
            requests = []
            primary_path='/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip'
            archive_path='/archive/apache-maven-3.9.16-bin.zip'

            class Handler(http.server.BaseHTTPRequestHandler):
                def do_GET(self):
                    if self.path not in (primary_path, archive_path):
                        self.send_error(404)
                        return
                    requests.append(self.path)
                    is_archive=self.path==archive_path
                    status=archive_status if is_archive else statuses[min(requests.count(primary_path)-1,len(statuses)-1)]
                    body=approved+b'corruption' if is_archive and archive_corrupt else approved if is_archive else payload
                    self.send_response(status)
                    self.send_header('Content-Length', str(len(body) if status == 200 else 0))
                    if status in (429, 503):
                        self.send_header('Retry-After', retry_after)
                    self.end_headers()
                    if status == 200:
                        self.wfile.write(body)

                def log_message(self, *_):
                    pass

            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                (directory / '.mvn/wrapper/maven-wrapper.properties').write_text(
                    'distributionType=only-script\n'
                    'distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip\n' +
                    ('' if missing_checksum else 'distributionSha256Sum=' + hashlib.sha256(approved).hexdigest() + '\n'))
                marker = directory / 'executed'
                env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME') if key in os.environ}
                env.update(HOME=str(directory / 'home'), MAVEN_USER_HOME=str(directory / 'cache'),
                           MVNW_REPOURL='http://127.0.0.1:' + str(server.server_port) + '/maven2',
                           NO_PROXY='127.0.0.1', NKW_WRAPPER_TEST_MARKER=str(marker))
                if archive_status is not None:
                    # The wrapper sees the exact production URLs. A test-only
                    # transport shim maps them to loopback, never the Internet.
                    env.pop('MVNW_REPOURL')
                    shim=directory/'shim';shim.mkdir()
                    urls={
                        'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip':primary_path,
                        'https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.zip':archive_path}
                    script='#!'+sys.executable+'\nimport os,sys\na=sys.argv[1:]\nurls='+repr(urls)+'\nassert a[-1] in urls\n'
                    script+='if urls[a[-1]]=='+repr(archive_path)+':\n assert a[a.index("--retry")+1]=="0"\n assert a[a.index("--max-time")+1]=="60"\n assert a[a.index("--proto")+1]=="=https"\n assert a[a.index("--proto-redir")+1]=="=https"\n'
                    script+='for flag in ("--proto","--proto-redir"):\n if flag in a:\n  i=a.index(flag);del a[i:i+2]\n'
                    script+='a[-1]='+repr('http://127.0.0.1:'+str(server.server_port))+'+urls[a[-1]]\nos.execv('+repr(shutil.which('curl'))+',['+repr(shutil.which('curl'))+']+a)\n'
                    (shim/'curl').write_text(script);(shim/'curl').chmod(0o755)
                    env['PATH']=str(shim)+os.pathsep+env['PATH']
                result = subprocess.run(['/bin/sh', './mvnw', '--version'], cwd=directory,
                                        env=env, capture_output=True, timeout=30)
                if archive_status is not None:
                    return result.returncode, marker.exists(), requests.count(primary_path), requests.count(archive_path)
                return result.returncode, marker.exists(), len(requests)
            finally:
                server.shutdown()
                server.server_close()
                worker.join(timeout=5)

    def test_transient_transfer_then_pinned_fixture_executes(self):
        self.assertEqual(self.run_case([429, 503, 200]), (0, True, 3))

    def test_exhausted_transient_transfer_never_executes(self):
        code, executed, calls = self.run_case([503])
        self.assertNotEqual(code, 0)
        self.assertFalse(executed)
        self.assertEqual(calls, 3)

    def test_permanent_http_failure_is_not_retried(self):
        code, executed, calls = self.run_case([404])
        self.assertNotEqual(code, 0)
        self.assertFalse(executed)
        self.assertEqual(calls, 1)

    def test_corrupted_archive_never_executes(self):
        code, executed, calls = self.run_case([200], corrupt=True)
        self.assertNotEqual(code, 0)
        self.assertFalse(executed)
        self.assertEqual(calls, 1)

    def test_excessive_retry_after_stops_instead_of_waiting(self):
        code, executed, calls = self.run_case([503], retry_after='86400')
        self.assertNotEqual(code, 0)
        self.assertFalse(executed)
        self.assertEqual(calls, 1)

    def test_central_429_uses_apache_once_with_the_same_pin(self):
        self.assertEqual(self.run_case([429],archive_status=200),(0,True,3,1))

    def test_archive_corruption_never_executes(self):
        code,executed,central,archive=self.run_case([429],archive_status=200,archive_corrupt=True)
        self.assertNotEqual(code,0);self.assertFalse(executed);self.assertEqual((central,archive),(3,1))

    def test_archive_failure_has_no_retry_or_third_source(self):
        for status in (404,503):
            with self.subTest(status=status):
                code,executed,central,archive=self.run_case([429],archive_status=status)
                self.assertNotEqual(code,0);self.assertFalse(executed);self.assertEqual((central,archive),(3,1))

    def test_only_429_may_select_the_archive(self):
        for status,calls in ((403,1),(503,3)):
            with self.subTest(status=status):
                code,executed,central,archive=self.run_case([status],archive_status=200)
                self.assertNotEqual(code,0);self.assertFalse(executed);self.assertEqual((central,archive),(calls,0))

    def test_primary_checksum_failure_cannot_select_another_source(self):
        code,executed,central,archive=self.run_case([200],corrupt=True,archive_status=200)
        self.assertNotEqual(code,0);self.assertFalse(executed);self.assertEqual((central,archive),(1,0))

    def test_archive_requires_an_explicit_checksum(self):
        code,executed,central,archive=self.run_case([429],archive_status=200,missing_checksum=True)
        self.assertNotEqual(code,0);self.assertFalse(executed);self.assertEqual((central,archive),(3,0))


if __name__ == '__main__':
    unittest.main()
