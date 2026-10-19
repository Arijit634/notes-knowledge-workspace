#!/usr/bin/env python3
"""Fail-closed release/promotion checks. No registry, cloud or deployment mutations."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys

MAIN_CONTEXTS = {'MAIN / FAST', 'MAIN / Build sanity', 'MAIN / API', 'MAIN / SECURITY',
                 'MAIN / Flyway schema', 'MAIN / FRONTEND', 'MAIN / DATABASE',
                 'MAIN / Secret scan', 'MAIN / Modulith architecture',
                 'CodeQL / Java-Kotlin', 'CodeQL / JavaScript-TypeScript'}


def require(value, code):
    if not value:
        raise ValueError(code)


def command(*args):
    result = subprocess.run(args, check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=60)
    require(result.returncode == 0, 'release_read_failed')
    return result.stdout.strip()


def sha(value):
    require(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{40}', value), 'exact_source_required')
    return value


def digest(value):
    require(isinstance(value, str) and re.fullmatch(r'sha256:[0-9a-f]{64}', value), 'exact_digest_required')
    return value


def protected_main(repository, source):
    require(repository == 'Arijit634/notes-knowledge-workspace', 'untrusted_repository')
    sha(source)
    api = lambda path: json.loads(command('gh', 'api', 'repos/' + repository + '/' + path))
    require(api('git/ref/heads/main')['object']['sha'] == source, 'protected_main_moved')
    require(command('git', 'rev-parse', 'HEAD') == source and not command('git', 'status', '--porcelain'), 'source_checkout_not_clean')
    branch = api('branches/main')
    protection = branch['protection']
    contexts = protection['required_status_checks']
    # The content-readable branch endpoint is usable with GITHUB_TOKEN;
    # administration-only settings APIs must not require a new release secret.
    require(branch['protected'] and protection['enabled'] and contexts['enforcement_level'] == 'everyone'
            and len(contexts['contexts']) >= 12, 'protected_main_policy_missing')
    checks = api('commits/' + source + '/check-runs?per_page=100')['check_runs']
    # Prefer newest generation; an obsolete failed run cannot override a new
    # successful rerun, but incomplete/new failed runs never inherit old success.
    latest = {}
    for run in checks:
        if run['name'] not in latest or run['id'] > latest[run['name']]['id']:
            latest[run['name']] = run
    require(MAIN_CONTEXTS.issubset(latest), 'protected_main_evidence_missing')
    require(all(latest[name]['head_sha'] == source and latest[name]['status'] == 'completed'
                and latest[name]['conclusion'] == 'success' and latest[name]['app']['id'] == 15368 for name in MAIN_CONTEXTS), 'protected_main_not_green')
    commit = api('git/commits/' + source)
    for kind in ('author', 'committer'):
        require(re.fullmatch(r'(?:(?:[0-9]+\+[^@]+|web-flow)@users\.noreply\.github\.com|noreply@github\.com)', commit[kind]['email']), 'public_metadata_privacy_gate')
    return dict(source=source, protectedMainGreen=True, requiredContexts=len(contexts['contexts']), checks=sorted(MAIN_CONTEXTS))


def candidate(source, image, security, sbom, smoke):
    sha(source); digest(image)
    require(security['image'] == image and security['scanExit'] == 0, 'exact_image_scan_missing')
    require(security['secretFindingCount'] == 0, 'secret_gate_failed')
    require(security['releaseGatePassed'] is True, 'image_security_gate_failed')
    require(sbom['image'] == image and sbom['format'] == 'CycloneDX' and sbom['explicitShippedArtifacts'] == 6, 'exact_sbom_missing')
    require(smoke['passed'] is True and smoke['deploymentImage'] == image and smoke['productionJar'] is True, 'production_smoke_missing')
    require(any(row['step'] == 'encrypted_backup_restore' and row['runtimeQuarantined'] and row['tamperRefused'] for row in smoke['events']), 'recovery_rehearsal_missing')
    require(command('docker', 'image', 'inspect', '--format', '{{.Architecture}}', image) == 'arm64', 'native_arm64_required')
    require(command('docker', 'image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}', image) == source, 'image_source_binding_missing')
    return dict(source=source, candidateImage=image, architecture='arm64', schemaVersion=19, relations=38,
                sbomSha256=sbom['sha256'], securityGatePassed=True, smokePassed=True,
                containerProfileSha256=smoke['containerProfileSha256'],
                registryPublished=False, attestationVerified=False, releaseEligible=False, productionAuthorized=False)


def promotion(manifest, approval):
    sha(manifest['source']); digest(manifest['publishedDigest'])
    require(manifest['releaseEligible'] is True and manifest['attestationVerified'] is True
            and manifest['securityGatePassed'] is True and manifest['architecture'] == 'arm64', 'unreleased_artifact')
    require(approval['digest'] == manifest['publishedDigest'] and approval['source'] == manifest['source'], 'promotion_identity_mismatch')
    for gate in ('humanProductionApproval', 'currentBackupVerified', 'freeTierVerified', 'providerPostureVerified',
                 'secretsExternallyInjected', 'schemaCompatibilityVerified', 'readinessAndSyntheticPlanApproved', 'serializedMaintenanceConfirmed'):
        require(approval.get(gate) is True, 'manual_production_gate_missing')
    require(approval['schemaVersion'] == manifest['schemaVersion'], 'schema_compatibility_missing')
    if approval.get('rollback'):
        digest(approval['previousDigest'])
        require(approval['previousDigest'] == manifest['publishedDigest'], 'rollback_target_not_the_previous_release')
        require(digest(approval['runningDigest']) != manifest['publishedDigest'], 'rollback_running_identity_ambiguous')
        require(approval.get('previousArtifactSupportsCurrentSchema') is True, 'rollback_requires_forward_correction')
    # Receipt only; never SSH, restore old data, run migrations or open traffic.
    return dict(source=manifest['source'], digest=manifest['publishedDigest'], manualGateValidated=True,
                deploymentPerformed=False, databaseRestoreForRollback=False)


def verify_published_artifact(manifest):
    """Independently recheck a supplied manifest; its booleans are not signatures."""
    repository='Arijit634/notes-knowledge-workspace'
    source=sha(manifest['source']); image=digest(manifest['publishedDigest'])
    subject='ghcr.io/arijit634/notes-knowledge-workspace@'+image
    for predicate in ('https://slsa.dev/provenance/v1','https://cyclonedx.org/bom'):
        command('gh','attestation','verify','oci://'+subject,'--repo',repository,
                '--signer-workflow',repository+'/.github/workflows/backend-release.yml',
                '--source-ref','refs/heads/main','--source-digest',source,'--predicate-type',predicate,'--format','json')
    # Pull only the verified digest, never a tag. No container is created or run.
    command('docker','pull',subject)
    require(command('docker','image','inspect','--format','{{.Architecture}}',subject)=='arm64', 'published_architecture_mismatch')
    require(command('docker','image','inspect','--format','{{index .Config.Labels "org.opencontainers.image.revision"}}',subject)==source, 'published_source_mismatch')
    return dict(canonicalDigestVerified=True,provenanceAndSbomReverified=True,containerStarted=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('main', 'candidate', 'promotion'))
    parser.add_argument('--source'); parser.add_argument('--image'); parser.add_argument('--repository')
    parser.add_argument('--security', type=pathlib.Path); parser.add_argument('--sbom', type=pathlib.Path)
    parser.add_argument('--smoke', type=pathlib.Path); parser.add_argument('--manifest', type=pathlib.Path)
    parser.add_argument('--approval', type=pathlib.Path); parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args(); load = lambda path: json.loads(path.read_text())
    if args.mode == 'main':
        result = protected_main(args.repository, args.source)
    elif args.mode == 'candidate':
        result = candidate(args.source, args.image, load(args.security), load(args.sbom), load(args.smoke))
    else:
        manifest=load(args.manifest)
        result = promotion(manifest, load(args.approval))
        result.update(verify_published_artifact(manifest))
    with args.output.open('x') as output:
        json.dump(result, output, indent=2)
    print(json.dumps(dict(gate=args.mode, passed=True, externalMutation=False)))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        raise SystemExit('release_gate_failed_no_promotion_authorized') from None
