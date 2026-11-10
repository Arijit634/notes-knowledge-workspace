#!/usr/bin/env python3
"""Closed-egress native test of the actual rootless, single-container preview."""
import base64
import importlib.util
import ipaddress
import io
import json
import os
from pathlib import Path
import re
import subprocess
import struct
import sys
import tempfile
import time
import urllib.error
import urllib.request
import urllib.parse
import uuid
import wave
import zipfile
import zlib

ROOT=Path(__file__).resolve().parent
REPO=ROOT.parents[2]
PG='pgvector/pgvector:0.8.6-pg18-trixie@sha256:78bf48b801e792f99e3ac62b5036fd3876e9be48afda16c1e331af1c75ceb2ff'
HOST='ep-fixture.ap-southeast-1.aws.neon.tech'
OTHER_HOST='ep-other.ap-southeast-1.aws.neon.tech'

def run(*args,timeout=240):
    # Docker can write image-pull progress to stderr during create. An identifier
    # or JSON response must never be parsed from a merged diagnostic stream.
    return subprocess.run(args,capture_output=True,text=True,check=True,timeout=timeout).stdout.strip()

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):return None

def await_readiness(request,timeout=600,now=time.monotonic,pause=time.sleep):
    deadline=now()+timeout
    previous=None;samples=0
    while now()<deadline:
        try:
            code,_,body=request('/_preview/ready',authenticated=False)
            if code==200:
                if json.loads(body)!={'status':'UP'}:raise ValueError('Readiness disclosed details or was not UP')
                return
            # During servlet/management initialization the edge may return a
            # non-ready status. None is success; only exact UP before the fixed
            # deadline qualifies. Record bounded status-only diagnostics.
            if code!=previous and samples<12:
                print(json.dumps({'readinessPendingStatus':code}),flush=True)
                previous=code;samples+=1
        except (urllib.error.URLError,ConnectionError,TimeoutError):pass
        pause(0.25)
    raise RuntimeError('Managed preview readiness deadline exceeded')

def main():
    if os.name!='posix' or len(sys.argv)!=2 or not re.fullmatch(r'sha256:[0-9a-f]{64}',sys.argv[1]):
        raise ValueError('Native Linux and immutable local image required')
    image=sys.argv[1];prefix='nkw-managed-'+uuid.uuid4().hex[:12]
    network=None;volume=None;pg=None;app=None;holder=None;provider=None;containers=[]
    with tempfile.TemporaryDirectory(prefix=prefix) as temporary:
        temp=Path(temporary);os.chmod(temp,0o755)
        cert=temp/'cert';cert.mkdir(mode=0o755)
        secret=temp/'secrets';secret.mkdir(mode=0o755)
        spec=importlib.util.spec_from_file_location('preview_init',ROOT.parent/'private-preview/initialize.py')
        init=importlib.util.module_from_spec(spec);spec.loader.exec_module(init)
        values,owner,runtime,redis=init.configuration('https://preview.example.test','syntheticnamespace','ap-mumbai-1',
            'synthetic-private-bucket','synthetic.apps.googleusercontent.com','synthetic-client-secret',
            'synthetic-access-key','synthetic-storage-secret')
        values['nkw.preview.database-host']=HOST
        for value in values.values():
            if '\n' in value or '\r' in value or '\\' in value:raise ValueError('Unframed fixture value')
        (secret/'runtime.properties').write_text('\n'.join(k+'='+v for k,v in values.items())+'\n')
        (secret/'preview-origin').write_text('https://preview.example.test')
        (secret/'redis.conf').write_text(f'bind 127.0.0.1\nprotected-mode yes\nrequirepass "{redis}"\nmaxmemory 32mb\nmaxmemory-policy noeviction\nsave ""\nappendonly no\ndir /tmp/nkw-redis\n')
        (cert/'owner').write_text(owner)
        url='jdbc:postgresql://'+HOST+':5432/nkw_preview?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&connectTimeout=10&socketTimeout=30'
        (cert/'migration.properties').write_text(f'profile=managed-preview\ndatabaseHost={HOST}\nurl={url}\nuser=nkw_preview_migrator\npassword={owner}\nruntimePassword={runtime}\n')
        (cert/'migration-wrong-host.properties').write_text((cert/'migration.properties').read_text().replace(HOST,OTHER_HOST))
        run('openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=NKW synthetic fixture CA',
            '-keyout',str(cert/'ca.key'),'-out',str(cert/'ca.crt'))
        run('openssl','req','-newkey','rsa:2048','-nodes','-subj','/CN='+HOST,
            '-keyout',str(cert/'server.key'),'-out',str(cert/'server.csr'))
        (cert/'server.ext').write_text('subjectAltName=DNS:'+HOST+'\nextendedKeyUsage=serverAuth\n')
        run('openssl','x509','-req','-days','1','-in',str(cert/'server.csr'),'-CA',str(cert/'ca.crt'),
            '-CAkey',str(cert/'ca.key'),'-CAcreateserial','-extfile',str(cert/'server.ext'),'-out',str(cert/'server.crt'))
        storage_host='syntheticnamespace.compat.objectstorage.ap-mumbai-1.oraclecloud.com'
        run('openssl','req','-newkey','rsa:2048','-nodes','-subj','/CN=accounts.google.com',
            '-keyout',str(cert/'provider.key'),'-out',str(cert/'provider.csr'))
        (cert/'provider.ext').write_text('subjectAltName=DNS:accounts.google.com,DNS:'+storage_host+'\nextendedKeyUsage=serverAuth\n')
        run('openssl','x509','-req','-days','1','-in',str(cert/'provider.csr'),'-CA',str(cert/'ca.crt'),
            '-CAkey',str(cert/'ca.key'),'-CAcreateserial','-extfile',str(cert/'provider.ext'),'-out',str(cert/'provider.crt'))
        run('keytool','-importcert','-noprompt','-alias','synthetic-fixture','-file',str(cert/'ca.crt'),
            '-keystore',str(cert/'truststore'),'-storepass','synthetic-fixture-only')
        os.chmod(cert/'server.key',0o600);run('sudo','chown','999:999',str(cert/'server.key'))
        os.chmod(cert/'truststore',0o644)
        trust='-Djavax.net.ssl.trustStore=/fixture/truststore -Djavax.net.ssl.trustStorePassword=synthetic-fixture-only'
        try:
            holder=run('docker','create',image)
            containers.append(holder)
            run('docker','cp',holder+':/app/application.jar',str(temp/'application.jar'))
            with zipfile.ZipFile(temp/'application.jar') as jar:
                (temp/'crypto.jar').write_bytes(jar.read('BOOT-INF/lib/spring-security-crypto-7.1.1.jar'))
            hashed=run('java','-Xmx96m','-cp',str(temp/'crypto.jar'),str(ROOT/'FixtureHash.java'))
            if not re.fullmatch(r'\$2[aby]\$12\$[./A-Za-z0-9]{53}',hashed):raise ValueError('Fixture hashing failed')
            (secret/'preview-password-hash').write_text(hashed)
            # Validate the public proxy template without mounting ANY credentials.
            # Diagnostic output here can contain only this synthetic configuration.
            edge_check=subprocess.run(['docker','run','--rm','--network','none','--read-only',
                '--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=8m,uid=65533,gid=65533,mode=700',
                '--entrypoint','bash',image,'-c',
                'set -e; mkdir -p /tmp/nkw-edge/body /tmp/nkw-edge/proxy; '
                'sed s/__PREVIEW_HOST__/preview.example.test/g /app/nginx.conf > /tmp/nkw-edge/nginx.conf; '
                'nginx -e /dev/stderr -t -c /tmp/nkw-edge/nginx.conf'],capture_output=True,text=True)
            if edge_check.returncode:
                print(edge_check.stderr[-3000:])
                raise ValueError('Synthetic proxy configuration invalid')
            network=run('docker','network','create','--internal',prefix)
            gateway=json.loads(run('docker','network','inspect',network))[0]['IPAM']['Config'][0]['Gateway']
            if not ipaddress.ip_address(gateway).is_private:raise ValueError('Fixture bridge is not private')
            ready=temp/'provider-ready.json'
            provider=subprocess.Popen(['sudo','-n','python3',str(ROOT/'provider_fixture.py'),gateway,
                str(cert/'provider.crt'),str(cert/'provider.key'),str(ready)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            deadline=time.monotonic()+20
            while not ready.exists():
                if provider.poll() is not None or time.monotonic()>deadline:raise ValueError('Controlled provider unavailable')
                time.sleep(0.05)
            control_port=json.loads(ready.read_text())['controlPort']
            control=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            def provider_control(path,value=None):
                data=json.dumps(value).encode() if value is not None else None
                req=urllib.request.Request('http://127.0.0.1:'+str(control_port)+path,data=data,
                    headers={'Content-Type':'application/json'})
                with control.open(req,timeout=5) as response:return json.loads(response.read(8192))
            volume=run('docker','volume','create','--label','nkw.synthetic='+prefix,prefix+'-pg')
            def start_postgres():
                identifier=run('docker','create','--network',network,'--network-alias',HOST,'--network-alias',OTHER_HOST,
                    '--mount','type=volume,source='+volume+',target=/var/lib/postgresql',
                    '-v',str(cert)+':/fixture:ro','-e','POSTGRES_USER=nkw_preview_migrator',
                    '-e','POSTGRES_DB=nkw_preview','-e','POSTGRES_PASSWORD_FILE=/fixture/owner',
                    '--memory','768m',PG,'postgres','-c','ssl=on','-c','ssl_cert_file=/fixture/server.crt',
                    '-c','ssl_key_file=/fixture/server.key','-c','log_min_error_statement=panic')
                containers.append(identifier)
                run('docker','start',identifier)
                deadline=time.monotonic()+90
                while time.monotonic()<deadline:
                    probe=subprocess.run(['docker','exec',identifier,'pg_isready','-U','nkw_preview_migrator','-d','nkw_preview'],capture_output=True)
                    if probe.returncode==0:return identifier
                    time.sleep(0.25)
                raise RuntimeError('Fixture PostgreSQL unavailable')
            pg=start_postgres()
            migration=['docker','run','--rm','--network',network,'--entrypoint','java',
                '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
                '--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=32m,uid=65533,gid=65533,mode=700',
                '-v',str(cert)+':/fixture:ro']
            command=[image,
                '-Xmx256m','-XX:-HeapDumpOnOutOfMemoryError','-Dloader.main=org.notesknowledge.PreviewMigrationCommand',
                '-cp','/app/application.jar','org.springframework.boot.loader.launch.PropertiesLauncher']
            # Distinct negative TLS cases must fail before the positive migration.
            for additional,configuration in [([], '/fixture/migration.properties'),
                    (['-e','JAVA_TOOL_OPTIONS='+trust],'/fixture/migration-wrong-host.properties')]:
                rejected=subprocess.run(migration+additional+command+[configuration],capture_output=True,timeout=60)
                if rejected.returncode!=2:raise ValueError('Untrusted certificate or wrong hostname accepted')
            run(*migration,'-e','JAVA_TOOL_OPTIONS='+trust,*command,'/fixture/migration.properties')
            def start_app():
                identifier=run('docker','create','--network',network,'--read-only',
                    '--memory','448m','--memory-swap','448m','--cpus','0.1','--pids-limit','512',
                    '--cap-drop','ALL','--security-opt','no-new-privileges',
                    '--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=32m,uid=65533,gid=65533,mode=700',
                    '-v',str(secret)+':/etc/secrets:ro','-v',str(cert/'truststore')+':/fixture/truststore:ro',
                    '--add-host','accounts.google.com:'+gateway,'--add-host',storage_host+':'+gateway,
                    '-e','JAVA_TOOL_OPTIONS='+trust,'-e','APP_VERSION=synthetic-managed-smoke',image)
                containers.append(identifier)
                run('docker','start',identifier)
                return identifier
            started=time.monotonic();app=start_app()
            opener=urllib.request.build_opener(NoRedirect(),urllib.request.ProxyHandler({}))
            auth='Basic '+base64.b64encode(b'preview:synthetic-preview-perimeter-only').decode()
            authenticated_seconds=[]
            def request(path,*,authenticated=True,method='GET',cookie=None,csrf=None,extra=None,data=None,expected_redirect=False):
                metadata=json.loads(run('docker','inspect',app))[0]
                if not metadata['State']['Running']:
                    logs=run('docker','logs',app)
                    known=['preview_port_invalid','preview_configuration_unavailable','preview_origin_invalid',
                        'preview_perimeter_unavailable','preview_edge_configuration_invalid','preview_constituent_stopped',
                        'Restricted private preview configuration required','APPLICATION FAILED TO START',
                        'ConfigDataResourceNotFoundException','OutOfMemoryError']
                    print(json.dumps({'runtimeExited':True,'exitCode':metadata['State']['ExitCode'],
                        'oomKilled':metadata['State']['OOMKilled'],'observedSafeCodes':[code for code in known if code in logs]}))
                    raise ValueError('Runtime exited before readiness')
                # Internal networks intentionally do not publish host ports. The
                # native Linux runner reaches this isolated bridge directly;
                # there is no external egress or public/loopback port binding.
                networks=list(metadata['NetworkSettings']['Networks'].values())
                if len(networks)!=1 or networks[0]['NetworkID']!=network:
                    raise ValueError('Unexpected fixture network')
                address=ipaddress.ip_address(networks[0]['IPAddress'])
                if address.version!=4 or not address.is_private or address.is_loopback:
                    raise ValueError('Fixture address outside private bridge')
                if metadata['HostConfig']['PortBindings']:raise ValueError('Fixture port exposed')
                headers={'Host':'preview.example.test'}
                if authenticated:headers['Authorization']=auth
                if cookie:headers['Cookie']=cookie
                if csrf:headers['X-CSRF-TOKEN']=csrf
                headers.update(extra or {})
                req=urllib.request.Request('http://'+str(address)+':10000'+path,headers=headers,method=method,data=data)
                request_started=time.monotonic()
                try:response=opener.open(req,timeout=45)
                except urllib.error.HTTPError as error:response=error
                with response:
                    body=response.read(1024*1024+1)
                    if len(body)>1024*1024:raise ValueError('Unbounded fixture response')
                    if 300<=response.status<400 and not expected_redirect:raise ValueError('Unexpected fixture redirect')
                    if authenticated:authenticated_seconds.append(round(time.monotonic()-request_started,3))
                    return response.status,response.headers,body
            await_readiness(request);first=round(time.monotonic()-started,2)
            if request('/login',authenticated=False)[0]!=401:raise ValueError('Missing perimeter accepted')
            if request('/login',extra={'Authorization':'Basic '+base64.b64encode(b'preview:incorrect').decode()})[0]!=401:raise ValueError('Wrong perimeter accepted')
            for path,expected in [('/login',200),('/api/auth/session',200),('/api/notes',401),
                    ('/api/knowledge/query',503),('/api/public/explore',503),('/actuator/health',404),
                    ('/api/auth/login/password',503)]:
                if request(path)[0]!=expected:raise ValueError('Managed boundary status failed')
            if request('/login',extra={'Forwarded':'host=evil.invalid;proto=http','X-Forwarded-Host':'evil.invalid',
                    'X-Forwarded-Proto':'http','X-Forwarded-For':'198.51.100.22'})[0]!=200:raise ValueError('Caller forwarding became authority')
            if request('/login',extra={'Host':'evil.invalid'})[0]!=400:raise ValueError('Unowned host accepted')
            if request('/_preview/ready',authenticated=False,method='POST')[0]!=405:raise ValueError('Readiness method widened')
            code,_,body=request('/api/auth/oidc/google/callback?state=synthetic-state-marker&code=synthetic-code-marker',
                extra={'Cookie':'QUALIFICATION=synthetic-cookie-marker'})
            markers=['synthetic-state-marker','synthetic-code-marker','synthetic-cookie-marker',auth]
            if code!=401 or any(marker.encode() in body for marker in markers):raise ValueError('Invalid callback boundary failed')
            logs=run('docker','logs',app)
            if any(marker in logs for marker in markers):raise ValueError('Edge or application logs disclosed request material')
            code,headers,body=request('/api/auth/csrf')
            cookie=headers['Set-Cookie'];csrf=json.loads(body)['csrfToken']
            if code!=200 or not all(s in cookie for s in ['__Host-NKW_PREVIEW=','Secure','HttpOnly','SameSite=Lax','Path=/']):raise ValueError('Unsafe cookie')
            if 'Domain=' in cookie:raise ValueError('Cookie domain scope widened')
            cookie=cookie.split(';',1)[0]
            if request('/api/auth/logout',method='POST',cookie=cookie)[0]!=403:raise ValueError('CSRF missing proof accepted')
            def login(identity):
                code,headers,body=request('/api/auth/csrf')
                if code!=200:raise ValueError('Synthetic login CSRF unavailable')
                browser_cookie=headers['Set-Cookie'].split(';',1)[0];proof=json.loads(body)['csrfToken']
                code,headers,body=request('/api/auth/oidc/google/authorizations',method='POST',cookie=browser_cookie,csrf=proof,
                    extra={'Content-Type':'application/json'},data=json.dumps({'intendedDestination':'/notes'}).encode())
                if code!=200:raise ValueError('Controlled OIDC start failed')
                if headers.get('Set-Cookie'):browser_cookie=headers['Set-Cookie'].split(';',1)[0]
                url=urllib.parse.urlsplit(json.loads(body)['authorizationUrl'])
                if url.scheme!='https' or url.hostname!='accounts.google.com':raise ValueError('Unowned controlled provider')
                parameters=urllib.parse.parse_qs(url.query)
                if parameters['code_challenge_method']!=['S256']:raise ValueError('PKCE contract changed')
                issued=provider_control('/authorize',{'nonce':parameters['nonce'][0],
                    'challenge':parameters['code_challenge'][0],'identity':identity})
                markers.extend([browser_cookie,parameters['state'][0],issued['code']])
                query=urllib.parse.urlencode({'state':parameters['state'][0],'code':issued['code'],'iss':'https://accounts.google.com'})
                code,headers,_=request('/api/auth/oidc/google/callback?'+query,cookie=browser_cookie,expected_redirect=True)
                if code!=303 or headers.get('Location')!='/notes':raise ValueError('Controlled OIDC callback failed; status='+str(code))
                browser_cookie=headers['Set-Cookie'].split(';',1)[0]
                markers.append(browser_cookie)
                code,_,body=request('/api/auth/csrf',cookie=browser_cookie)
                if code!=200:raise ValueError('Authenticated CSRF unavailable')
                return browser_cookie,json.loads(body)['csrfToken']
            owner_cookie,owner_csrf=login('owner')
            print(json.dumps({'controlledOwnerLogin':True}),flush=True)
            def json_request(path,value,*,method='POST',if_match=None):
                extra={'Content-Type':'application/json'}
                if if_match:extra['If-Match']=if_match
                return request(path,method=method,cookie=owner_cookie,csrf=owner_csrf,extra=extra,data=json.dumps(value).encode())
            code,headers,body=json_request('/api/notes',{'title':'Budget fixture','markdown':'budgetmarker evidence','aiEnabled':False})
            if code!=201:raise ValueError('Authenticated Note creation failed')
            note=json.loads(body);note_path='/api/notes/'+note['id'];old_etag=headers['ETag']
            code,headers,body=json_request(note_path,{'title':'Budget fixture saved','markdown':'budgetmarker explicitly saved'},method='PUT',if_match=old_etag)
            if code!=200 or json.loads(body)['markdown']!='budgetmarker explicitly saved':raise ValueError('Explicit Note Save failed')
            if json_request(note_path,{'title':'stale','markdown':'stale'},method='PUT',if_match=old_etag)[0]!=412:raise ValueError('Stale mutation accepted')
            code,_,body=json_request('/api/notes/search',{'query':'budgetmarker'})
            if code!=200 or note['id'] not in body.decode():raise ValueError('Ordinary search missed owned Note')
            if request('/api/me/profile',cookie=owner_cookie)[0]!=200:raise ValueError('Private profile unavailable')
            if request('/api/me/security/sessions',cookie=owner_cookie)[0]!=200:raise ValueError('Current-session listing unavailable')
            print(json.dumps({'authenticatedNoteSearchAndProfile':True}),flush=True)
            def png_chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
            png=b'\x89PNG\r\n\x1a\n'+png_chunk(b'IHDR',struct.pack('>IIBBBBB',8,8,8,2,0,0,0))+png_chunk(b'IDAT',zlib.compress((b'\0'+b'\x40\x80\xc0'*8)*8))+png_chunk(b'IEND',b'')
            buffer=io.BytesIO()
            with wave.open(buffer,'wb') as audio:audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(8000);audio.writeframes(b'\0\0'*3200)
            attachments=[]
            for name,media,payload in [('probe.png','image/png',png),('probe.wav','audio/wav',buffer.getvalue())]:
                boundary='nkw-controlled-upload'
                upload=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: {media}\r\n\r\n').encode()+payload+f'\r\n--{boundary}--\r\n'.encode()
                code,_,body=request(note_path+'/attachments',method='POST',cookie=owner_cookie,csrf=owner_csrf,
                    extra={'Content-Type':'multipart/form-data; boundary='+boundary},data=upload)
                if code!=201:raise ValueError('Authenticated synthetic upload failed')
                attachment_path=note_path+'/attachments/'+json.loads(body)['id']
                code,_,received=request(attachment_path+'/content',cookie=owner_cookie)
                if code!=200:raise ValueError('Private byte delivery failed; status='+str(code))
                if media=='image/png':
                    # Safe image admission rewrites the raster and strips metadata.
                    # Check every decoded pixel, then preserve exact accepted bytes
                    # for the later recreation/readback assertion.
                    check=temp/'accepted-fixture.png';check.write_bytes(received)
                    if run('java','-Xmx64m',str(ROOT/'FixtureImage.java'),str(check))!='synthetic-raster-verified':
                        raise ValueError('Canonical PNG changed synthetic pixels')
                elif received!=payload:raise ValueError('PCM WAV bytes changed')
                attachments.append((attachment_path,received))
            print(json.dumps({'authenticatedPngAndWav':True}),flush=True)
            other_cookie,_=login('other')
            if request(note_path,cookie=other_cookie)[0]!=404 or request(attachments[0][0]+'/content',cookie=other_cookie)[0]!=404:
                raise ValueError('Cross-owner isolation failed')
            samples=[]
            def resources():
                metadata=json.loads(run('docker','inspect',app))[0]
                if metadata['Config']['User']!='65533:65533' or not metadata['HostConfig']['ReadonlyRootfs']:
                    raise ValueError('Runtime confinement changed')
                events=dict(line.split() for line in run('docker','exec',app,'cat','/sys/fs/cgroup/memory.events').splitlines())
                if metadata['State']['OOMKilled'] or metadata['RestartCount'] or int(events['oom']) or int(events['oom_kill']):raise ValueError('Resource failure')
                peak=int(run('docker','exec',app,'cat','/sys/fs/cgroup/memory.peak'))
                if peak>448*1024*1024:raise ValueError('Budget exceeded')
                sockets=run('docker','exec',app,'cat','/proc/net/tcp','/proc/net/tcp6').splitlines()
                for port in (8080,8081,6379):
                    listeners=[line.split()[1].split(':')[0].upper() for line in sockets
                        if len(line.split())>3 and line.split()[3]=='0A' and line.split()[1].endswith(':'+format(port,'04X'))]
                    if not listeners or any(address not in ('0100007F','0000000000000000FFFF00000100007F') for address in listeners):raise ValueError('Private listener exposed')
                samples.append({'peakBytes':peak,'memoryLimitBytes':metadata['HostConfig']['Memory'],
                    'cpuQuota':metadata['HostConfig']['NanoCpus'],'oomEvents':0,'restarts':0,'privateListenersLoopbackOnly':True})
            resources()
            stopped=time.monotonic();run('docker','stop','--time','28',app,timeout=40)
            shutdown=round(time.monotonic()-stopped,2)
            metadata=json.loads(run('docker','inspect',app))[0]
            if shutdown>=30 or metadata['State']['ExitCode']!=0:raise ValueError('Graceful bounded shutdown failed')
            run('docker','rm',app);app=None
            run('docker','stop',pg);run('docker','rm',pg);pg=None
            pg=start_postgres();started=time.monotonic();app=start_app();await_readiness(request);second=round(time.monotonic()-started,2)
            code,headers,_=request('/api/auth/csrf',cookie=cookie)
            if code!=200 or headers.get('Set-Cookie'):raise ValueError('Session did not survive recreation')
            code,headers,body=request(note_path,cookie=owner_cookie)
            if code!=200 or json.loads(body)['title']!='Budget fixture saved':raise ValueError('Authenticated Note/session did not survive recreation')
            for attachment_path,payload in attachments:
                code,_,received=request(attachment_path+'/content',cookie=owner_cookie)
                if code!=200 or received!=payload:raise ValueError('Private files did not survive recreation')
            if request('/api/auth/logout',method='POST',cookie=owner_cookie,csrf=owner_csrf)[0]!=204:raise ValueError('Authenticated logout failed')
            if request(note_path,cookie=owner_cookie)[0]!=401:raise ValueError('Logged-out authority retained')
            owner_cookie,owner_csrf=login('owner')
            if request(note_path,cookie=owner_cookie)[0]!=200:raise ValueError('Returning OIDC login lost owned data')
            sql=lambda db,q:run('docker','exec',pg,'psql','-X','-U','nkw_preview_migrator','-d',db,'-At','-v','ON_ERROR_STOP=1','-c',q)
            if sql('nkw_preview','select count(*) from public.flyway_schema_history where success')!='20':raise ValueError('Migration count differs')
            run('docker','exec',pg,'pg_dump','-U','nkw_preview_migrator','-d','nkw_preview','-Fc','-f','/tmp/drill.dump')
            sql('nkw_preview','create database nkw_preview_restore')
            sql('nkw_preview','revoke connect on database nkw_preview_restore from public;revoke connect on database nkw_preview_restore from nkw_preview_runtime')
            run('docker','exec',pg,'pg_restore','-U','nkw_preview_migrator','--no-owner','--no-acl','-d','nkw_preview_restore','/tmp/drill.dump')
            run('docker','cp',str(REPO/'src/main/resources/operations/restore-security-quarantine.sql'),pg+':/tmp/quarantine.sql')
            run('docker','exec',pg,'psql','-X','-U','nkw_preview_migrator','-d','nkw_preview_restore','-v','ON_ERROR_STOP=1',
                '--set=runtime_role=nkw_preview_runtime','--set=restore_confirmation=invalidate_uncertain_authority','-f','/tmp/quarantine.sql')
            if sql('nkw_preview_restore','select count(*) from identity.spring_session')!='0':raise ValueError('Restore authority retained')
            if sql('nkw_preview_restore',"select has_database_privilege('nkw_preview_runtime',current_database(),'CONNECT')")!='f':raise ValueError('Restored runtime reachable')
            if request('/api/auth/logout',method='POST',cookie=cookie,csrf=csrf)[0]!=204:raise ValueError('Persisted CSRF proof rejected')
            resources()
            controlled=provider_control('/counts')
            if controlled['token']!=3 or controlled['objectPut']!=3 or controlled['objectGet']<4:raise ValueError('Controlled workload incomplete')
            if any(marker in run('docker','logs',app) for marker in markers):raise ValueError('Authenticated logs disclosed request material')
            print(json.dumps({'image':image,'nativeArchitecture':run('uname','-m'),'rootlessSingleContainer':True,
                'appProxyRedisCombinedBudget':True,'firstBootSeconds':first,'recreatedBootSeconds':second,
                'gracefulShutdownSeconds':shutdown,'privateListenersLoopbackOnly':True,'perimeterAndCsrfDenials':True,
                'untrustedForwardingDiscarded':True,'sessionAndCsrfSurviveAppAndPgRecreation':True,
                'isolatedLogicalRestoreQuarantine':True,'migrations':20,'databaseTlsVerifiedWithIsolatedFixtureCa':True,
                'untrustedDatabaseCertificateAndHostnameRejected':True,'callbackLogPrivacy':True,
                'perimeterBcryptCost':12,'authenticatedRequestSeconds':authenticated_seconds,
                'authenticatedCoreWorkload':True,'controlledOidcSignatureNoncePkce':True,'crossOwnerDenial':True,
                'authenticatedSessionNoteAndFilesSurviveRecreation':True,'returningLoginAndLogout':True,
                'controlledProviderCounts':controlled,'publicTlsOrProviderQualified':False,'liveProviderCalls':0,
                'realDataUsed':False,'resources':samples}))
        finally:
            if provider is not None:
                provider.terminate()
                try:provider.wait(timeout=10)
                except subprocess.TimeoutExpired:provider.kill();provider.wait(timeout=10)
            for identifier in reversed(containers):
                if identifier:subprocess.run(['docker','rm','-f',identifier],capture_output=True)
            if volume:subprocess.run(['docker','volume','rm',volume],capture_output=True)
            if network:subprocess.run(['docker','network','rm',network],capture_output=True)

if __name__=='__main__':main()
