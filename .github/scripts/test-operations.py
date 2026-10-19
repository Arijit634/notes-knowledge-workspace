#!/usr/bin/env python3
"""FAST offline contract tests. No credentials, OCI/registry calls or provider use."""
import copy
import datetime
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, pathlib.Path(__file__).with_name(file))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ops = load('operations_plan', 'operations-plan.py')
release = load('release_guard', 'release-guard.py')


class OperationsContracts(unittest.TestCase):
    def setUp(self):
        self.data = json.loads(ops.CONTRACT.read_text())

    def binding(self):
        return dict(compartmentId='ocid1.compartment.oc1..synthetic', notificationTopicId='ocid1.onstopic.oc1..synthetic',
            verifiedOn=datetime.date.today().isoformat(), zeroCostConfirmed=True, retentionApproved=True,
            scrapeIntervalSeconds=60, signals={}, alarms={})

    def test_complete_finite_source_contract(self):
        ops.validate(self.data)
        self.assertEqual((len(self.data['dashboards']), len(self.data['alerts']), len(self.data['events'])), (7, 27, 17))
        profile=json.loads((ops.ROOT/'src/main/resources/operations/container-profile.json').read_text())
        self.assertEqual(profile['logOptions'],dict(mode='non-blocking',**{'max-buffer-size':'4m','max-size':'8m','max-file':'3'}))
        self.assertTrue(profile['readOnly']);self.assertEqual(profile['user'],'65533:65533')
        self.assertGreaterEqual(profile['stopTimeoutSeconds'],225)

    def test_unbound_cloud_evidence_is_not_a_fake_healthy_signal(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary) / 'plan'
            result = ops.render(self.data, ops.bind(self.data, self.binding()), directory)
            self.assertFalse(result['configurationBound'])
            self.assertFalse(result['productionReady'])
            self.assertIn('alarm-25', result['unbound'])
            self.assertFalse(result['deploymentAuthorized'])
            self.assertEqual(len(list(directory.glob('dashboard-*.json'))), 7)
            for file in directory.glob('alarm-*.json'):
                self.assertFalse(json.loads(file.read_text())['isEnabled'])
            with self.assertRaises(FileExistsError):
                ops.render(self.data, self.binding(), directory)

    def test_external_bindings_can_complete_all_queries_without_creating_cloud_resources(self):
        config = self.binding()
        # Deliberate synthetic test-only operator metrics, not actual availability evidence.
        for key in self.data['operatorSignals']:
            config['signals'][key] = dict(namespace='synthetic_test', query='synthetic_test[1m].max()', verifiedAvailable=True)
        for alarm in self.data['alerts']:
            if 'query' not in alarm or alarm['id'] in ops.OPERATOR_ESCALATIONS:
                config['alarms'][str(alarm['id'])] = dict(namespace='synthetic_test', query='synthetic_test[1m].max() > 0', verifiedAvailable=True)
                if alarm['id'] in ops.OPERATOR_ESCALATIONS:
                    config['alarms'][str(alarm['id'])]['overrides']=[dict(ruleName='high',query='synthetic_test[1m].max() > 1',severity='ERROR',pendingDuration='PT1M')]
                    if alarm['id']==19:
                        config['alarms'][str(alarm['id'])]['overrides'].append(dict(ruleName='critical',query='synthetic_test[1m].max() > 2',severity='CRITICAL',pendingDuration='PT1M'))
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary) / 'plan'
            result = ops.render(self.data, ops.bind(self.data, config), directory)
            self.assertTrue(result['configurationBound'])
            self.assertFalse(result['productionReady'])
            self.assertEqual(result['cloudCalls'], 0)
            self.assertEqual(len(list(directory.glob('alarm-*.json'))), 27)
            for number in (11,13):
                self.assertEqual(json.loads((directory/('alarm-'+str(number)+'.json')).read_text())['overrides'][0]['severity'],'ERROR')

    def test_private_scrape_and_nonsuppressible_invariants_are_mandatory(self):
        unsafe = copy.deepcopy(self.data); unsafe['scrape']['url'] = 'https://example.test/metrics'
        with self.assertRaises(ValueError): ops.validate(unsafe)
        unsafe = copy.deepcopy(self.data); unsafe['alerts'][24]['maintenanceAllowed'] = True
        with self.assertRaises(ValueError): ops.validate(unsafe)

    def test_stale_unverified_or_secret_bearing_operator_inputs_are_rejected(self):
        for name, value in [('verifiedOn', '2020-01-01'), ('zeroCostConfirmed', False), ('retentionApproved', False), ('scrapeIntervalSeconds', 5)]:
            config = self.binding(); config[name] = value
            with self.assertRaises(ValueError): ops.bind(self.data, config)
        config = self.binding(); config['password'] = 'reserved-synthetic-value'
        with self.assertRaises(ValueError): ops.bind(self.data, config)


class ReleaseContracts(unittest.TestCase):
    def test_protected_main_gate_cannot_inherit_old_or_wrong_source_success(self):
        source='a'*40
        checks=[dict(id=n,name=name,head_sha=source,status='completed',conclusion='success',app=dict(id=15368))
                for n,name in enumerate(sorted(release.MAIN_CONTEXTS))]
        def read(*args):
            if args[:2]==('git','rev-parse'): return source
            if args[:2]==('git','status'): return ''
            path=args[-1]
            if path.endswith('git/ref/heads/main'): return json.dumps(dict(object=dict(sha=source)))
            if path.endswith('branches/main'): return json.dumps(dict(protected=True,protection=dict(enabled=True,
                required_status_checks=dict(enforcement_level='everyone',contexts=list(range(12))))))
            if 'check-runs?' in path: return json.dumps(dict(check_runs=checks))
            if '/git/commits/' in path: return json.dumps(dict(author=dict(email='12345+synthetic@users.noreply.github.com'),committer=dict(email='noreply@github.com')))
            raise AssertionError('unexpected_read')
        with patch.object(release,'command',side_effect=read):
            self.assertTrue(release.protected_main('Arijit634/notes-knowledge-workspace',source)['protectedMainGreen'])
            checks[0]['head_sha']='b'*40
            with self.assertRaises(ValueError): release.protected_main('Arijit634/notes-knowledge-workspace',source)
            checks[0]['head_sha']=source
            checks.append(dict(checks[0],id=100,status='in_progress',conclusion=None))
            with self.assertRaises(ValueError): release.protected_main('Arijit634/notes-knowledge-workspace',source)

    def test_failed_scan_cannot_be_promoted_or_published(self):
        with self.assertRaisesRegex(ValueError, 'image_security_gate_failed'):
            release.candidate('a' * 40, 'sha256:' + 'b' * 64,
                dict(image='sha256:' + 'b' * 64, scanExit=0, secretFindingCount=0, releaseGatePassed=False), {}, {})

    def test_manual_promotion_requires_exact_identity_and_every_manual_gate(self):
        manifest = dict(source='a' * 40, publishedDigest='sha256:' + 'b' * 64, releaseEligible=True,
            attestationVerified=True, securityGatePassed=True, architecture='arm64', schemaVersion=19)
        approval = dict(source=manifest['source'], digest=manifest['publishedDigest'], schemaVersion=19)
        with self.assertRaises(ValueError): release.promotion(manifest, approval)
        for gate in ('humanProductionApproval', 'currentBackupVerified', 'freeTierVerified', 'providerPostureVerified',
                     'secretsExternallyInjected', 'schemaCompatibilityVerified', 'readinessAndSyntheticPlanApproved', 'serializedMaintenanceConfirmed'):
            approval[gate] = True
        self.assertFalse(release.promotion(manifest, approval)['deploymentPerformed'])
        approval['digest'] = 'sha256:' + 'c' * 64
        with self.assertRaises(ValueError): release.promotion(manifest, approval)

    def test_rollback_never_restores_old_database_and_requires_compatible_artifact(self):
        manifest = dict(source='a' * 40, publishedDigest='sha256:' + 'b' * 64, releaseEligible=True,
            attestationVerified=True, securityGatePassed=True, architecture='arm64', schemaVersion=19)
        approval = dict(source=manifest['source'], digest=manifest['publishedDigest'], schemaVersion=19, rollback=True,
            previousDigest='sha256:' + 'b' * 64, runningDigest='sha256:'+'c'*64)
        for gate in ('humanProductionApproval', 'currentBackupVerified', 'freeTierVerified', 'providerPostureVerified',
                     'secretsExternallyInjected', 'schemaCompatibilityVerified', 'readinessAndSyntheticPlanApproved', 'serializedMaintenanceConfirmed'):
            approval[gate] = True
        with self.assertRaises(ValueError): release.promotion(manifest, approval)
        approval['previousArtifactSupportsCurrentSchema'] = True
        self.assertFalse(release.promotion(manifest, approval)['databaseRestoreForRollback'])

    def test_promotion_does_not_trust_manifest_verification_booleans(self):
        manifest=dict(source='a'*40,publishedDigest='sha256:'+'b'*64)
        with patch.object(release,'command',side_effect=ValueError('signature_failed')) as transport:
            with self.assertRaises(ValueError): release.verify_published_artifact(manifest)
            self.assertEqual(transport.call_count,1)


if __name__ == '__main__':
    unittest.main()
