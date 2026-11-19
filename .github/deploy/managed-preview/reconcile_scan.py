"""Retain raw HIGH/CRITICAL findings; accept only exact reviewed vendor fixes.

This supplemental profile has no general risk waiver. The full native/image
release still has its separate source-bound qualification and security gates.
"""
import argparse,datetime,hashlib,importlib.util,json,pathlib,re,subprocess

ROOT=pathlib.Path(__file__).resolve().parents[3]
CVES={'CVE-2026-113425','CVE-2026-123007','CVE-2026-123633','CVE-2026-123634'}
VERSION='1.24.0-2ubuntu7.20'
BOUNDARIES=('rootlessSingleContainer','privateListenersLoopbackOnly','perimeterAndCsrfDenials',
    'crossOwnerDenial','callbackLogPrivacy','sessionAndCsrfSurviveAppAndPgRecreation',
    'isolatedLogicalRestoreQuarantine','controlledOidcSignatureNoncePkce')

def require(value,code):
    if not value:raise ValueError(code)

def identity(row):
    return tuple(row[k] for k in ('VulnerabilityID','PkgName','InstalledVersion','Severity'))

def reconcile(raw,exit_status,database,facts,smoke,policy,now=None):
    now=now or datetime.datetime.now(datetime.timezone.utc)
    require(exit_status in (0,1),'scanner_execution_failed')
    image=facts['image'];require(re.fullmatch(r'sha256:[0-9a-f]{64}',image),'exact_image_required')
    require(raw.get('ArtifactName')==smoke.get('image')==image,'scan_image_mismatch')
    require(raw.get('Metadata',{}).get('OS')==dict(Family='ubuntu',Name='24.04'),'scan_os_mismatch')
    require(re.fullmatch('[0-9a-f]{40}',facts['source']) and facts['architecture']=='amd64'
        and facts['user']=='65533:65533','image_source_or_identity_mismatch')
    require(policy['schemaVersion']==1 and policy['purpose']=='exact-managed-preview-vendor-fixes'
        and policy['productionSourceSha256']==facts['productionSourceSha256'],'reviewed_source_mismatch')
    for key,limit in [('UpdatedAt',172800),('DownloadedAt',129600)]:
        stamp=datetime.datetime.fromisoformat(database[key].replace('Z','+00:00'))
        require(stamp.tzinfo is not None and -300<=(now-stamp).total_seconds()<=limit,'fresh_scanner_database_required')
    require(all(smoke.get(name) is True for name in BOUNDARIES)
        and smoke.get('liveProviderCalls')==0 and smoke.get('realDataUsed') is False,'runtime_boundary_evidence_missing')
    require(smoke.get('resources') and all(r['peakBytes']<=r['memoryLimitBytes']==536870912
        and r['oomEvents']==r['restarts']==0 for r in smoke['resources']),'runtime_memory_evidence_missing')
    require(all(facts['packages'].get(name)==dict(version=VERSION,source='nginx')
        for name in ('nginx','nginx-common')),'exact_vendor_packages_required')
    findings=[];java=0;os_packages=0
    for result in raw.get('Results',[]):
        if result.get('Type')=='jar':java+=len(result.get('Packages',[]))
        if result.get('Class')=='os-pkgs':os_packages+=len(result.get('Packages',[]))
        findings.extend(result.get('Vulnerabilities',[]))
        require(not result.get('Secrets') and not result.get('Misconfigurations'),'unexpected_security_finding')
    require(java>=200 and os_packages>0,'scanner_coverage_missing')
    require(bool(findings)==bool(exit_status),'scanner_result_inconsistent')
    rules={}
    for row in policy['findings']:
        key=identity(row)
        require(key not in rules and row['kind']=='upstream-fixed' and key[0] in CVES
            and key[1] in ('nginx','nginx-common') and key[2]==VERSION and key[3]=='HIGH','unapproved_vendor_fix')
        reviewed=datetime.date.fromisoformat(row['reviewedOn']);expires=datetime.date.fromisoformat(row['expiresOn'])
        require(reviewed<=now.date()<=expires and 0<(expires-reviewed).days<=7,'vendor_fix_review_expired')
        require(row['references']==['https://ubuntu.com/security/'+key[0]]
            and re.fullmatch('[0-9a-f]{64}',row['vendorEvidenceSha256'])
            and all(isinstance(row.get(k),str) and len(row[k])>=12
                for k in ('rationale','compensatingControls','owner','remediationTarget')),'vendor_fix_evidence_missing')
        rules[key]=row
    require(set(rules)=={(c,p,VERSION,'HIGH') for c in CVES for p in ('nginx','nginx-common')},'vendor_fix_set_incomplete')
    require(len({identity(r) for r in findings})==len(findings),'duplicate_scanner_finding')
    require(all(identity(r) in rules for r in findings),'unreviewed_security_finding')
    return dict(source=facts['source'],image=image,productionSourceSha256=facts['productionSourceSha256'],
        rawScannerExit=exit_status,rawHighCriticalFindings=len(findings),findingsRetained=[dict(zip(
            ('VulnerabilityID','PkgName','InstalledVersion','Severity'),identity(r))) for r in findings],
        exactVendorPackagesVerified=True,runtimeBoundariesVerified=True,scannerFindingsSuppressed=False,
        securityReconciled=True,fullApplicationReleaseQualified=False,reviewExpiry=min(r['expiresOn'] for r in rules.values()))

def command(*args):
    result=subprocess.run(args,capture_output=True,timeout=90)
    require(result.returncode==0,'image_evidence_command_failed')
    return result.stdout

def main(args):
    require(re.fullmatch(r'sha256:[0-9a-f]{64}',args.image) and re.fullmatch('[0-9a-f]{40}',args.source),'input_identity_invalid')
    metadata=json.loads(command('docker','image','inspect',args.image))[0]
    require(metadata['Config']['Labels']['org.opencontainers.image.revision']==args.source,'image_revision_mismatch')
    inventory=command('docker','run','--rm','--network','none','--read-only','--cap-drop','ALL',
        '--security-opt','no-new-privileges','--entrypoint','dpkg-query',args.image,'-W',
        '-f=${Package}\t${Version}\t${source:Package}\n','nginx','nginx-common').decode()
    packages={}
    for line in inventory.splitlines():
        name,version,source=line.split('\t');require(name not in packages,'duplicate_package_identity')
        packages[name]=dict(version=version,source=source)
    spec=importlib.util.spec_from_file_location('native_facts',ROOT/'.github/scripts/native-image-facts.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    facts=dict(image=args.image,source=args.source,architecture=metadata['Architecture'],user=metadata['Config']['User'],
        packages=packages,productionSourceSha256=module.production_fingerprint()['sha256'])
    folder=pathlib.Path(args.folder);policy=pathlib.Path(args.policy)
    read=lambda p:json.loads(p.read_text())
    result=reconcile(read(folder/'raw-scan.json'),int((folder/'scanner-exit.txt').read_text()),
        read(folder/'db-metadata.json'),facts,read(folder/'smoke.json'),read(policy))
    (folder/'image-facts.json').write_text(json.dumps(facts,indent=2)+'\n')
    result['inputSha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest()
        for p in [folder/'raw-scan.json',folder/'scanner-exit.txt',folder/'db-metadata.json',folder/'smoke.json',folder/'image-facts.json',policy]}
    (folder/'security-reconciliation.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(dict(source=args.source,rawFindingsRetained=result['rawHighCriticalFindings'],securityReconciled=True)))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--image',required=True);parser.add_argument('--source',required=True)
    parser.add_argument('--folder',required=True);parser.add_argument('--policy',required=True);main(parser.parse_args())
