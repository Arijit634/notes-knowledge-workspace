"""FAST readiness contract regression; fake clock, no Docker or network calls."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
import urllib.error

spec=importlib.util.spec_from_file_location('managed_smoke',Path(__file__).with_name('smoke.py'))
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)

class ReadinessTests(unittest.TestCase):
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
