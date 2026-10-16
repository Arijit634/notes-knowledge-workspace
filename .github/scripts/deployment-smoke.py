#!/usr/bin/env python3
"""Disposable Linux smoke of the actual deployment JAR/factories, not a Spring test context.

Usage: deployment-smoke.py DEPLOYMENT_IMAGE QUALIFICATION_IMAGE NEW_EVIDENCE_DIRECTORY
Only the endpoint/migration fixture process uses the qualification image. The service
gets no test classes, Docker socket, host credentials, external providers or network egress.
"""
import base64
import hashlib
import http.cookiejar
import json
import os
import pathlib
import platform
import secrets
import shutil
import ssl
import struct
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zlib

HOST = 'syntheticnamespace.compat.objectstorage.ap-mumbai-1.oci.customer-oci.com'
PASSWORD = 'Synthetic-smoke-password-731!'


def require(value, code):
    if not value:
        raise RuntimeError(code)


def command(*args, input=None, check=True):
    result = subprocess.run(args, input=input, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=120)
    if check and result.returncode:
        raise RuntimeError('smoke_command_failed_' + args[0])
    return result


class Browser:
    def __init__(self, origin, certificate):
        self.origin = origin
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(self.cookies),
            urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=certificate)))
        self.csrf = None
        self.sensitive = []

    def request(self, method, path, data=None, headers=None, csrf=True):
        headers = dict(headers or {})
        if isinstance(data, dict):
            data = json.dumps(data).encode()
            headers['Content-Type'] = 'application/json'
        if method not in ('GET', 'HEAD') and csrf and self.csrf:
            headers['X-CSRF-TOKEN'] = self.csrf
        request = urllib.request.Request(self.origin + path, data=data, headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=35)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read(26 * 1024 * 1024)
            result = response.status, dict(response.headers), body
        self.sensitive.extend(cookie.value for cookie in self.cookies)
        return result

    def bootstrap(self):
        status, _, body = self.request('GET', '/api/auth/csrf')
        require(status == 200, 'csrf_bootstrap_failed')
        self.csrf = json.loads(body)['csrfToken']
        self.sensitive.append(self.csrf)

    def upload(self, note, payload):
        boundary = 'synthetic-smoke-multipart'
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="synthetic.png"\r\n'
                'Content-Type: image/png\r\n\r\n').encode() + payload + ('\r\n--' + boundary + '--\r\n').encode()
        return self.request('POST', '/api/notes/' + note + '/attachments', body,
                            {'Content-Type': 'multipart/form-data; boundary=' + boundary})


def synthetic_png():
    def chunk(kind, value):
        return struct.pack('!I', len(value)) + kind + value + struct.pack('!I', zlib.crc32(kind + value))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!IIBBBBB', 16, 16, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress((b'\x00' + b'\x00\x00\xff' * 16) * 16)) + chunk(b'IEND', b''))


def main(deployment, qualification, evidence):
    require(platform.system() == 'Linux', 'native_linux_required')
    require(deployment.startswith('sha256:') and qualification.startswith('sha256:'), 'exact_images_required')
    output = pathlib.Path(evidence).resolve()
    output.mkdir(exist_ok=False, parents=True)
    temporary = pathlib.Path(tempfile.mkdtemp(prefix='backend-smoke-', dir=os.environ['RUNNER_TEMP'])).resolve()
    prefix = 'nkw-smoke-' + uuid.uuid4().hex[:12]
    network, volume = prefix + '-network', prefix + '-database'
    resources, browsers, events = [], [], []
    canaries = [PASSWORD, 'synthetic-owner@example.test', 'synthetic-other@example.test',
                'synthetic-access-key', 'synthetic-secret-key', 'PRIVATE_SMOKE_TITLE_731', 'PRIVATE_SMOKE_BODY_731']
    log_events = []

    def docker(*args, **kwargs):
        return command('docker', *args, **kwargs)

    def event(step, **facts):
        row = dict(step=step, **facts)
        events.append(row)
        print(json.dumps(dict(deploymentSmoke=True, **row)), flush=True)

    def collect_logs(name):
        raw = docker('logs', name, check=False)
        text = raw.stdout + raw.stderr
        sensitive = canaries + [value for browser in browsers for value in browser.sensitive]
        require(not any(value and value in text for value in sensitive), 'application_log_privacy_failure')
        # No raw message, exception, SQL, path, header or field value leaves this boundary.
        for line in text.splitlines():
            try:
                row = json.loads(line)
                level = row.get('log', {}).get('level')
                if level not in ('TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR'):
                    level = 'UNKNOWN'
                log_events.append(dict(origin='packaged_backend', level=level, messageRetained=False))
            except (ValueError, AttributeError):
                log_events.append(dict(origin='packaged_backend', level='UNSTRUCTURED', messageRetained=False))

    def start_app(label, overrides=None):
        config = dict(properties)
        config.update(overrides or {})
        name = prefix + '-' + label
        path = temporary / (label + '.properties')
        path.write_text(''.join(key + '=' + str(value) + '\n' for key, value in config.items()))
        path.chmod(0o644)
        args = ['create', '--name', name, '--label', 'nkw.smoke.invocation=' + prefix, '--network', network, '--init', '--user', '65533:65533',
                '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--read-only', '--memory', '2g',
                '--cpus', '2', '--pids-limit', '512', '--tmpfs', '/tmp:rw,noexec,nosuid,nodev,size=512m',
                '-p', '127.0.0.1::8443', '-v', str(temporary) + ':/config:ro']
        for directory, size in [('pdf-recovery', '4m'), ('video-recovery', '4m'), ('video-scratch', '192m')]:
            args += ['--tmpfs', '/private/' + directory + ':rw,noexec,nosuid,nodev,size=' + size + ',uid=65533,gid=65533,mode=0700']
        args += ['--entrypoint', '/jdk/bin/java', deployment, '-Xmx512m',
                 '-Djavax.net.ssl.trustStore=/config/trust.p12', '-Djavax.net.ssl.trustStorePassword=synthetic-keystore-password',
                 '-jar', '/application.jar', '--spring.config.additional-location=file:/config/' + label + '.properties']
        docker(*args)
        resources.append(name)
        docker('start', name)
        port = docker('port', name, '8443/tcp').stdout.strip().split(':')[-1]
        browser = Browser('https://localhost:' + port, str(temporary / 'certificate.pem'))
        browsers.append(browser)
        return name, browser

    def state(name):
        return json.loads(docker('inspect', '--format', '{{json .State}}', name).stdout)

    def await_health(browser, expected, timeout=75):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                status, _, body = browser.request('GET', '/actuator/health/readiness')
                if status == expected:
                    require(set(json.loads(body)) == {'status'}, 'health_details_exposed')
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.5)  # Bounded external service readiness polling, not a flaky assertion sleep.
        raise RuntimeError('readiness_transition_failed_' + str(expected))

    def stop_app(name):
        started = time.monotonic()
        docker('stop', '-t', '40', name)
        info = state(name)
        require(not info['Running'] and not info['OOMKilled'] and info['ExitCode'] in (0, 143)
                and info['Pid'] == 0 and time.monotonic() - started < 40, 'shutdown_boundary_failed')
        collect_logs(name)
        event('bounded_shutdown', containerProcessesRetained=0, oomKilled=False)

    try:
        temporary.chmod(0o755)  # Dedicated synthetic files must be readable by the service UID.
        docker('network', 'create', '--internal', '--label', 'nkw.purpose=deployment-smoke', network)
        docker('volume', 'create', '--label', 'nkw.purpose=deployment-smoke', volume)
        for suffix, alias, image, rest in [
            ('db', 'database', 'pgvector/pgvector:0.8.6-pg18-trixie',
             ['-e', 'POSTGRES_DB=deployment_smoke', '-e', 'POSTGRES_USER=synthetic_migrator',
              '-e', 'POSTGRES_PASSWORD=synthetic-migration-password', '-v', volume + ':/var/lib/postgresql']),
            ('redis', 'rate-store', 'redis:8.10.2', [])]:
            name = prefix + '-' + suffix
            docker('run', '-d', '--name', name, '--network', network, '--network-alias', alias,
                   '--memory', '512m', '--cpus', '1', '--pids-limit', '128', *rest, image)
            resources.append(name)
        database = prefix + '-db'
        for _ in range(60):
            if docker('exec', database, 'pg_isready', '-U', 'synthetic_migrator', '-d', 'deployment_smoke', check=False).returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError('synthetic_database_start_failed')
        # All certificate/private-key material is synthetic, temporary, never evidence or host trust.
        command('openssl', 'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1',
                '-nodes', '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,DNS:' + HOST,
                '-keyout', str(temporary / 'key.pem'), '-out', str(temporary / 'certificate.pem'))
        command('openssl', 'pkcs12', '-export', '-name', 'synthetic-localhost', '-inkey', str(temporary / 'key.pem'),
                '-in', str(temporary / 'certificate.pem'), '-out', str(temporary / 'endpoint.p12'),
                '-passout', 'pass:synthetic-keystore-password')
        for path in temporary.iterdir():
            path.chmod(0o644)
        tool = ['run', '--rm', '--network', network, '--user', '65533:65533', '--cap-drop', 'ALL',
                '--security-opt', 'no-new-privileges', '--read-only', '--memory', '512m', '--cpus', '1',
                '--pids-limit', '128', '--tmpfs', '/tmp:rw,nosuid,nodev,size=64m',
                '-v', str(temporary) + ':/config', qualification]
        docker(*tool, '-Dloader.path=/qualification', '-Dloader.main=org.notesknowledge.DeploymentSmokeDatabase',
               '-cp', '/application.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher', 'initialize')
        # Trust only the synthetic endpoint certificate, without disabling TLS validation.
        docker('run', '--rm', '--network', 'none', '--user', '0:0', '--memory', '256m',
               '-v', str(temporary) + ':/config', '--entrypoint', '/jdk/bin/keytool', qualification,
               '-importcert', '-noprompt', '-alias', 'synthetic-endpoint', '-file', '/config/certificate.pem',
               '-keystore', '/config/trust.p12', '-storepass', 'synthetic-keystore-password')
        (temporary / 'trust.p12').chmod(0o644)
        selected = docker('run', '--rm', '--network', 'none', '--entrypoint', '/bin/cat', deployment,
                          '/provenance/runtime-selection.properties').stdout
        properties = dict(line.split('=', 1) for line in selected.splitlines() if line and not line.startswith('#'))
        properties.update({'spring.datasource.url': 'jdbc:postgresql://database:5432/deployment_smoke',
                           'spring.datasource.username': 'synthetic_runtime', 'spring.datasource.password': 'synthetic-runtime-password',
                           'spring.datasource.hikari.maximum-pool-size': '5', 'spring.datasource.hikari.minimum-idle': '0',
                           'spring.datasource.hikari.connection-timeout': '2000', 'spring.datasource.hikari.validation-timeout': '1000',
                           'spring.data.redis.host': 'rate-store', 'spring.data.redis.port': '6379',
                           'server.port': '8443', 'server.ssl.enabled': 'true', 'server.ssl.key-store': 'file:/config/endpoint.p12',
                           'server.ssl.key-store-password': 'synthetic-keystore-password', 'server.ssl.key-store-type': 'PKCS12',
                           'identity.delivery.polling-enabled': 'false', 'identity.delivery.smtp-enabled': 'false',
                           'identity.oidc.google.enabled': 'false', 'knowledge.derivation.enabled': 'false',
                           'knowledge.derivation.scheduling-enabled': 'false', 'knowledge.operations.scheduling-enabled': 'false',
                           'notes.cursor.active.version': 'synthetic1', 'notes.media-runtime.pdf.enabled': 'true',
                           'notes.media-runtime.video.enabled': 'true', 'notes.media-runtime.pdf.recovery-directory': '/private/pdf-recovery',
                           'notes.media-runtime.video.recovery-directory': '/private/video-recovery',
                           'notes.media-runtime.video.scratch-directory': '/private/video-scratch'})
        for key in ['notes.cursor.active.key-base64', 'identity.rate.key-base64', 'identity.delivery.key-base64',
                    'identity.mfa.key-base64', 'identity.mfa.handle-key-base64', 'identity.session-handle.key-base64']:
            value = base64.b64encode(secrets.token_bytes(32)).decode()
            properties[key] = value
            canaries.append(value)
        for storage in ['notes.private-storage', 'profile.avatar-storage', 'profile.public-avatar-storage', 'publishing.media-storage']:
            properties.update({storage + '.enabled': 'true', storage + '.profile': 'oci-s3-private-v1',
                               storage + '.endpoint': 'https://' + HOST, storage + '.region': 'ap-mumbai-1',
                               storage + '.bucket': 'synthetic-private-bucket', storage + '.credentials-source': 'injected',
                               storage + '.private-bucket-confirmed': 'true', storage + '.reconciliation-enabled': 'false',
                               storage + '.access-key-id': 'synthetic-access-key', storage + '.secret-access-key': 'synthetic-secret-key'})
        objects = temporary / 'objects'
        objects.mkdir(mode=0o777)
        objects.chmod(0o777)
        endpoint = prefix + '-objects'
        docker('run', '-d', '--name', endpoint, '--network', network, '--network-alias', HOST,
               '--user', '65533:65533', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--read-only',
               '--memory', '512m', '--cpus', '1', '--pids-limit', '128', '-v', str(temporary) + ':/config:ro',
               '-v', str(objects) + ':/objects', qualification, '-Dloader.path=/qualification',
               '-Dloader.main=org.notesknowledge.ControlledS3Endpoint', '-cp', '/application.jar',
               'org.springframework.boot.loader.launch.PropertiesLauncher', '/config/endpoint.p12', '/objects')
        resources.append(endpoint)
        app, owner = start_app('service')
        await_health(owner, 200)
        require(owner.request('GET', '/actuator/health/liveness')[0] == 200, 'liveness_failed')
        require(owner.request('GET', '/actuator/env')[0] in (401, 403, 404), 'operational_details_exposed')
        event('production_startup', nativePreflight=True, testApplicationBeans=False, liveProviders=False)
        for browser, email in [(owner, 'synthetic-owner@example.test'), (Browser(owner.origin, str(temporary / 'certificate.pem')), 'synthetic-other@example.test')]:
            if browser not in browsers:
                browsers.append(browser)
            browser.bootstrap()
            require(browser.request('POST', '/api/auth/registrations', {'email': email, 'password': PASSWORD})[0] == 202, 'registration_failed')
            # Explicit synthetic fixture verification, not an exposed API bypass or test bean.
            sql = "update identity.account set email_verified_at=clock_timestamp(),account_state='active' where canonical_email='" + email + "';"
            docker('exec', '-i', database, 'psql', '-U', 'synthetic_migrator', '-d', 'deployment_smoke', '-v', 'ON_ERROR_STOP=1', input=sql)
            status, headers, body = browser.request('POST', '/api/auth/login/password', {'email': email, 'password': PASSWORD})
            require(status == 200 and json.loads(body)['state'] == 'authenticated', 'password_login_failed')
            require(any(cookie.secure and cookie.has_nonstandard_attr('HttpOnly') for cookie in browser.cookies), 'authentication_cookie_weakened')
            browser.bootstrap()
        other = browsers[-1]
        require(owner.request('POST', '/api/notes', {'title': 'blocked', 'markdown': ''}, csrf=False)[0] == 403, 'csrf_not_enforced')
        status, _, body = owner.request('POST', '/api/notes', {'title': canaries[5], 'markdown': canaries[6], 'aiEnabled': False})
        require(status == 201, 'note_create_failed')
        note = json.loads(body)['id']
        require(other.request('GET', '/api/notes/' + note)[0] == 404, 'owner_isolation_failed')
        status, _, body = owner.upload(note, synthetic_png())
        require(status == 201, 'production_storage_upload_failed')
        attachment = json.loads(body)['id']
        content = '/api/notes/' + note + '/attachments/' + attachment + '/content'
        status, _, accepted = owner.request('GET', content)
        require(status == 200 and accepted.startswith(b'\x89PNG'), 'production_storage_read_failed')
        status, headers, partial = owner.request('GET', content, headers={'Range': 'bytes=0-15'})
        require(status == 206 and partial == accepted[:16], 'production_range_integrity_failed')
        require(other.request('GET', content)[0] == 404, 'private_media_isolation_failed')
        event('authorized_storage', rangeIntegrity=True, crossOwnerDenied=True, csrfRequired=True)
        docker('stop', '-t', '5', endpoint)
        require(owner.request('GET', content)[0] == 503, 'storage_outage_not_truthful')
        require(owner.request('GET', '/api/notes/' + note)[0] == 200, 'storage_outage_broke_metadata')
        docker('start', endpoint)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            status, _, recovered = owner.request('GET', content)
            if status == 200:
                require(recovered == accepted, 'storage_recovery_integrity_failed')
                break
            time.sleep(0.5)
        else:
            raise RuntimeError('storage_recovery_failed')
        event('storage_failure_and_recovery', passed=True, actualOci=False)
        docker('stop', '-t', '5', prefix + '-redis')
        require(owner.request('POST', '/api/notes', {'title': 'rate unavailable', 'markdown': ''})[0] == 503, 'rate_store_failed_open')
        docker('start', prefix + '-redis')
        event('rate_store_outage', failedClosed=True)
        docker('stop', '-t', '5', database)
        await_health(owner, 503, 15)
        require(owner.request('GET', '/actuator/health/liveness')[0] == 200, 'database_outage_killed_liveness')
        status, _, unavailable = owner.request('GET', '/api/notes/' + note)
        event('database_outage_private_read', status=status)
        require(status in (401, 403, 503) and canaries[5].encode() not in unavailable
                and canaries[6].encode() not in unavailable, 'database_outage_private_denial_failed')
        docker('start', database)
        await_health(owner, 200)
        require(owner.request('GET', '/api/notes/' + note)[0] == 200, 'database_recovery_failed')
        event('database_restart', readinessDeniedThenRecovered=True, sessionPersisted=True)
        stop_app(app)
        docker('start', app)
        owner.origin = 'https://localhost:' + docker('port', app, '8443/tcp').stdout.strip().split(':')[-1]
        await_health(owner, 200)
        require(owner.request('GET', '/api/notes/' + note)[0] == 200, 'service_restart_lost_session_or_note')
        require(owner.request('GET', content)[2] == accepted, 'service_restart_lost_object')
        stop_app(app)
        event('service_restart', preservedAuthorityAndObjects=True)
        for label, changes in [('missing-storage', {'notes.private-storage.enabled': 'false'}),
                               ('invalid-credential', {'notes.private-storage.access-key-id': 'short'}),
                               ('invalid-runtime', {'notes.media-runtime.video.artifact-identity': '0' * 64})]:
            candidate, browser = start_app(label, changes)
            if label == 'missing-storage':
                await_health(browser, 200)
                # Existing persisted session remains authoritative across this independently started process.
                browser.cookies = owner.cookies
                browser.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(browser.cookies),
                    urllib.request.ProxyHandler({}),
                    urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=str(temporary / 'certificate.pem'))))
                browser.bootstrap()
                require(browser.upload(note, synthetic_png())[0] == 503, 'missing_storage_did_not_fail_closed')
                stop_app(candidate)
            else:
                deadline = time.monotonic() + 75
                while state(candidate)['Running'] and time.monotonic() < deadline:
                    time.sleep(0.5)
                info = state(candidate)
                require(not info['Running'] and info['ExitCode'] != 0 and not info['OOMKilled'], label + '_not_refused')
                collect_logs(candidate)
            event(label.replace('-', '_'), failedClosed=True)
        receipt = dict(deploymentSmoke=True, passed=True, deploymentImage=deployment,
                       qualificationImage=qualification, productionJar=True, testApplicationBeans=False,
                       liveProviderCalls=0, actualOci=False, relations=38, memoryBytes=2147483648,
                       cpus=2, pidsLimit=512, privateScratchBytes=209715200, events=events,
                       rawApplicationLogsExcluded=True, knownSyntheticCanariesAbsent=True)
        (output / 'deployment-smoke.json').write_text(json.dumps(receipt, indent=2) + '\n')
        (output / 'application-sanitized.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in log_events))
        event('complete', passed=True)
    except Exception as failure:
        code = str(failure) if isinstance(failure, RuntimeError) and str(failure).replace('_', '').replace('-', '').isalnum() else 'deployment_smoke_failed'
        # Retain safe failure facts before destroying only this invocation's resources.
        states = []
        for name in resources:
            if name not in (prefix + '-db', prefix + '-redis', prefix + '-objects'):
                info = state(name)
                raw = docker('logs', name, check=False)
                text = raw.stdout + raw.stderr
                states.append(dict(running=info['Running'], exitCode=info['ExitCode'], oomKilled=info['OOMKilled'],
                    databasePrivilegeDenied='permission denied' in text.lower(),
                    nativePreflightDenied='Media runtime preflight unavailable' in text,
                    configurationDenied='configuration unavailable' in text.lower()))
        (output / 'deployment-smoke-failure.json').write_text(json.dumps(dict(passed=False, code=code, events=events, states=states)) + '\n')
        print(json.dumps(dict(deploymentSmoke=True, passed=False, code=code)), flush=True)
        raise RuntimeError(code) from None
    finally:
        # Explicit per-invocation names only; never sweep owner resources or arbitrary paths.
        for name in reversed(resources):
            docker('rm', '-f', '-v', name, check=False)
        docker('volume', 'rm', volume, check=False)
        docker('network', 'rm', network, check=False)
        require(temporary.parent == pathlib.Path(os.environ['RUNNER_TEMP']).resolve()
                and temporary.name.startswith('backend-smoke-'), 'cleanup_path_outside_owned_directory')
        shutil.rmtree(temporary)


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('Three explicit qualification arguments required')
    try:
        main(*sys.argv[1:])
    except (RuntimeError, OSError, subprocess.SubprocessError):
        raise SystemExit('deployment_smoke_failed; inspect sanitized receipt') from None
