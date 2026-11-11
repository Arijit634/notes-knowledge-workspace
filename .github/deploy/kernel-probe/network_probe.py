"""Fixed public build-endpoint diagnostics. Never reads secrets or changes DNS."""
import json,os,pathlib,subprocess
IMAGE='ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852'
def call(args):
 try:return subprocess.run(args,capture_output=True,timeout=20).returncode==0
 except subprocess.TimeoutExpired:return False
subprocess.run(['docker','pull',IMAGE],check=True,stdout=subprocess.DEVNULL)
rows=[]
for host in ['archive.ubuntu.com','security.ubuntu.com','ports.ubuntu.com']:
 row=dict(endpoint=host,hostDns=call(['getent','hosts',host]))
 for mode in ['bridge','host']:
  row[mode+'Dns']=call(['docker','run','--rm','--network',mode,'--read-only','--user','65533:65533','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','96m','--entrypoint','getent',IMAGE,'hosts',host])
 row['hostVerifiedHttps']=call(['curl','--fail','--silent','--show-error','--head','--max-time','15','--proto','=https','--tlsv1.2','https://'+host+'/'])
 rows.append(row)
receipt=dict(source=os.environ['SOURCE_SHA'],platform='GitHub standard Ubuntu 24.04',endpointChecks=rows,configurationChanged=False,privateEndpointOrCredentialUsed=False)
pathlib.Path(os.environ['RUNNER_TEMP'],'build-network-diagnostic.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt))
