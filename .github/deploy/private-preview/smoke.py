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
import time
import uuid

ROOT = Path(__file__).resolve().parent
JDK = 'eclipse-temurin:25.0.5_8-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a'


def run(*args, capture=False, timeout=360):
    return subprocess.run(args, check=True, capture_output=capture, text=True, timeout=timeout).stdout


def main():
    if os.name != 'posix' or len(sys.argv) not in (2, 3):
        raise ValueError('native_linux_and_candidate_image_required')
    bundled = len(sys.argv) == 3 and sys.argv[2] == '--small-bundle'
    small_host = len(sys.argv) == 3
    if small_host and sys.argv[2] not in ('--small-host', '--small-bundle'):
        raise ValueError('unknown_qualification_mode')
    image = sys.argv[1]
    # Only a locally built immutable image ID; never pull an operator-controlled tag here.
    if not image.startswith('sha256:') or len(image) != 71:
        raise ValueError('immutable_local_image_required')
    spec=importlib.util.spec_from_file_location('preview_initialize', ROOT/'initialize.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    project='nkw-preview-test-'+uuid.uuid4().hex[:10]
    slice_unit='nkwqualification'+uuid.uuid4().hex[:12]+'.slice'
    anchor_unit=project+'-anchor.service'
    slice_path=None
    with tempfile.TemporaryDirectory(prefix='nkw-preview-test-') as temp:
        temp=Path(temp);os.chmod(temp,0o755)
        shutil.copyfile(ROOT/'compose.yaml',temp/'compose.yaml')
        # Disable all external egress and publish no port, including ACME/TLS.
        offline='networks:\n  edge:\n    internal: true\n'
        if small_host:
            # Qualification override only: deployment defaults remain unchanged.
            # Swap cannot conceal a memory-budget failure. The external DB and
            # Redis are accounted separately; this is NOT hosted-platform proof.
            offline += '''services:
  app:
    mem_limit: 448m
    memswap_limit: 448m
    cpus: 0.1
    entrypoint: [java, -Xmx160m, -XX:MaxDirectMemorySize=16m, -XX:MaxMetaspaceSize=128m, -XX:ReservedCodeCacheSize=32m, -XX:TieredStopAtLevel=1, -XX:+UseSerialGC, -Xss512k, -XX:ActiveProcessorCount=1, -XX:-HeapDumpOnOutOfMemoryError, -XX:+ExitOnOutOfMemoryError, -Duser.timezone=UTC, -Djava.io.tmpdir=/tmp, -jar, /app/application.jar]
    healthcheck:
      disable: true
'''
        if bundled:
            # One aggregate cgroup, including proxy and rate-control Redis. This
            # measures the combined budget, not single-container packaging or a
            # hosted provider. PostgreSQL and operator probes remain outside it.
            offline += f'''    cgroup_parent: {slice_unit}
  redis:
    cgroup_parent: {slice_unit}
    mem_limit: 64m
    memswap_limit: 64m
  edge:
    cgroup_parent: {slice_unit}
    ports: !reset []
    mem_limit: 64m
    memswap_limit: 64m
'''
            (temp/'Caddyfile').write_text('''{
    admin off
    auto_https off
    log default {
        level ERROR
    }
}
http://:8088 {
    reverse_proxy app:8080 {
        header_up -Forwarded
        header_up X-Forwarded-For {remote_host}
        header_up X-Forwarded-Proto https
        header_up X-Forwarded-Host preview.example.test
        header_up Host preview.example.test
    }
}
''')
        (temp/'offline.yaml').write_text(offline)
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
            if bundled:
                run('sudo','systemd-run','--quiet','--unit',anchor_unit,'--slice',slice_unit,
                    '--property=RuntimeMaxSec=1800','/usr/bin/sleep','1800')
                run('sudo','systemctl','set-property','--runtime',slice_unit,
                    'MemoryMax=469762048','MemorySwapMax=0','CPUQuota=10%','TasksMax=512')
                group=run('systemctl','show','--property=ControlGroup','--value',slice_unit,capture=True).strip()
                if group!='/'+slice_unit:raise ValueError('unexpected_qualification_cgroup')
                slice_path=Path('/sys/fs/cgroup')/slice_unit
            run(*compose,'up','-d','--wait','--wait-timeout','120','postgres','redis')
            run(*compose,'run','--rm','migrate')
            if bundled:
                run(*compose,'run','--rm','--no-deps','edge','caddy','validate',
                    '--config','/etc/caddy/Caddyfile','--adapter','caddyfile')
                run(*compose,'up','-d','edge')
            started=time.monotonic()
            if small_host:run(*compose,'up','-d','app')
            else:run(*compose,'up','-d','--wait','--wait-timeout','180','app')
            resources=[]
            def measure_resources():
                if not small_host:return
                identifier=run(*compose,'ps','-q','app',capture=True).strip()
                metadata=json.loads(run('docker','inspect',identifier,capture=True))[0]
                state=metadata['State'];config=metadata['HostConfig']
                events=dict(line.split() for line in run(*compose,'exec','-T','app','cat','/sys/fs/cgroup/memory.events',capture=True).splitlines())
                if (state['OOMKilled'] or state['ExitCode']!=0 or metadata['RestartCount']!=0
                        or int(events.get('oom','0')) or int(events.get('oom_kill','0'))):
                    raise ValueError('preview_resource_failure')
                sample={'oomKilled':state['OOMKilled'],'restartCount':metadata['RestartCount'],
                    'cgroupOomEvents':int(events.get('oom','0')),'cgroupOomKills':int(events.get('oom_kill','0')),
                    'memoryLimitBytes':config['Memory'],'swapTotalLimitBytes':config['MemorySwap'],
                    'cpuQuota':config['NanoCpus'],
                    'cgroupMemoryPeakBytes':int(run(*compose,'exec','-T','app','cat','/sys/fs/cgroup/memory.peak',capture=True).strip())}
                if small_host and (sample['memoryLimitBytes']!=448*1024*1024
                        or sample['swapTotalLimitBytes']!=448*1024*1024 or sample['cpuQuota']!=100_000_000):
                    raise ValueError('small_host_limit_not_enforced')
                if bundled:
                    events=dict(line.split() for line in (slice_path/'memory.events').read_text().splitlines())
                    quota,period=map(int,(slice_path/'cpu.max').read_text().split())
                    peak=int((slice_path/'memory.peak').read_text())
                    if (int((slice_path/'memory.max').read_text())!=448*1024*1024
                            or (slice_path/'memory.swap.max').read_text().strip()!='0'
                            or quota*10!=period or int(events['oom']) or int(events['oom_kill'])
                            or peak>448*1024*1024):raise ValueError('combined_resource_budget_failed')
                    sample['combinedPeakBytes']=peak
                    sample['combinedMemoryLimitBytes']=448*1024*1024
                    sample['combinedCpuQuota']=quota/period
                resources.append(sample)
            probe=str(ROOT/'Probe.java')
            state=temp/'probe-state';state.mkdir(mode=0o700)
            def probe_request(mode,address='10.89.10.2'):
                run('docker','run','--rm','--network',project+'_edge','--ip',address,
                    '--user',f'{os.getuid()}:{os.getgid()}',
                    '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
                    '--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=32m',
                    '-v',probe+':/probe/Probe.java:ro','-v',str(state)+':/state',JDK,'java','/probe/Probe.java',
                    mode,*(['/state/session'] if mode.startswith('session-') else []))
            def await_private_readiness():
                if not small_host:return
                identifier=run(*compose,'ps','-q','app',capture=True).strip()
                run('docker','run','--rm','--network','container:'+identifier,
                    '--user',f'{os.getuid()}:{os.getgid()}',
                    '--read-only','--cap-drop','ALL','--security-opt','no-new-privileges',
                    '--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=32m',
                    '-v',probe+':/probe/Probe.java:ro',JDK,'java','/probe/Probe.java',
                    'await-private-readiness',timeout=660)
            await_private_readiness()
            first_boot_seconds=round(time.monotonic()-started,2)
            measure_resources()
            probe_request('trusted')
            if bundled:probe_request('edge','10.89.10.5')
            probe_request('untrusted','10.89.10.4')
            probe_request('session-create')
            # Recreate both containers, retaining ONLY this unique project's volumes.
            measure_resources()
            run(*compose,'stop','app','postgres')
            run(*compose,'rm','-f','app','postgres')
            run(*compose,'up','-d','--wait','--wait-timeout','120','postgres')
            started=time.monotonic()
            if small_host:run(*compose,'up','-d','app')
            else:run(*compose,'up','-d','--wait','--wait-timeout','180','app')
            await_private_readiness()
            restart_boot_seconds=round(time.monotonic()-started,2)
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
            measure_resources()
            print(json.dumps({'nativeArchitecture':run('uname','-m',capture=True).strip(),
                'image':image,'migrations':20,'coreReadiness':True,'restartReadiness':True,
                'databaseAndContainerRecreationPersistSession':True,'isolatedLogicalRestoreQuarantine':True,
                'cookieAndCsrfDenials':True,'untrustedForwardingRejected':True,
                'providerCalls':0,'realDataUsed':False,'hostedTlsQualified':False,
                'smallHostQualification':small_host,'firstBootSeconds':first_boot_seconds,
                'combinedProxyAppRedisBudgetQualified':bundled,
                'reservedHostHeadroomBytes':64*1024*1024 if small_host else 0,
                'privateReadinessProbeOutsideAppResourceLimit':small_host,
                'restartBootSeconds':restart_boot_seconds,'resourceSamples':resources,
                'scope':('combined app/proxy/Redis budget only; external DB, single-container packaging and hosted platform not qualified'
                    if bundled else 'application container only; external DB/Redis and hosted platform not qualified')}))
        finally:
            # Exact unique test project only. No global prune or developer volume deletion.
            run(*compose,'down','--volumes','--remove-orphans')
            if bundled:
                run('sudo','systemctl','stop',anchor_unit,slice_unit)


if __name__=='__main__':main()
