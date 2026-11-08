#!/usr/bin/env python3
"""Isolated synthetic native-container smoke; no provider dispatch or cloud mutation."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parent
JDK = 'eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a'


def run(*args, capture=False):
    return subprocess.run(args, check=True, capture_output=capture, text=True, timeout=360).stdout


def main():
    if os.name != 'posix' or len(sys.argv) != 2:
        raise ValueError('native_linux_and_candidate_image_required')
    image = sys.argv[1]
    # Only a locally built immutable image ID; never pull an operator-controlled tag here.
    if not image.startswith('sha256:') or len(image) != 71:
        raise ValueError('immutable_local_image_required')
    spec=importlib.util.spec_from_file_location('preview_initialize', ROOT/'initialize.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    project='nkw-preview-test-'+uuid.uuid4().hex[:10]
    with tempfile.TemporaryDirectory(prefix='nkw-preview-test-') as temp:
        temp=Path(temp);os.chmod(temp,0o755)
        shutil.copyfile(ROOT/'compose.yaml',temp/'compose.yaml')
        # Disable all external egress and publish no port, including ACME/TLS.
        (temp/'offline.yaml').write_text('networks:\n  edge:\n    internal: true\n')
        app,owner,runtime,redis=module.configuration('https://preview.example.test','syntheticnamespace',
            'ap-mumbai-1','synthetic-private-bucket','synthetic.apps.googleusercontent.com',
            'synthetic-client-secret','synthetic-access-key','synthetic-storage-secret')
        secret=temp/'secrets';secret.mkdir();(secret/'app').mkdir()
        for name,value in app.items():(secret/'app'/name).write_text(value)
        (secret/'db-owner').write_text(owner)
        (secret/'redis.conf').write_text(f'bind 0.0.0.0\nprotected-mode yes\nrequirepass "{redis}"\nmaxmemory 64mb\nmaxmemory-policy noeviction\n')
        (secret/'migration.properties').write_text(f'url=jdbc:postgresql://postgres:5432/nkw_preview\nuser=nkw_preview_migrator\npassword={owner}\nruntimePassword={runtime}\n')
        # Synthetic fixtures only. Production initialize.py uses owner-only files.
        (temp/'runtime.env').write_text(f'APP_IMAGE={image}\nSOURCE_SHA=synthetic-container-smoke\nPREVIEW_HOST=preview.example.test\n')
        compose=['docker','compose','--project-directory',str(temp),'-p',project,'--env-file',str(temp/'runtime.env'),'-f',str(temp/'compose.yaml'),'-f',str(temp/'offline.yaml')]
        try:
            run(*compose,'up','-d','--wait','--wait-timeout','120','postgres','redis')
            run(*compose,'run','--rm','migrate')
            run(*compose,'up','-d','--wait','--wait-timeout','180','app')
            probe=str(ROOT/'Probe.java')
            state=temp/'probe-state';state.mkdir(mode=0o700)
            def probe_request(mode,address='10.89.10.2'):
                run('docker','run','--rm','--network',project+'_edge','--ip',address,
                    '--user',f'{os.getuid()}:{os.getgid()}',
                    '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
                    '--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=32m',
                    '-v',probe+':/probe/Probe.java:ro','-v',str(state)+':/state',JDK,'java','/probe/Probe.java',
                    mode,*(['/state/session'] if mode.startswith('session-') else []))
            probe_request('trusted')
            probe_request('untrusted','10.89.10.4')
            probe_request('session-create')
            # Recreate both containers, retaining ONLY this unique project's volumes.
            run(*compose,'stop','app','postgres')
            run(*compose,'rm','-f','app','postgres')
            run(*compose,'up','-d','--wait','--wait-timeout','120','postgres')
            run(*compose,'up','-d','--wait','--wait-timeout','180','app')
            probe_request('session-check')
            pg=compose+['exec','-T','postgres']
            sql=lambda database,query: run(*pg,'psql','-X','-U','nkw_preview_migrator','-d',database,
                '-v','ON_ERROR_STOP=1','-At','-c',query,capture=True).strip()
            assert int(sql('nkw_preview','select count(*) from identity.spring_session'))>0
            run(*pg,'pg_dump','-U','nkw_preview_migrator','-d','nkw_preview','-Fc','-f','/tmp/preview-drill.dump')
            sql('nkw_preview','create database nkw_preview_restore')
            sql('nkw_preview','revoke connect on database nkw_preview_restore from public; revoke connect on database nkw_preview_restore from nkw_preview_runtime')
            run(*pg,'pg_restore','-U','nkw_preview_migrator','--no-owner','--no-acl','-d','nkw_preview_restore','/tmp/preview-drill.dump')
            assert int(sql('nkw_preview_restore','select count(*) from identity.spring_session'))>0
            container=run(*compose,'ps','-q','postgres',capture=True).strip()
            quarantine=ROOT.parents[2]/'src/main/resources/operations/restore-security-quarantine.sql'
            run('docker','cp',str(quarantine),container+':/tmp/quarantine.sql')
            run(*pg,'psql','-X','-U','nkw_preview_migrator','-d','nkw_preview_restore','-v','ON_ERROR_STOP=1',
                '--set=runtime_role=nkw_preview_runtime','--set=restore_confirmation=invalidate_uncertain_authority',
                '-f','/tmp/quarantine.sql')
            assert sql('nkw_preview_restore','select count(*) from identity.spring_session')=='0'
            assert sql('nkw_preview_restore',"select has_database_privilege('nkw_preview_runtime',current_database(),'CONNECT')")=='f'
            assert sql('nkw_preview_restore','select count(*) from public.flyway_schema_history where success')=='20'
            probe_request('session-check')
            probe_request('session-finalize')
            print(json.dumps({'nativeArchitecture':run('uname','-m',capture=True).strip(),
                'image':image,'migrations':20,'coreReadiness':True,'restartReadiness':True,
                'databaseAndContainerRecreationPersistSession':True,'isolatedLogicalRestoreQuarantine':True,
                'cookieAndCsrfDenials':True,'untrustedForwardingRejected':True,
                'providerCalls':0,'realDataUsed':False,'hostedTlsQualified':False}))
        finally:
            # Exact unique test project only. No global prune or developer volume deletion.
            run(*compose,'down','--volumes','--remove-orphans')


if __name__=='__main__':main()
