"""Actual Linux diagnostic and deterministic missing-mechanism controls."""
import json,pathlib,subprocess,sys,tempfile,time,uuid

def command(*args):return subprocess.check_output(args,text=True,stderr=subprocess.DEVNULL,timeout=30).strip()

image=sys.argv[1]
assert image.startswith('sha256:') and len(image)==71
assert command('docker','image','inspect','--format','{{.Config.User}}',image)=='65533:65533'
assert command('docker','image','inspect','--format','{{.Architecture}}',image)=='amd64'
results=[]
with tempfile.TemporaryDirectory(prefix='kernel-probe-') as temporary:
    for denied in (None,'landlock_create_ruleset','seccomp'):
        name='kernel-probe-'+uuid.uuid4().hex
        args=['docker','create','--name',name,'--network','none','--read-only','--cap-drop','ALL',
            '--security-opt','no-new-privileges','--memory','16m','--cpus','1','--pids-limit','8']
        if denied:
            policy=pathlib.Path(temporary)/(denied+'.json')
            policy.write_text(json.dumps(dict(defaultAction='SCMP_ACT_ALLOW',syscalls=[dict(names=[denied],action='SCMP_ACT_ERRNO',errnoRet=1)])))
            args+=['--security-opt','seccomp='+str(policy)]
        created=False
        try:
            command(*args,image);created=True
            command('docker','start',name)
            deadline=time.monotonic()+10;row=None
            while time.monotonic()<deadline:
                text=command('docker','logs',name)
                if text:
                    row=json.loads(text);break
                time.sleep(.1)
            assert row is not None and set(row)=={'kernelProbe','uidUnprivileged','capabilitiesEmpty','noNewPrivileges',
                'landlockAbiAtLeast3','landlockFilesystemEnforced','seccompEnforced','applicationDataAccessed','providerCalls'}
            assert all(row[key] is True for key in ('kernelProbe','uidUnprivileged','capabilitiesEmpty','noNewPrivileges'))
            assert row['applicationDataAccessed'] is False and row['providerCalls']==0
            assert row['landlockAbiAtLeast3']==row['landlockFilesystemEnforced']==(denied!='landlock_create_ruleset')
            assert row['seccompEnforced']==(denied!='seccomp')
            results.append(dict(deniedMechanism=denied,observed=row))
        finally:
            if created:command('docker','rm','--force',name)
print(json.dumps(dict(kernelDiagnosticTests=3,passed=True,results=results,liveResources=False)))
