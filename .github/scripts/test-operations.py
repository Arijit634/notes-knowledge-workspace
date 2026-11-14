#!/usr/bin/env python3
"""FAST offline contract tests. No credentials, OCI/registry calls or provider use."""
import copy
import datetime
import importlib.util
import json
import pathlib
import subprocess
import sys
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
smoke = load('deployment_smoke', 'deployment-smoke.py')
facts = load('native_image_facts', 'native-image-facts.py')
reconciliation = load('image_reconciliation', 'reconcile-image-security.py')


class ImageReconciliationContracts(unittest.TestCase):
    def test_checked_in_expat_patches_are_the_exact_reviewed_source_inputs(self):
        for commit,digest in facts.EXPAT_SECURITY_PATCHES.items():
            path=facts.ROOT/'src/main/native/upstream'/('expat-'+commit+'.patch')
            self.assertTrue(path.is_file())
            data=path.read_bytes()
            self.assertLess(len(data),65536)
            self.assertEqual(digest,__import__('hashlib').sha256(data).hexdigest())
            self.assertTrue(data.startswith(('From '+commit+' ').encode()))
    def test_ffmpeg_backport_requires_all_exact_controls(self):
        values=[dict(facts.FFMPEG_SECURITY_PATCHES),
            dict(unpatchedCleanupExit=139,expectedStackExhaustion=True,coreDumpEnabled=False),
            dict(ffmpegEncryptionRegression=True,cleanupNodes=100000,appendRecords=10000,boundedStackKiB=256),
            dict(upstreamEncryptionInfoFatePassed=True),'!CONFIG_CDXL_DECODER=yes\n']
        self.assertTrue(facts.validate_ffmpeg_security(*values)['cdxlDecoderDisabled'])
        for index in range(len(values)):
            changed=copy.deepcopy(values);changed[index]={} if index<4 else 'CONFIG_CDXL_DECODER=yes\n'
            with self.subTest(index=index),self.assertRaises(ValueError):facts.validate_ffmpeg_security(*changed)

    def setUp(self):
        self.today=datetime.date(2026,11,10)
        self.image='sha256:'+'a'*64
        self.finding=dict(VulnerabilityID='CVE-2025-69720',PkgName='ncurses-bin',InstalledVersion='6.5-test',Severity='HIGH')
        self.scan=dict(image=self.image,scanExit=1,javaPackagesAnalyzed=250,osPackagesAnalyzed=80,
            javaVulnerabilityCount=0,secretFindingCount=0,secretFindings=[],misconfigurations=[],
            vulnerabilityCounts=dict(HIGH=1),vulnerabilities=[self.finding],
            database=dict(UpdatedAt='2026-11-10T00:00:00Z',DownloadedAt='2026-11-10T01:00:00Z'))
        self.facts=dict(deploymentImage=self.image,source='b'*40,architecture='arm64',imageUser='65533:65533',
            productionSourceFingerprint=dict(sha256='c'*64),packages={'ncurses-bin':dict(version='6.5-test')},
            setuidSetgidFilesAbsent=True,fontConfigurationRootOwnedAndNotWritable=True,defaultLocaleInputsRestricted=True,
            nativeLinkageMentionsSqlite=False,sqliteApplicationDependencyPresent=False,
            components=dict(infocmp=dict(absent=True)))
        self.smoke=dict(deploymentImage=self.image,passed=True,productionJar=True,events=[],
            resourceMeasurements=[dict(javaProcessesInspected=1,sqliteLoaded=False)])
        self.rule=dict(self.finding,kind='component-not-shipped',owner='synthetic-test-maintainer',
            reviewedOn='2026-11-10',expiresOn='2026-11-17',rationale='The vulnerable tool is absent from this exact synthetic image.',
            compensatingControls='Numeric unprivileged identity, absent executable and immutable source binding.',
            remediationTarget='Review a fixed stable vendor package by the expiry date.',requiredControls=['infocmpAbsent'],
            references=['https://security-tracker.debian.org/tracker/CVE-2025-69720'])
        self.policy=dict(schemaVersion=1,purpose='exact-native-runtime-applicability',architectures=['arm64'],
            productionSourceSha256='c'*64,findings=[self.rule])

    def run_policy(self):
        return reconciliation.reconcile(self.scan,self.facts,self.smoke,self.policy,self.today)

    def test_source_package_unpacker_review_requires_each_affected_component_absent(self):
        components=('dpkgSource','dpkgSourceV2','perl','tar')
        self.rule['requiredControls']=[name+'Absent' for name in components]
        for name in components:self.facts['components'][name]=dict(absent=True)
        self.assertTrue(self.run_policy()['releaseSecurityReconciled'])
        for name in components:
            with self.subTest(component=name):
                self.facts['components'][name]['absent']=False
                with self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()
                del self.facts['components'][name]
                with self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()
                self.facts['components'][name]=dict(absent=True)

    def test_gpt_generator_review_requires_both_binary_paths_measured_absent(self):
        names=('systemdGptAutoGenerator','systemdGptAutoGeneratorLegacy')
        self.rule['requiredControls']=[name+'Absent' for name in names]
        for name in names:self.facts['components'][name]=dict(absent=True)
        self.assertTrue(self.run_policy()['releaseSecurityReconciled'])
        for name in names:
            with self.subTest(component=name):
                self.facts['components'][name]['absent']=False
                with self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()
                del self.facts['components'][name]
                with self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()
                self.facts['components'][name]=dict(absent=True)

    def test_full_bundle_dispositions_require_measured_controls(self):
        required=[
            'native64BitAbi',
            'gnutlsNotLinkedInFullBundle',
            'elfBpfNotLinkedInFullBundle',
            'upstreamOpenH26426Verified',
            'publicPrivateSeparationQualified',
        ]
        self.rule['requiredControls']=required
        with self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()
        bundle=dict(gnutlsNotLinked=True,elfBpfNotLinked=True,native64BitAbi=True,
            openh264SourceCopiesMatch=True,
            openh264SourceSha256='52fc65775e03abe70f3f453c3c57fa7b31c71fe240de84da53d75f2ddc7dd519')
        self.facts['fullHostedBundle']=bundle
        self.smoke['events']=[dict(step='public_private_separation',privateResourceDenied=True)]
        self.assertTrue(self.run_policy()['releaseSecurityReconciled'])
        original=copy.deepcopy(bundle)
        for name in bundle:
            self.facts['fullHostedBundle']=dict(original,**{name:False if isinstance(bundle[name],bool) else '0'*64})
            with self.subTest(name=name),self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()
        self.facts['fullHostedBundle']=original
        self.smoke['events']=[]
        with self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()

    def test_missing_java_mapping_observations_do_not_become_unloaded_claims(self):
        self.smoke['resourceMeasurements']=[dict(peakBytes=100)]
        active=reconciliation.controls(self.facts,self.smoke)
        self.assertFalse(active['opensslNotLoadedInWorkload'])
        self.assertFalse(active['sqliteNotLoadedInWorkload'])

    def test_managed_boundary_requires_artifact_entrypoint_and_measured_processes(self):
        self.facts['fullHostedBundle']=dict(serviceBoundaryArtifactSha256='d'*64,serviceBoundaryEntryPoint=True)
        with self.assertRaisesRegex(ValueError,'managed_service_boundary_evidence_missing'):self.run_policy()
        self.smoke.update(managedWritableHostProfile=True,managedServiceKernelBoundary=True)
        self.smoke['resourceMeasurements'][0]['managedProcessBoundaryVerified']=True
        self.assertTrue(self.run_policy()['releaseSecurityReconciled'])
        self.smoke['resourceMeasurements'][0]['managedProcessBoundaryVerified']=False
        with self.assertRaisesRegex(ValueError,'managed_service_boundary_evidence_missing'):self.run_policy()
        self.smoke['resourceMeasurements'][0]['managedProcessBoundaryVerified']=True
        self.facts['fullHostedBundle']['serviceBoundaryEntryPoint']=False
        with self.assertRaisesRegex(ValueError,'managed_service_boundary_evidence_missing'):self.run_policy()

    def test_glibc_assessment_rejects_unexpected_native_and_jni_inputs(self):
        audit=dict(image=self.image,architecture='arm64',selectedRuntimeAndLinkageInspected=True,
            vulnerableLibcPresent=True,inspectedElfFiles=20,copiedNativeCodeExecuted=False,
            runtimeCredentialsUsed=False,glibcDeclaredFixed=False,inventorySha256='d'*64,
            regexConsumers=[],wideScanfConsumers=[],supervisorScriptMatchesSource=False)
        self.facts['glibcInputAudit']=audit
        self.assertTrue(self.run_policy()['releaseSecurityReconciled'])
        audit['wideScanfConsumers']=[dict(path='BOOT-INF/lib/example.jar!/native.so',wideScanf=['__isoc99_vswscanf'])]
        with self.assertRaisesRegex(ValueError,'unreviewed_glibc_input_path'):self.run_policy()
        audit['wideScanfConsumers']=[];audit['regexConsumers']=[dict(path='/opt/video/tools/bin/ffmpeg',regex=['regexec'])]
        with self.assertRaisesRegex(ValueError,'unreviewed_glibc_input_path'):self.run_policy()
        audit['regexConsumers']=[dict(path='/bin/bash',regex=['regexec'])]
        with self.assertRaisesRegex(ValueError,'unreviewed_glibc_input_path'):self.run_policy()
        self.facts['fullHostedBundle']=dict(example=True);audit['supervisorScriptMatchesSource']=True
        self.assertTrue(self.run_policy()['releaseSecurityReconciled'])
        audit['inventorySha256']=''
        with self.assertRaisesRegex(ValueError,'unreviewed_glibc_input_path'):self.run_policy()

    def test_exact_non_shipped_component_retains_the_original_failed_scan(self):
        before=copy.deepcopy(self.scan)
        result=self.run_policy()
        self.assertTrue(result['releaseSecurityReconciled'])
        self.assertEqual(1,result['rawScannerExit'])
        self.assertEqual(dict(HIGH=1),result['rawVulnerabilityCounts'])
        self.assertEqual(1,result['findingsRetained'])
        self.assertFalse(result['scannerFindingsSuppressed'])
        self.assertFalse(result['publicLaunchApproved'])
        self.assertEqual(before,self.scan)

    def test_new_finding_is_not_covered_by_an_existing_review(self):
        self.scan['vulnerabilities'].append(dict(self.finding,VulnerabilityID='CVE-2099-12345'))
        self.scan['vulnerabilityCounts']['HIGH']=2
        result=self.run_policy()
        self.assertFalse(result['releaseSecurityReconciled'])
        self.assertEqual('CVE-2099-12345',result['unreviewedFindings'][0]['finding'])

    def test_changed_code_architecture_package_or_control_fails_closed(self):
        original=copy.deepcopy(self.facts)
        for change in ('source','architecture','package','component'):
            self.facts=copy.deepcopy(original)
            if change=='source': self.facts['productionSourceFingerprint']['sha256']='d'*64
            elif change=='architecture': self.facts['architecture']='amd64'
            elif change=='package': self.facts['packages']['ncurses-bin']['version']='new-version'
            else: self.facts['components']['infocmp']['absent']=False
            with self.subTest(change=change),self.assertRaises(ValueError):self.run_policy()

    def test_expired_future_and_overlong_reviews_are_rejected(self):
        for reviewed,expires in [('2026-11-01','2026-11-08'),('2026-11-11','2026-11-17'),('2026-11-10','2026-12-10')]:
            self.rule.update(reviewedOn=reviewed,expiresOn=expires)
            with self.subTest(reviewed=reviewed,expires=expires),self.assertRaises(ValueError):self.run_policy()

    def test_secret_misconfiguration_and_java_findings_are_never_excepted(self):
        original=copy.deepcopy(self.scan)
        for field,value in [('secretFindingCount',1),('secretFindings',[{}]),('misconfigurations',[{}]),('javaVulnerabilityCount',1),('scanExit',2)]:
            self.scan=dict(copy.deepcopy(original),**{field:value})
            with self.subTest(field=field),self.assertRaises(ValueError):self.run_policy()

    def test_weak_or_mismatched_evidence_is_rejected(self):
        original=copy.deepcopy(self.smoke)
        for field,value in [('deploymentImage','sha256:'+'f'*64),('passed',False),('productionJar',False),('feasibilityOnly',True)]:
            self.smoke=dict(copy.deepcopy(original),**{field:value})
            with self.subTest(field=field),self.assertRaises(ValueError):self.run_policy()

    def test_stale_scanner_database_and_incomplete_or_duplicate_reviews_are_rejected(self):
        self.scan['database']['UpdatedAt']='2026-11-08T00:00:00Z'
        with self.assertRaisesRegex(ValueError,'fresh_vulnerability_database_required'):self.run_policy()
        self.scan['database']['UpdatedAt']='2026-11-10T00:00:00Z'
        self.policy['findings'].append(copy.deepcopy(self.rule))
        with self.assertRaisesRegex(ValueError,'duplicate_disposition'):self.run_policy()
        self.policy['findings'].pop();self.rule['requiredControls']=[]
        with self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()

    def test_a_recent_scan_survives_midnight_but_future_timestamps_do_not(self):
        self.today=datetime.date(2026,11,11)
        self.scan['database'].update(UpdatedAt='2026-11-10T22:00:00Z',DownloadedAt='2026-11-10T23:00:00Z')
        self.assertTrue(self.run_policy()['releaseSecurityReconciled'])
        self.scan['database']['DownloadedAt']='2026-11-12T00:00:00Z'
        with self.assertRaisesRegex(ValueError,'fresh_vulnerability_database_required'):self.run_policy()

    def test_crypto_absence_requires_linkage_and_actual_workload_observation(self):
        self.rule['requiredControls']=['opensslNotLinkedInMedia','opensslNotLoadedInWorkload']
        self.facts.update(ffmpegOpenSslDisabled=True,nativeLinkageMentionsOpenSsl=False)
        with self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()
        self.smoke['resourceMeasurements'][0]['opensslLoaded']=False
        self.assertTrue(self.run_policy()['releaseSecurityReconciled'])
        self.smoke['resourceMeasurements'][0]['opensslLoaded']=True
        with self.assertRaisesRegex(ValueError,'required_control_missing'):self.run_policy()


class NativeArtifactFactContracts(unittest.TestCase):
    def test_image_evidence_must_match_architecture_user_and_source(self):
        source='a'*40
        image=dict(Architecture='arm64',Config=dict(User='65533:65533',
            Labels={'org.opencontainers.image.revision':source}))
        self.assertEqual('arm64',facts.validate_images([image,copy.deepcopy(image)],source))
        for key,value in (('Architecture','amd64'),('User','0:0'),('revision','b'*40)):
            changed=copy.deepcopy(image)
            if key=='Architecture': changed[key]=value
            elif key=='User': changed['Config'][key]=value
            else: changed['Config']['Labels']['org.opencontainers.image.revision']=value
            with self.subTest(field=key),self.assertRaisesRegex(ValueError,'artifact_binding_failed'):
                facts.validate_images([image,changed],source)
        with self.assertRaises(ValueError): facts.validate_images([image],source)

    def test_version_facts_reject_old_or_ambiguous_security_libraries(self):
        packages='libfreetype6:arm64\t2.15.0-0nkw1\tlibfreetype6\nlibexpat1:arm64\t2.10.0-0nkw2\tlibexpat1\n'
        self.assertEqual(2,len(facts.package_inventory(packages)))
        for invalid in (packages.replace('2.10.0-0nkw2','2.7.1-1'),packages.replace('2.10.0-0nkw2','2.10.0-0nkw1'),
                        packages.splitlines()[0],packages+packages.splitlines()[0]+'\n'):
            with self.subTest(invalid=invalid),self.assertRaises(ValueError): facts.package_inventory(invalid)


class ShippedSbomContracts(unittest.TestCase):
    def test_library_inventory_cannot_redirect_the_sbom_output(self):
        # Execute the actual embedded augmentation, including its final atomic
        # replacement. This catches output-path shadowing by library inventory.
        script = pathlib.Path(__file__).with_name('scan-deployment.sh').read_text()
        augmentation = script.split('python3 - "$security" "$deployment" <<\'PY\'\n', 1)[1].split('\nPY\n', 1)[0]
        for triplet in ('x86_64-linux-gnu', 'aarch64-linux-gnu'):
            with self.subTest(triplet=triplet), tempfile.TemporaryDirectory() as temporary:
                root = pathlib.Path(temporary)
                sbom = root / 'deployment.cdx.json'
                components = [dict(type='library', name=f'dependency-{n}',
                    purl=f'pkg:maven/synthetic/library-{n}@1', **{'bom-ref':f'maven-{n}'}) for n in range(200)]
                sbom.write_text(json.dumps(dict(bomFormat='CycloneDX', specVersion='1.6', version=1,
                    metadata=dict(component={'bom-ref':'image'}), components=components)))
                paths = ['/opt/video/tools/bin/ffmpeg','/opt/video/tools/bin/ffprobe',
                    '/opt/pdf/helper','/opt/video/helper','/opt/operations/health-probe',
                    '/jdk/lib/modules','/application.jar',
                    f'/usr/lib/{triplet}/libfreetype.so.6',f'/usr/lib/{triplet}/libexpat.so.1']
                (root / 'shipped-artifacts.sha256').write_text(''.join(f'{n:064x}  {path}\n' for n,path in enumerate(paths,1)))
                result = subprocess.run([sys.executable,'-',str(root),'sha256:'+'a'*64],
                    input=augmentation, text=True, capture_output=True, timeout=10)
                self.assertEqual(0,result.returncode,result.stderr)
                data = json.loads(sbom.read_text())
                self.assertEqual(2,data['version'])
                self.assertEqual(209,len(data['components']))
                self.assertEqual(9,len(data['dependencies'][0]['dependsOn']))
                receipt = json.loads((root / 'sbom-receipt.json').read_text())
                self.assertEqual(9,receipt['explicitShippedArtifacts'])
                self.assertFalse(receipt['manualNativeVulnerabilityCoverageClaimed'])
                self.assertFalse((root / 'deployment-augmented.cdx.json').exists())
                self.assertFalse((root / 'shipped-artifacts.sha256').exists())


class ResourceProbeContracts(unittest.TestCase):
    def test_explicit_startup_probe_does_not_mutate_the_approved_release_profile(self):
        normal, default_jvm = smoke.resource_plan()
        limited, limited_jvm = smoke.resource_plan('render-startup')
        self.assertEqual((2147483648, 2000000000), (normal['memoryBytes'], normal['nanoCpus']))
        self.assertEqual(['-Xmx512m'], default_jvm)
        self.assertEqual((536870912, 100000000), (limited['memoryBytes'], limited['nanoCpus']))
        for field in ('user','readOnly','pidsLimit','capDrop','securityOpt','tmpfs','stopTimeoutSeconds'):
            self.assertEqual(normal[field], limited[field])
        self.assertIn('-XX:+ExitOnOutOfMemoryError', limited_jvm)
        self.assertEqual((normal, default_jvm), smoke.resource_plan())
        with self.assertRaisesRegex(RuntimeError, 'unknown_resource_probe'):
            smoke.resource_plan('unbounded')

    def test_observer_matches_host_window_without_changing_worker_limits(self):
        self.assertEqual(75, smoke.readiness_observer_timeout())
        self.assertEqual(900, smoke.readiness_observer_timeout('render-startup'))
        self.assertEqual(75, smoke.readiness_observer_timeout('unknown'))

    def test_startup_diagnostics_only_expose_fixed_booleans(self):
        signals = smoke.startup_signals('private-value Started Application in 2 seconds')
        self.assertTrue(signals['applicationStarted'])
        self.assertFalse(signals['tomcatInitialized'])
        self.assertTrue(all(isinstance(value, bool) for value in signals.values()))
        self.assertNotIn('private-value', json.dumps(signals))


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
    def test_candidate_requires_current_measured_schema_and_relation_evidence(self):
        source='a'*40;image='sha256:'+'b'*64
        security=dict(image=image,scanExit=0,secretFindingCount=0,releaseGatePassed=True)
        sbom=dict(image=image,format='CycloneDX',explicitShippedArtifacts=9,sha256='c'*64)
        smoke=dict(passed=True,deploymentImage=image,productionJar=True,schemaVersion=20,relations=39,migrations=20,
            containerProfileSha256='d'*64,events=[dict(step='encrypted_backup_restore',runtimeQuarantined=True,tamperRefused=True)])
        def docker(*args):return 'arm64' if args[-2]=='{{.Architecture}}' else source
        with patch.object(release,'command',side_effect=docker):
            result=release.candidate(source,image,security,sbom,smoke)
            self.assertEqual((20,39),(result['schemaVersion'],result['relations']))
            with self.assertRaisesRegex(ValueError, 'exact_sbom_missing'):
                release.candidate(source,image,security,dict(sbom,explicitShippedArtifacts=6),smoke)
            with self.assertRaisesRegex(ValueError, 'exact_sbom_missing'):
                release.candidate(source,image,security,dict(sbom,explicitShippedArtifacts=8),smoke)
            with self.assertRaisesRegex(ValueError, 'resource_probe_is_not_release_qualification'):
                release.candidate(source,image,security,sbom,dict(smoke,feasibilityOnly=True))
            for field,old in [('schemaVersion',19),('relations',38),('migrations',19)]:
                stale=dict(smoke);stale[field]=old
                with self.subTest(field=field),self.assertRaisesRegex(ValueError,'current_schema_evidence_missing'):
                    release.candidate(source,image,security,sbom,stale)

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

    def test_partial_or_wrong_source_applicability_evidence_cannot_authorize_a_release(self):
        source='a'*40;image='sha256:'+'b'*64
        security=dict(image=image,scanExit=1,secretFindingCount=0,releaseGatePassed=False)
        for facts,policy in [(dict(source=source),None),(None,{}),(dict(source='c'*40),{})]:
            with self.subTest(facts=facts),self.assertRaisesRegex(ValueError,'applicability_evidence_missing'):
                release.candidate(source,image,security,{}, {},facts,policy)

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
