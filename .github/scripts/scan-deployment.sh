#!/usr/bin/env bash
set -euo pipefail
test "$#" = 3
# Qualification evidence, not release promotion. Preserve native-test results
# independently from vulnerability findings; any finding still blocks release.
deployment="$1"
security="$2"
cache="$3"
test ! -e "$security"
mkdir -p "$security" "$cache"
scanner='ghcr.io/aquasecurity/trivy:0.75.0@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa'
database_flags=()
if test -f "$cache/db/metadata.json"; then database_flags+=(--skip-db-update); fi
if test -f "$cache/java-db/metadata.json"; then database_flags+=(--skip-java-db-update); fi
scan_result=0
docker run --rm --memory=2g --cpus=2 -v /var/run/docker.sock:/var/run/docker.sock:ro \
  -v "$security:/evidence" -v "$cache:/root/.cache/trivy" "$scanner" image \
  "${database_flags[@]}" --timeout 5m --scanners vuln,secret,misconfig --image-config-scanners misconfig,secret \
  --list-all-pkgs --exit-code 1 --format json --output /evidence/raw-scan.json "$deployment" || scan_result=$?
SCAN_RESULT="$scan_result" python3 - "$security" "$deployment" "$scanner" "$cache" <<'PY'
import collections,hashlib,json,os,pathlib,sys
root=pathlib.Path(sys.argv[1]); raw=root/'raw-scan.json'
assert raw.is_file(), 'Scanner infrastructure failed; missing evidence is not zero findings'
data=json.loads(raw.read_text()); vulnerabilities=[]; secrets=[]; misconfigurations=[]; java_packages=0; os_packages=0
assert int(os.environ['SCAN_RESULT']) in (0,1) and data.get('Metadata'), 'Scanner execution failed'
recognized_os=data['Metadata'].get('OS',{})
assert recognized_os.get('Family')=='debian' and str(recognized_os.get('Name','')).split('.')[0] in ('12','13'), 'Supported deployment OS not recognized'
for item in data.get('Results',[]):
    if item.get('Type')=='jar': java_packages+=len(item.get('Packages',[]))
    if item.get('Class')=='os-pkgs': os_packages+=len(item.get('Packages',[]))
    for v in item.get('Vulnerabilities',[]):
        vulnerabilities.append({k:v.get(k) for k in ('VulnerabilityID','PkgName','InstalledVersion','FixedVersion','Severity','Status','PrimaryURL')})
    for v in item.get('Secrets',[]):
        # Never publish Match, Code, source lines or secret-bearing metadata.
        secrets.append({k:v.get(k) for k in ('RuleID','Category','Severity')})
    for v in item.get('Misconfigurations',[]):
        misconfigurations.append({k:v.get(k) for k in ('ID','Severity','Status','PrimaryURL')})
assert java_packages>=200 and os_packages>0, 'Packaged Java/OS scan coverage missing; zero findings is not clearance'
db_path=pathlib.Path(sys.argv[4])/'db'/'metadata.json'
assert db_path.is_file(), 'Vulnerability database metadata missing'
database=json.loads(db_path.read_text())
receipt=dict(releaseSecurity=True,image=sys.argv[2],scanner=sys.argv[3],scanExit=int(os.environ['SCAN_RESULT']),
    database={k:database.get(k) for k in ('Version','UpdatedAt','NextUpdate','DownloadedAt')},
    javaPackagesAnalyzed=java_packages,osPackagesAnalyzed=os_packages,recognizedOs=recognized_os,
    javaVulnerabilityCount=sum(len(item.get('Vulnerabilities',[])) for item in data.get('Results',[]) if item.get('Type')=='jar'),
    vulnerabilityCounts=dict(collections.Counter(v['Severity'] for v in vulnerabilities)),vulnerabilities=vulnerabilities,
    secretFindingCount=len(secrets),secretFindings=secrets,misconfigurations=misconfigurations,
    releaseGatePassed=not(vulnerabilities or secrets or misconfigurations) and os.environ['SCAN_RESULT']=='0',
    registryPublished=False,codecLegalClearance=False)
assert receipt['scanExit']==0 or vulnerabilities or secrets or misconfigurations, 'Scanner failed without a complete findings report'
(root/'security-readiness.json').write_text(json.dumps(receipt,indent=2)+'\n')
raw.unlink() # Raw matches never leave this controlled ephemeral scan boundary.
print(json.dumps(receipt))
assert not secrets, 'Potential secret finding: STOP AND REVIEW; no match values were published'
PY
docker run --rm --memory=2g --cpus=2 -v /var/run/docker.sock:/var/run/docker.sock:ro \
  -v "$security:/evidence" -v "$cache:/root/.cache/trivy" "$scanner" image \
  "${database_flags[@]}" --timeout 5m --format cyclonedx --output /evidence/deployment.cdx.json "$deployment"
docker run --rm --network none --user 65533:65533 --cap-drop ALL --security-opt no-new-privileges --read-only \
  --entrypoint sha256sum "$deployment" /opt/video/tools/bin/ffmpeg /opt/video/tools/bin/ffprobe \
  /opt/pdf/helper /opt/video/helper /jdk/lib/modules /application.jar > "$security/shipped-artifacts.sha256"
python3 - "$security" "$deployment" <<'PY'
import hashlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); path=root/'deployment.cdx.json'; data=json.loads(path.read_text())
assert data.get('bomFormat')=='CycloneDX' and data.get('components')
assert sum(c.get('purl','').startswith('pkg:maven/') for c in data['components'])>=200, 'Java SBOM coverage missing'
# Package scanners cannot infer manually built artifacts from dpkg metadata.
# Add their exact shipped binary hashes without inventing vulnerability coverage.
shipped={line.split()[1]:line.split()[0] for line in (root/'shipped-artifacts.sha256').read_text().splitlines()}
explicit_refs=[]
for file,name,version in [('/opt/video/tools/bin/ffmpeg','ffmpeg','9.0.2'),
                          ('/opt/video/tools/bin/ffprobe','ffprobe','9.0.2'),
                          ('/opt/pdf/helper','pdf-sandbox-helper',None),
                          ('/opt/video/helper','video-sandbox-helper',None),
                          ('/jdk/lib/modules','microsoft-jdk-runtime-modules','25.0.4.1'),
                          ('/application.jar','notes-knowledge-application-jar',None)]:
    component=dict(type='file',name=name,**{'bom-ref':'urn:nkw:artifact:'+name+':'+shipped[file]},
                   hashes=[dict(alg='SHA-256',content=shipped[file])],
                   properties=[dict(name='nkw:shipped:path',value=file),
                               dict(name='nkw:inventory:origin',value='explicit-shipped-artifact'),
                               dict(name='nkw:scanner:coverage',value='not-claimed-for-manually-built-code')])
    if version: component['version']=version
    data['components'].append(component)
    explicit_refs.append(component['bom-ref'])
root_ref=data['metadata']['component']['bom-ref']
dependencies=data.setdefault('dependencies',[])
root_dependency=next((row for row in dependencies if row['ref']==root_ref),None)
if root_dependency is None:
    root_dependency=dict(ref=root_ref,dependsOn=[]);dependencies.append(root_dependency)
root_dependency.setdefault('dependsOn',[]).extend(explicit_refs)
assert len(shipped)==6 and len({c['bom-ref'] for c in data['components']})==len(data['components'])
data['version']=data.get('version',1)+1
data['metadata'].setdefault('properties',[]).append(dict(name='nkw:sbom:augmentation',
    value='Explicit shipped JAR/JRE/native binary hashes; not additional scanner vulnerability coverage'))
# Trivy's container owns the original file. The runner owns this dedicated
# directory: replace atomically rather than requiring writes to a root-owned
# payload or weakening its permissions.
augmented=root/'deployment-augmented.cdx.json'
with augmented.open('x') as output: output.write(json.dumps(data,indent=2)+'\n')
augmented.replace(path)
(root/'shipped-artifacts.sha256').unlink()
receipt=dict(releaseSbom=True,image=sys.argv[2],format='CycloneDX',specVersion=data['specVersion'],
    components=len(data['components']),explicitShippedArtifacts=6,manualNativeVulnerabilityCoverageClaimed=False,
    sha256=hashlib.sha256(path.read_bytes()).hexdigest(),registryPublished=False,signedAttestation=False)
(root/'sbom-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt))
PY
