"""FAST readiness contract regression; fake clock, no Docker or network calls."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
import urllib.error
from email.message import Message

spec=importlib.util.spec_from_file_location('managed_smoke',Path(__file__).with_name('smoke.py'))
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)

class ReadinessTests(unittest.TestCase):
    def test_core_validator_requires_strong_tag_and_one_no_transform_policy(self):
        headers=Message();headers['ETag']='"opaque_current-core"'
        headers['Cache-Control']='no-store, no-transform'
        self.assertEqual('"opaque_current-core"',smoke.require_core_validator(headers))

    def test_duplicate_cache_policy_is_not_an_unambiguous_core_response(self):
        headers=Message();headers['ETag']='"opaque_current-core"'
        headers['Cache-Control']='no-store';headers['Cache-Control']='no-store, no-transform'
        with self.assertRaises(ValueError):smoke.require_core_validator(headers)

    def test_encoded_core_response_is_not_the_selected_identity_representation(self):
        for encoding in ('gzip','br','deflate','zstd'):
            with self.subTest(encoding=encoding),self.assertRaises(ValueError):
                smoke.require_core_validator({'ETag':'"opaque"','Cache-Control':'no-store, no-transform','Content-Encoding':encoding})

    def test_transformed_missing_or_ambiguous_core_validator_fails_closed(self):
        for tag in (None,'W/"opaque"','bare','*','"a", "b"','""'):
            with self.subTest(tag=tag),self.assertRaises(ValueError):
                smoke.require_core_validator({'ETag':tag,'Cache-Control':'no-store, no-transform'})
        headers=Message();headers['ETag']='"a"';headers['ETag']='"b"';headers['Cache-Control']='no-store, no-transform'
        with self.assertRaises(ValueError):smoke.require_core_validator(headers)

    def test_cache_or_transformation_permission_is_not_a_valid_core_response(self):
        for control in ('no-store','no-transform','public, max-age=60','no-store, x-no-transform'):
            with self.subTest(control=control),self.assertRaises(ValueError):
                smoke.require_core_validator({'ETag':'"opaque"','Cache-Control':control})

    def test_inherited_process_boundary_requires_all_fixed_proc_fields(self):
        text='Uid:\t65533\t65533\t65533\t65533\nNoNewPrivs:\t1\nMax processes    300    300    processes\n'
        text+=''.join(name+':\t0000000000000000\n' for name in ('CapInh','CapPrm','CapEff','CapAmb'))
        self.assertTrue(smoke.managed_process_protected(text))
        for bad in (text.replace('300','34583'),text.replace('NoNewPrivs:\t1','NoNewPrivs:\t0'),
                    text.replace('CapAmb:\t0000000000000000','CapAmb:\t0000000000000001'),text.replace('65533','0')):
            self.assertFalse(smoke.managed_process_protected(bad))
        self.assertFalse(smoke.managed_process_protected(''))
        self.assertFalse(smoke.managed_process_protected('x'*65537))
    def test_container_exit_diagnostic_excludes_configuration_and_raw_error(self):
        data={'State':{'Running':False,'OOMKilled':True,'ExitCode':137,'Error':'synthetic-private-marker'},
              'RestartCount':0,'Config':{'Env':['synthetic-private-marker']}}
        self.assertEqual({'running':False,'oomKilled':True,'exitCode':137,'restarts':0},smoke.safe_container_state(data))
        self.assertNotIn('synthetic-private-marker',json.dumps(smoke.safe_container_state(data)))

    def test_full_client_observes_edge_deadline_without_changing_legacy_observer(self):
        self.assertEqual(45,smoke.request_observer_timeout(False))
        self.assertEqual(130,smoke.request_observer_timeout(True))

    def test_resource_observation_keeps_only_bounded_numeric_counters(self):
        result=smoke.safe_resource_observation('123\n456\noom 0\noom_kill 0\nusage_usec 789\nprivate synthetic-marker\n')
        self.assertTrue(result['available'])
        self.assertEqual(456,result['memoryPeakBytes'])
        self.assertEqual({'oom':0,'oom_kill':0,'usage_usec':789},result['counters'])
        self.assertNotIn('synthetic-marker',json.dumps(result))
        self.assertEqual({'available':False},smoke.safe_resource_observation('not-a-measurement'))
        self.assertEqual({'available':False},smoke.safe_resource_observation('1\n2\noom 0\n'))

    def test_startup_diagnostic_does_not_return_messages_or_sensitive_material(self):
        raw=('APPLICATION FAILED TO START\nCaused by: java.lang.IllegalArgumentException: '
            'Security email sender is not configured synthetic-private-marker\n'
            ' at org.notesknowledge.identity.ManagedEmailDeliveryAdapter.create(Adapter.java:123)\n'
            'Caused by: attacker.payload.ShouldNotBeCopied: synthetic-private-marker')
        result=smoke.safe_startup_diagnostics(raw)
        self.assertEqual(['java.lang.IllegalArgumentException'],result['causeClasses'])
        self.assertEqual(['org.notesknowledge.identity.ManagedEmailDeliveryAdapter.create'],result['applicationFrames'])
        self.assertNotIn('synthetic-private-marker',json.dumps(result))
        self.assertNotIn('attacker.payload',json.dumps(result))

    def wait(self,responses,timeout=2):
        clock=[0.0];calls=[];remaining=iter(responses)
        def request(path,authenticated):
            self.assertEqual('/_preview/ready',path);self.assertFalse(authenticated)
            value=next(remaining);calls.append(value)
            if isinstance(value,Exception):raise value
            return value,{},json.dumps({'status':'UP'}).encode()
        def pause(seconds):clock[0]+=seconds
        with contextlib.redirect_stdout(io.StringIO()):
            smoke.await_readiness(request,timeout,lambda:clock[0],pause)
        return calls

    def test_management_startup_statuses_are_not_success_or_immediate_failure(self):
        self.assertEqual([502,503,504,404,200],self.wait([502,503,504,404,200]))

    def test_transport_failure_can_recover_only_to_exact_up(self):
        self.assertEqual(2,len(self.wait([urllib.error.URLError('synthetic'),200])))

    def test_permanent_nonready_responses_exhaust_fixed_deadline(self):
        for code in (401,404,500,502,503,504):
            with self.subTest(code=code),self.assertRaisesRegex(RuntimeError,'deadline exceeded'):
                self.wait([code]*8)

    def test_200_down_or_extra_details_never_qualify(self):
        for body in ({'status':'DOWN'},{'status':'UP','details':{}},{}):
            with self.subTest(body=body),self.assertRaises(ValueError):
                smoke.await_readiness(lambda *args,**kwargs:(200,{},json.dumps(body).encode()))

    def test_malformed_200_body_never_qualifies(self):
        with self.assertRaises(ValueError):
            smoke.await_readiness(lambda *args,**kwargs:(200,{},b'invalid'))

    def test_redirect_or_runtime_exit_is_not_treated_as_startup(self):
        def failed(*args,**kwargs):raise RuntimeError('runtime exited')
        with self.assertRaisesRegex(RuntimeError,'runtime exited'):smoke.await_readiness(failed)

if __name__=='__main__':unittest.main()
