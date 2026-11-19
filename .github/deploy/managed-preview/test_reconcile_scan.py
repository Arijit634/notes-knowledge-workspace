"""Offline security contracts; no Docker, credentials or provider calls."""
import copy,datetime,importlib.util,pathlib,unittest

spec=importlib.util.spec_from_file_location('preview_reconciliation',pathlib.Path(__file__).with_name('reconcile_scan.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class VendorFixContracts(unittest.TestCase):
    def setUp(self):
        self.now=datetime.datetime(2026,11,19,13,tzinfo=datetime.timezone.utc)
        self.image='sha256:'+'a'*64;self.source='b'*40;self.fingerprint='c'*64
        self.rows=[dict(VulnerabilityID=c,PkgName=p,InstalledVersion=module.VERSION,Severity='HIGH')
            for c in sorted(module.CVES) for p in ('nginx','nginx-common')]
        self.raw=dict(ArtifactName=self.image,Metadata=dict(OS=dict(Family='ubuntu',Name='24.04')),
            Results=[dict(Type='jar',Packages=[{}]*200),dict(Class='os-pkgs',Packages=[{}]*2,Vulnerabilities=copy.deepcopy(self.rows))])
        self.facts=dict(image=self.image,source=self.source,productionSourceSha256=self.fingerprint,
            architecture='amd64',user='65533:65533',packages={p:dict(version=module.VERSION,source='nginx') for p in ('nginx','nginx-common')})
        self.database=dict(UpdatedAt='2026-11-19T07:00:00Z',DownloadedAt='2026-11-19T12:00:00Z')
        self.smoke=dict(image=self.image,liveProviderCalls=0,realDataUsed=False,
            resources=[dict(peakBytes=400000000,memoryLimitBytes=536870912,oomEvents=0,restarts=0)],
            **{k:True for k in module.BOUNDARIES})
        self.policy=dict(schemaVersion=1,purpose='exact-managed-preview-vendor-fixes',productionSourceSha256=self.fingerprint,
            findings=[dict(row,kind='upstream-fixed',reviewedOn='2026-11-19',expiresOn='2026-11-26',
                references=['https://ubuntu.com/security/'+row['VulnerabilityID']],vendorEvidenceSha256='d'*64,
                rationale='The exact vendor revision includes the reviewed security backport.',owner='repository-maintainers',
                compensatingControls='Exact source, image, package identities and actual runtime boundary qualification.',
                remediationTarget='Review vendor and scanner status again before this assessment expires.') for row in self.rows])
        self.exit=1

    def run_policy(self):return module.reconcile(self.raw,self.exit,self.database,self.facts,self.smoke,self.policy,self.now)

    def test_retains_every_raw_finding_without_modifying_the_report(self):
        before=copy.deepcopy(self.raw);result=self.run_policy()
        self.assertEqual(8,result['rawHighCriticalFindings']);self.assertEqual(self.rows,result['findingsRetained'])
        self.assertEqual(1,result['rawScannerExit']);self.assertFalse(result['scannerFindingsSuppressed'])
        self.assertFalse(result['fullApplicationReleaseQualified']);self.assertEqual(before,self.raw)

    def test_new_or_severity_changed_finding_is_rejected(self):
        for change in (dict(VulnerabilityID='CVE-2099-12345'),dict(Severity='CRITICAL'),
                       dict(PkgName='other-library'),dict(InstalledVersion='1.24.0-2ubuntu7.19')):
            findings=self.raw['Results'][1]['Vulnerabilities'];before=copy.deepcopy(findings[0]);findings[0].update(change)
            with self.subTest(change=change),self.assertRaisesRegex(ValueError,'unreviewed_security_finding'):self.run_policy()
            findings[0]=before

    def test_no_general_unfixed_or_unreachable_exception_is_permitted(self):
        for kind in ('bounded-nonblocker','unreachable-precondition','component-not-shipped'):
            self.policy['findings'][0]['kind']=kind
            with self.subTest(kind=kind),self.assertRaisesRegex(ValueError,'unapproved_vendor_fix'):self.run_policy()

    def test_every_runtime_authorization_boundary_is_required(self):
        for key in module.BOUNDARIES:
            self.smoke[key]=False
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'runtime_boundary_evidence_missing'):self.run_policy()
            self.smoke[key]=True

    def test_exact_package_source_and_version_are_required_for_both_binaries(self):
        for package in ('nginx','nginx-common'):
            original=self.facts['packages'].pop(package)
            with self.subTest(missing=package),self.assertRaisesRegex(ValueError,'exact_vendor_packages_required'):self.run_policy()
            self.facts['packages'][package]=dict(original,version='1.24.0-2ubuntu7.19')
            with self.subTest(old=package),self.assertRaisesRegex(ValueError,'exact_vendor_packages_required'):self.run_policy()
            self.facts['packages'][package]=dict(original,source='unreviewed')
            with self.subTest(source=package),self.assertRaisesRegex(ValueError,'exact_vendor_packages_required'):self.run_policy()
            self.facts['packages'][package]=original

    def test_expired_future_or_overlong_assessment_is_rejected(self):
        for dates in [('2026-11-10','2026-11-17'),('2026-11-20','2026-11-26'),('2026-11-19','2026-12-19')]:
            self.policy['findings'][0].update(reviewedOn=dates[0],expiresOn=dates[1])
            with self.subTest(dates=dates),self.assertRaisesRegex(ValueError,'vendor_fix_review_expired'):self.run_policy()

    def test_source_image_and_architecture_mismatch_is_rejected(self):
        for key,value in [('source','invalid'),('productionSourceSha256','0'*64),('architecture','arm64'),('user','0:0')]:
            original=self.facts[key];self.facts[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):self.run_policy()
            self.facts[key]=original
        self.raw['ArtifactName']='sha256:'+'e'*64
        with self.assertRaisesRegex(ValueError,'scan_image_mismatch'):self.run_policy()

    def test_scanner_failure_and_incomplete_coverage_are_never_zero_findings(self):
        for exit_status in (2,137):
            self.exit=exit_status
            with self.subTest(exit=exit_status),self.assertRaisesRegex(ValueError,'scanner_execution_failed'):self.run_policy()
        self.exit=1;self.raw['Results'][0]['Packages']=[]
        with self.assertRaisesRegex(ValueError,'scanner_coverage_missing'):self.run_policy()

    def test_stale_database_and_unmatched_scanner_exit_are_rejected(self):
        for field in ('UpdatedAt','DownloadedAt'):
            original=self.database[field];self.database[field]='2026-11-01T00:00:00Z'
            with self.subTest(field=field),self.assertRaisesRegex(ValueError,'fresh_scanner_database_required'):self.run_policy()
            self.database[field]=original
        self.exit=0
        with self.assertRaisesRegex(ValueError,'scanner_result_inconsistent'):self.run_policy()

    def test_memory_failure_live_calls_and_real_data_invalidate_qualification(self):
        for key,value in [('oomEvents',1),('restarts',1),('peakBytes',600000000)]:
            original=self.smoke['resources'][0][key];self.smoke['resources'][0][key]=value
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'runtime_memory_evidence_missing'):self.run_policy()
            self.smoke['resources'][0][key]=original
        for key,value in [('liveProviderCalls',1),('realDataUsed',True)]:
            self.smoke[key]=value
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'runtime_boundary_evidence_missing'):self.run_policy()
            self.smoke[key]=0 if key=='liveProviderCalls' else False

    def test_duplicate_unreviewed_policy_and_false_primary_evidence_are_rejected(self):
        original=copy.deepcopy(self.policy)
        for change in ('duplicate','missing','reference','evidence'):
            self.policy=copy.deepcopy(original)
            if change=='duplicate':self.policy['findings'].append(copy.deepcopy(self.policy['findings'][0]))
            elif change=='missing':self.policy['findings'].pop()
            elif change=='reference':self.policy['findings'][0]['references']=['https://evil.example/CVE']
            else:self.policy['findings'][0]['vendorEvidenceSha256']='invalid'
            with self.subTest(change=change),self.assertRaises(ValueError):self.run_policy()

    def test_clean_complete_scan_does_not_invent_findings(self):
        self.raw['Results'][1]['Vulnerabilities']=[];self.exit=0
        result=self.run_policy();self.assertTrue(result['securityReconciled']);self.assertEqual([],result['findingsRetained'])

if __name__=='__main__':unittest.main()
