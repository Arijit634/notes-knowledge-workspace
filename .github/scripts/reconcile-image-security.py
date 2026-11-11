#!/usr/bin/env python3
"""Reconcile an exact image scan with reviewed, expiring applicability evidence.

The original scanner report is never rewritten. Unrecognized findings, changed
code/packages, absent controls and expired reviews fail closed. This is not a
severity filter or an ignore-unfixed mechanism.
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import re


def require(value, code):
    if not value:
        raise ValueError(code)


def identity(row):
    return (row['VulnerabilityID'],row['PkgName'],row['InstalledVersion'],row['Severity'])


def controls(facts, smoke):
    events={row['step']:row for row in smoke['events']}
    return {
        'numericUnprivilegedImage':facts['imageUser']=='65533:65533',
        'setuidSetgidAbsent':facts['setuidSetgidFilesAbsent'] is True,
        'trustedFontConfiguration':facts['fontConfigurationRootOwnedAndNotWritable'] is True,
        'boundedFontConfiguration':0<facts.get('fontConfigurationLargestBytes',0)<=65536
            and 0<facts.get('fontConfigurationTotalBytes',0)<=1048576,
        'ffmpegIconvDisabled':facts.get('ffmpegIconvDisabled') is True,
        'resolverSearchDomainsBounded':facts.get('resolverSearchDomainsBounded') is True,
        'opensslNotLinkedInMedia':facts.get('ffmpegOpenSslDisabled') is True
            and facts.get('nativeLinkageMentionsOpenSsl') is False,
        'opensslNotLoadedInWorkload':bool(smoke['resourceMeasurements']) and all(
            row['javaProcessesInspected']>0 and row.get('opensslLoaded') is False for row in smoke['resourceMeasurements']),
        'restrictedLocaleInputs':facts['defaultLocaleInputsRestricted'] is True,
        'alignedAllocationOverflowRejected':facts.get('alignedAllocationCheck')==dict(alignedAllocationOverflowRejected=True,cases=30),
        'freetype215Verified':facts.get('mediaLibraryAbi',{}).get('abiProbePassed') is True
            and facts['mediaLibraryAbi']['freetype']=='2.15.0',
        'expat210BackportVerified':facts.get('mediaLibraryAbi',{}).get('abiProbePassed') is True
            and facts['mediaLibraryAbi']['expat']=='2.10.0'
            and facts.get('expatRegressionApiCompatibilitySha256')=='6f71b7c60c0fedd8de8840ebaadd17fd3401b5b33a501655de8d2c7538b74418'
            and facts.get('expatBackportPatchSha256')=={
                'bf26290d2f021111cf806753dcb87d165f53571b':'56b71deecec296aba42cd80ebde30215527aec9fa5c3ea183d0a056f2bb1520e',
                '3d77567612994172f9d09b46f31bb604e16fb08d':'42caed50fae3f88186155b0c4280025a89913a83aed2a02986b545e0a8d1e7ed',
                '05075975dc246f5f6c0757d532f22f518850e7af':'89d6e66a1c346ffcb57abfaf5fc6b251b351d6dcc778270e09ea513ab9e4eb89'},
        'sqliteNotLinkedOrPackaged':facts['nativeLinkageMentionsSqlite'] is False
            and facts['sqliteApplicationDependencyPresent'] is False,
        'sqliteNotLoadedInWorkload':bool(smoke['resourceMeasurements']) and all(
            row['javaProcessesInspected']>0 and row['sqliteLoaded'] is False for row in smoke['resourceMeasurements']),
        'nativeStartupQualified':events.get('production_startup',{}).get('nativePreflight') is True,
        'ownerAndCsrfQualified':events.get('authorized_storage',{}).get('crossOwnerDenied') is True
            and events.get('authorized_storage',{}).get('csrfRequired') is True,
        'publicDenialQualified':events.get('public_generation_denial',{}).get('anonymousDenied') is True,
        **{name+'Absent':value['absent'] is True for name,value in facts['components'].items()},
    }


def reconcile(scan,facts,smoke,policy,today=None):
    now=(datetime.datetime.combine(today,datetime.time(12),datetime.timezone.utc) if today is not None
        else datetime.datetime.now(datetime.timezone.utc))
    today=now.date()
    image=scan['image']
    require(re.fullmatch(r'sha256:[0-9a-f]{64}',image),'exact_image_required')
    require(facts['deploymentImage']==image==smoke['deploymentImage'],'evidence_image_mismatch')
    require(scan['scanExit'] in (0,1) and scan['javaPackagesAnalyzed']>=200
        and scan['osPackagesAnalyzed']>0,'scan_coverage_missing')
    updated=datetime.datetime.fromisoformat(scan['database']['UpdatedAt'].replace('Z','+00:00'))
    downloaded=datetime.datetime.fromisoformat(scan['database']['DownloadedAt'].replace('Z','+00:00'))
    require(updated.tzinfo is not None and downloaded.tzinfo is not None
        and -300<=(now-updated).total_seconds()<=172800
        and -300<=(now-downloaded).total_seconds()<=129600,'fresh_vulnerability_database_required')
    require(scan['secretFindingCount']==0 and not scan['secretFindings'],'secret_gate_failed')
    require(not scan['misconfigurations'],'configuration_gate_failed')
    require(scan['javaVulnerabilityCount']==0,'application_dependency_gate_failed')
    require(smoke['passed'] is True and smoke['productionJar'] is True
        and not smoke.get('feasibilityOnly',False),'full_runtime_evidence_missing')
    require(policy['schemaVersion']==1 and policy['purpose']=='exact-native-runtime-applicability',
        'unsupported_disposition_policy')
    require(policy['productionSourceSha256']==facts['productionSourceFingerprint']['sha256'],
        'reviewed_production_source_changed')
    require(facts['architecture'] in policy['architectures'],'unreviewed_architecture')
    active=controls(facts,smoke)
    findings=scan['vulnerabilities']
    require(scan['scanExit']==0 or findings,'scanner_failed_without_findings')
    require(sum(scan['vulnerabilityCounts'].values())==len(findings),'scan_count_mismatch')
    rules={}
    for row in policy['findings']:
        key=identity(row)
        require(key not in rules,'duplicate_disposition')
        reviewed=datetime.date.fromisoformat(row['reviewedOn'])
        expires=datetime.date.fromisoformat(row['expiresOn'])
        require(reviewed<=today<=expires and 0<(expires-reviewed).days<=7,'disposition_expired_or_unreviewed')
        require(row['kind'] in ('upstream-fixed','component-not-shipped','unreachable-precondition','bounded-nonblocker'),
            'unsupported_disposition')
        require(all(isinstance(row.get(field),str) and len(row[field])>=12
            for field in ('rationale','compensatingControls','owner','remediationTarget')),'incomplete_disposition')
        require(row['references'] and all(re.fullmatch(r'https://(?:security-tracker\.debian\.org|github\.com|freetype\.org|openssl-library\.org)/[^\s]+',url)
            for url in row['references']),'primary_evidence_required')
        require(row['requiredControls'] and all(active.get(name) is True for name in row['requiredControls']),
            'required_control_missing')
        rules[key]=row
    dispositions=[];unknown=[]
    for finding in findings:
        key=identity(finding)
        version=facts['packages'].get(finding['PkgName'],{}).get('version')
        require(version==finding['InstalledVersion'],'package_evidence_mismatch')
        if key not in rules:
            unknown.append(dict(zip(('finding','package','version','severity'),key)))
        else:
            row=rules[key]
            dispositions.append(dict(finding=key[0],package=key[1],version=key[2],severity=key[3],
                kind=row['kind'],owner=row['owner'],reviewedOn=row['reviewedOn'],expiresOn=row['expiresOn']))
    # A stale row is also a changed-artifact signal, not an automatic renewed review.
    require(set(rules).issubset({identity(row) for row in findings}),'reviewed_finding_set_changed')
    return dict(schemaVersion=1,image=image,source=facts['source'],architecture=facts['architecture'],
        rawScannerExit=scan['scanExit'],rawVulnerabilityCounts=scan['vulnerabilityCounts'],
        findingsRetained=len(findings),dispositions=dispositions,unreviewedFindings=unknown,
        secrets=0,misconfigurations=0,applicationVulnerabilities=0,
        productionSourceSha256=policy['productionSourceSha256'],
        releaseSecurityReconciled=not unknown,scannerFindingsSuppressed=False,
        publicLaunchApproved=False,liveProviderQualified=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('scan','facts','smoke','policy','output'):parser.add_argument(name,type=pathlib.Path)
    args=parser.parse_args()
    require(not args.output.exists(),'existing_evidence_must_be_preserved')
    paths=(args.scan,args.facts,args.smoke,args.policy)
    payloads=[path.read_bytes() for path in paths]
    result=reconcile(*(json.loads(raw) for raw in payloads))
    result['inputSha256']={name:hashlib.sha256(raw).hexdigest()
        for name,raw in zip(('scan','facts','smoke','policy'),payloads)}
    with args.output.open('x') as output:output.write(json.dumps(result,indent=2)+'\n')
    print(json.dumps(dict(image=result['image'],reviewedFindings=len(result['dispositions']),
        unreviewedFindings=len(result['unreviewedFindings']),releaseSecurityReconciled=result['releaseSecurityReconciled'])))
    require(result['releaseSecurityReconciled'],'unreviewed_security_findings')


if __name__=='__main__':main()
