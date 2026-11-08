#!/usr/bin/env python3
"""Real edge failure regression with synthetic markers; no ACME or public port."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid

IMAGE='caddy:2.11.9@sha256:0c2243efcf437ab7f7a978f1027fcf0d4f9be8a75df27b1b3fbc5dbe0ffa8cf0'


def main():
    name='nkw-edge-test-'+uuid.uuid4().hex[:10]
    config=Path(__file__).with_name('Caddyfile').read_text().replace('auto_https disable_redirects','auto_https off')
    config=config.replace('https://{$PREVIEW_HOST} {','http://{$PREVIEW_HOST}:8080 {')
    config=config.replace('http://{$PREVIEW_HOST} {','http://{$PREVIEW_HOST}:8082 {')
    with tempfile.TemporaryDirectory(prefix='nkw-edge-test-') as temporary:
        file=Path(temporary)/'Caddyfile';file.write_text(config)
        # A loopback address would hit Caddy's own wildcard listener recursively.
        subprocess.run(['docker','run','-d','--name',name,'--network','none','--add-host','app:192.0.2.10',
            '-e','PREVIEW_HOST=preview.example.test','-v',str(file)+':/etc/caddy/Caddyfile:ro',IMAGE],check=True,capture_output=True)
        try:
            # Wait on a concrete listener check; never repeat an assertion until green.
            for _ in range(30):
                ready=subprocess.run(['docker','exec',name,'sh','-c','nc -z 127.0.0.1 8080'],capture_output=True)
                if ready.returncode==0:break
                time.sleep(0.2)
            else:raise RuntimeError('edge_listener_unavailable')
            result=subprocess.run(['docker','exec',name,'wget','-S','-O','/dev/null',
                '--header','Host: preview.example.test','--header','Cookie: synthetic-cookie-marker',
                '--header','Authorization: synthetic-authorization-marker',
                'http://127.0.0.1:8080/api/auth/oidc/google/callback?code=synthetic-code-marker&state=synthetic-state-marker'],capture_output=True,text=True)
            assert result.returncode!=0 and '502' in result.stderr, 'unreachable upstream must fail'
            logs=subprocess.check_output(['docker','logs',name],stderr=subprocess.STDOUT,text=True)
            assert all(marker not in logs for marker in ['synthetic-cookie-marker','synthetic-authorization-marker','synthetic-code-marker','synthetic-state-marker','/api/auth/oidc/google/callback'])
            entries=[]
            for line in logs.splitlines():
                try:entries.append(json.loads(line))
                except json.JSONDecodeError:pass
            assert any(entry.get('status')==502 and not any(k in entry for k in ['request','resp_headers']) for entry in entries)
            print('edge_upstream_failure_status_retained_without_credentials_or_private_paths')
        finally:
            subprocess.run(['docker','rm','-f',name],check=True,capture_output=True)


if __name__=='__main__':main()
