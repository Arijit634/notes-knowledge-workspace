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
import tempfile
import threading
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class WrapperTransportTest(unittest.TestCase):
    def run_case(self, statuses, corrupt=False):
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

            class Handler(http.server.BaseHTTPRequestHandler):
                def do_GET(self):
                    if self.path != '/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip':
                        self.send_error(404)
                        return
                    requests.append(self.path)
                    status = statuses[min(len(requests)-1, len(statuses)-1)]
                    self.send_response(status)
                    self.send_header('Content-Length', str(len(payload) if status == 200 else 0))
                    if status in (429, 503):
                        self.send_header('Retry-After', '1')
                    self.end_headers()
                    if status == 200:
                        self.wfile.write(payload)

                def log_message(self, *_):
                    pass

            server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            try:
                (directory / '.mvn/wrapper/maven-wrapper.properties').write_text(
                    'distributionType=only-script\n'
                    'distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip\n'
                    'distributionSha256Sum=' + hashlib.sha256(approved).hexdigest() + '\n')
                marker = directory / 'executed'
                env = {key: os.environ[key] for key in ('PATH', 'JAVA_HOME') if key in os.environ}
                env.update(HOME=str(directory / 'home'), MAVEN_USER_HOME=str(directory / 'cache'),
                           MVNW_REPOURL='http://127.0.0.1:' + str(server.server_port) + '/maven2',
                           NO_PROXY='127.0.0.1', NKW_WRAPPER_TEST_MARKER=str(marker))
                result = subprocess.run(['/bin/sh', './mvnw', '--version'], cwd=directory,
                                        env=env, capture_output=True, timeout=30)
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


if __name__ == '__main__':
    unittest.main()
