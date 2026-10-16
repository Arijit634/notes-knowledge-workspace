#!/usr/bin/env python3
"""Join exact-image inventories, scans and SBOMs; never infer safety from totals."""
import hashlib
import json
import pathlib
import sys


def main(before, after, directory):
    root = pathlib.Path(directory)
    records = []
    packages = []
    for name, image, inventory, base in [
        ('before', before, 'media-before-packages.txt', 'debian:bookworm-slim@sha256:7c7b2c966bc9ee8cedfeef67e0e279108992c77681fa595db4a9d65c06ccc587'),
        ('after', after, 'media-deployment-packages.txt', 'debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f')]:
        source = root / ('media-before-security' if name == 'before' else 'media-security-readiness')
        scan = json.loads((source / 'security-readiness.json').read_text())
        sbom = json.loads((source / 'sbom-receipt.json').read_text())
        assert scan['image'] == sbom['image'] == image
        assert sbom['sha256'] == hashlib.sha256((source / 'deployment.cdx.json').read_bytes()).hexdigest()
        assert scan['secretFindingCount'] == 0
        installed = dict(line.split('\t') for line in (root / inventory).read_text().splitlines())
        packages.append(installed)
        records.append(dict(name=name, image=image, base=base, packages=installed,
            vulnerabilities=scan['vulnerabilityCounts'], distinctAdvisories=sorted(set(v['VulnerabilityID'] for v in scan['vulnerabilities'])),
            findings=scan['vulnerabilities'], scanner=scan['scanner'], database=scan['database'],
            releaseGatePassed=scan['releaseGatePassed'], sbomSha256=sbom['sha256']))
    assert records[0]['scanner'] == records[1]['scanner']
    assert records[0]['database']['UpdatedAt'] == records[1]['database']['UpdatedAt']
    changes = [dict(package=key, before=packages[0].get(key), after=packages[1].get(key))
               for key in sorted(packages[0].keys() | packages[1].keys()) if packages[0].get(key) != packages[1].get(key)]
    receipt = dict(runtimeComparison=True, images=records, packageChanges=changes,
                   introducedAdvisories=sorted(set(records[1]['distinctAdvisories']) - set(records[0]['distinctAdvisories'])),
                   removedAdvisories=sorted(set(records[0]['distinctAdvisories']) - set(records[1]['distinctAdvisories'])),
                   sameApplicationJar=True, applicationSha256=hashlib.sha256((root / 'media-context/application.jar').read_bytes()).hexdigest(),
                   priorPackagingSource='ffa878faeb6302f06e0a1fdde7619776af77a27c',
                   smallerCountIsNotSafetyProof=True, findingsSuppressed=False, productionReleaseAuthorized=False)
    (root / 'media-security-readiness/runtime-comparison.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(dict(runtimeComparison=True, before=records[0]['vulnerabilities'], after=records[1]['vulnerabilities'],
                          packageChanges=len(changes), productionReleaseAuthorized=False)))


if __name__ == '__main__':
    main(*sys.argv[1:])
