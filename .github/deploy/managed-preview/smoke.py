#!/usr/bin/env python3
"""Closed-egress native test of the actual rootless, single-container preview."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile

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
    def redirect_request(self,*args,**kwargs):raise RuntimeError('Unexpected fixture redirect')

def main():
    if os.name!='posix' or len(sys.argv)!=2 or not re.fullmatch(r'sha256:[0-9a-f]{64}',sys.argv[1]):
        raise ValueError('Native Linux and immutable local image required')
    image=sys.argv[1];prefix='nkw-managed-'+uuid.uuid4().hex[:12]
    network=None;volume=None;pg=None;app=None;holder=None;containers=[]
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
            network=run('docker','network','create','--internal',prefix)
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
                    '-e','JAVA_TOOL_OPTIONS='+trust,'-e','APP_VERSION=synthetic-managed-smoke',
                    '-p','127.0.0.1::10000',image)
                containers.append(identifier)
                run('docker','start',identifier)
                return identifier
            started=time.monotonic();app=start_app()
            opener=urllib.request.build_opener(NoRedirect(),urllib.request.ProxyHandler({}))
            auth='Basic '+base64.b64encode(b'preview:synthetic-preview-perimeter-only').decode()
            authenticated_seconds=[]
            def request(path,*,authenticated=True,method='GET',cookie=None,csrf=None,extra=None):
                metadata=json.loads(run('docker','inspect',app))[0]
                port=metadata['NetworkSettings']['Ports']['10000/tcp'][0]
                if port['HostIp']!='127.0.0.1':raise ValueError('Fixture port exposed')
                headers={'Host':'preview.example.test'}
                if authenticated:headers['Authorization']=auth
                if cookie:headers['Cookie']=cookie
                if csrf:headers['X-CSRF-TOKEN']=csrf
                headers.update(extra or {})
                req=urllib.request.Request('http://127.0.0.1:'+port['HostPort']+path,headers=headers,method=method)
                request_started=time.monotonic()
                try:response=opener.open(req,timeout=5)
                except urllib.error.HTTPError as error:response=error
                with response:
                    body=response.read(1024*1024+1)
                    if len(body)>1024*1024:raise ValueError('Unbounded fixture response')
                    if authenticated:authenticated_seconds.append(round(time.monotonic()-request_started,3))
                    return response.status,response.headers,body
            def await_ready():
                deadline=time.monotonic()+600
                while time.monotonic()<deadline:
                    try:
                        code,_,body=request('/_preview/ready',authenticated=False)
                        if code==200:
                            if json.loads(body)!={'status':'UP'}:raise ValueError('Readiness disclosed details')
                            return
                        if code not in (502,503):raise ValueError('Unexpected readiness status')
                    except (urllib.error.URLError,ConnectionError,TimeoutError):pass
                    time.sleep(0.25)
                raise RuntimeError('Managed preview readiness deadline exceeded')
            await_ready();first=round(time.monotonic()-started,2)
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
            pg=start_postgres();started=time.monotonic();app=start_app();await_ready();second=round(time.monotonic()-started,2)
            code,headers,_=request('/api/auth/csrf',cookie=cookie)
            if code!=200 or headers.get('Set-Cookie'):raise ValueError('Session did not survive recreation')
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
            print(json.dumps({'image':image,'nativeArchitecture':run('uname','-m'),'rootlessSingleContainer':True,
                'appProxyRedisCombinedBudget':True,'firstBootSeconds':first,'recreatedBootSeconds':second,
                'gracefulShutdownSeconds':shutdown,'privateListenersLoopbackOnly':True,'perimeterAndCsrfDenials':True,
                'untrustedForwardingDiscarded':True,'sessionAndCsrfSurviveAppAndPgRecreation':True,
                'isolatedLogicalRestoreQuarantine':True,'migrations':20,'databaseTlsVerifiedWithIsolatedFixtureCa':True,
                'untrustedDatabaseCertificateAndHostnameRejected':True,'callbackLogPrivacy':True,
                'perimeterBcryptCost':12,'authenticatedRequestSeconds':authenticated_seconds,
                'publicTlsOrProviderQualified':False,'providerCalls':0,'realDataUsed':False,'resources':samples}))
        finally:
            for identifier in reversed(containers):
                if identifier:subprocess.run(['docker','rm','-f',identifier],capture_output=True)
            if volume:subprocess.run(['docker','volume','rm',volume],capture_output=True)
            if network:subprocess.run(['docker','network','rm',network],capture_output=True)

if __name__=='__main__':main()
