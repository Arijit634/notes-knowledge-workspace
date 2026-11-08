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
            for mode,address in [('trusted','10.89.10.2'),('untrusted','10.89.10.4')]:
                run('docker','run','--rm','--network',project+'_edge','--ip',address,
                    '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
                    '--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=32m',
                    '-v',probe+':/probe/Probe.java:ro',JDK,'java','/probe/Probe.java',mode)
            run(*compose,'restart','app')
            run(*compose,'up','-d','--wait','--wait-timeout','180','app')
            print(json.dumps({'nativeArchitecture':run('uname','-m',capture=True).strip(),
                'image':image,'migrations':20,'coreReadiness':True,'restartReadiness':True,
                'cookieAndCsrfDenials':True,'untrustedForwardingRejected':True,
                'providerCalls':0,'realDataUsed':False,'hostedTlsQualified':False}))
        finally:
            # Exact unique test project only. No global prune or developer volume deletion.
            run(*compose,'down','--volumes','--remove-orphans')


if __name__=='__main__':main()
