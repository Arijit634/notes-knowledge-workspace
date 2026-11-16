"""FAST supervisor regressions with synthetic files and controlled executables."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent


class FullBundleSupervisorTests(unittest.TestCase):
    def run_supervisor(self, missing=None, origin='https://preview.example.test', port='10000', resolver_exit=0, access_mode=None, idle_mode=None):
        bash = os.environ.get('BASH_FOR_TESTS') or shutil.which('bash')
        self.assertIsNotNone(bash)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            secret = root / 'secrets'
            secret.mkdir()
            values = {'runtime.properties': '# synthetic only\n', 'processing-disclosure.json': '{}',
                      'preview-origin': origin, 'preview-password-hash': '$2a$12$' + 'a' * 53,
                      'redis.conf': '# synthetic only\n'}
            for name, value in values.items():
                if name != missing:
                    (secret / name).write_text(value, newline='\n')
            fake = root / 'bin'
            fake.mkdir()
            for name, body in {
                'nginx': 'case "$*" in *" -t "*) exit 0 ;; esac\nexec sleep 60',
                'redis-server': 'exec sleep 60',
                'java': 'printf "%s\\n" "$@" > "$FIXTURE_ARGS"\nexit 0',
                'resolve-disclosure': 'printf "%s\\n" "$FIXTURE_DISCLOSURE"\nexit '+str(resolver_exit),
            }.items():
                path = fake / name
                path.write_text('#!/bin/bash\n' + body + '\n', newline='\n')
                path.chmod(0o755)
            script = (ROOT / 'start-full.sh').read_text()
            shutil.copyfile(ROOT / 'nginx.conf', root / 'nginx.conf')
            for original, replacement in (
                ('/etc/secrets', './secrets'), ('/tmp/nkw-edge', './edge'),
                ('/tmp/nkw-redis', './redis'), ('/app/nginx.conf', './nginx.conf'),
                ('/tmp/nkw-pdf-recovery', './pdf-recovery'),
                ('/tmp/nkw-video-recovery', './video-recovery'),
                ('/tmp/nkw-video-scratch', './video-scratch'),
                ('/jdk/bin/java', './bin/java'),
                ('/app/resolve-disclosure.sh', './bin/resolve-disclosure'),
            ):
                script = script.replace(original, replacement)
            executable = root / 'supervisor.sh'
            executable.write_text(script, newline='\n')
            env = os.environ.copy()
            env.pop("NKW_IDLE_DATABASE", None)
            if idle_mode is not None:env["NKW_IDLE_DATABASE"] = idle_mode
            env.pop('NKW_HOSTED_ACCESS_MODE', None)
            if access_mode is not None:
                env['NKW_HOSTED_ACCESS_MODE'] = access_mode
            bash_dir = Path(bash).resolve().parent
            env.update(PORT=port, FIXTURE_ARGS=(root / 'args').as_posix(),
                       FIXTURE_DISCLOSURE=(secret / 'processing-disclosure.json').resolve().as_posix(),
                       PATH=os.pathsep.join(str(p) for p in (fake, bash_dir, bash_dir.parent / 'usr/bin')))
            result = subprocess.run([bash, '--noprofile', '--norc', executable.as_posix()],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=15)
            args = (root / 'args').read_text() if (root / 'args').exists() else ''
            result.edge_config = (root / 'edge/nginx.conf').read_text() if (root / 'edge/nginx.conf').exists() else ''
            result.disclosure_path = (secret / 'processing-disclosure.json').resolve().as_posix()
            return result, args

    def test_default_and_explicit_private_modes_keep_the_password_gate(self):
        for mode in (None, 'private'):
            with self.subTest(mode=mode):
                result, args = self.run_supervisor(access_mode=mode)
                self.assertEqual(1, result.returncode)
                self.assertIn('auth_basic "Private preview";', result.edge_config)
                self.assertNotIn('auth_basic off;', result.edge_config)
                self.assertNotIn('NKW_HOSTED_ACCESS_MODE', args)

    def test_public_mode_removes_only_the_extra_edge_gate(self):
        private, private_args = self.run_supervisor()
        public, public_args = self.run_supervisor(access_mode='public')
        self.assertEqual(1, public.returncode)
        self.assertEqual(private.edge_config.replace('auth_basic "Private preview";', 'auth_basic off;'), public.edge_config)
        self.assertEqual(private_args.replace(private.disclosure_path, '<fixture-disclosure>'),
                         public_args.replace(public.disclosure_path, '<fixture-disclosure>'))
        self.assertIn('proxy_set_header Authorization "";', public.edge_config)
        self.assertIn('proxy_set_header X-Forwarded-Host preview.example.test;', public.edge_config)

    def test_invalid_modes_stop_before_any_constituent_launch(self):
        for mode in ('', 'PUBLIC', ' public', 'public ', 'public\n', 'public; auth_basic off;', 'false'):
            with self.subTest(mode=mode):
                result, args = self.run_supervisor(access_mode=mode)
                self.assertEqual(2, result.returncode)
                self.assertEqual('preview_access_mode_invalid\n', result.stderr)
                self.assertEqual('', args)
                self.assertEqual('', result.edge_config)

    def test_missing_disclosure_stops_before_any_application_launch(self):
        result, args = self.run_supervisor(missing='processing-disclosure.json')
        self.assertEqual(2, result.returncode)
        self.assertIn('preview_configuration_unavailable', result.stderr)
        self.assertEqual('', args)

    def test_missing_runtime_and_perimeter_inputs_stop_before_launch(self):
        for name in ('runtime.properties', 'preview-origin', 'preview-password-hash', 'redis.conf'):
            with self.subTest(name=name):
                result, args = self.run_supervisor(missing=name)
                self.assertEqual(2, result.returncode)
                self.assertEqual('', args)

    def test_unowned_or_insecure_origin_stops_before_launch(self):
        for origin in ('http://preview.example.test', 'https://preview.example.test/extra',
                       'https://user@preview.example.test', 'https://preview.example.test:8443'):
            with self.subTest(origin=origin):
                result, args = self.run_supervisor(origin=origin)
                self.assertEqual(2, result.returncode)
                self.assertEqual('', args)

    def test_external_port_cannot_expand_listener_surface(self):
        result, args = self.run_supervisor(port='8080')
        self.assertEqual(2, result.returncode)
        self.assertEqual('', args)

    def test_disclosure_admission_failure_stops_before_application_launch(self):
        result, args = self.run_supervisor(resolver_exit=2)
        self.assertEqual(2, result.returncode)
        self.assertEqual('', args)

    def test_idle_coordination_requires_exact_operator_selection(self):
        result, args = self.run_supervisor(idle_mode='true')
        self.assertEqual(1, result.returncode)
        self.assertIn('--spring.profiles.active=operations,private-preview,managed-preview,full-hosted,idle-hosted\n', args)
        for value in ('TRUE', '1', '', 'true,other'):
            with self.subTest(value=value):
                result, args = self.run_supervisor(idle_mode=value)
                self.assertEqual(2, result.returncode)
                self.assertEqual('preview_idle_mode_invalid\n', result.stderr)
                self.assertEqual('', args)

    def test_explicit_full_profile_disclosure_and_fail_closed_constituent_exit(self):
        result, args = self.run_supervisor()
        self.assertEqual(1, result.returncode)
        self.assertIn('preview_constituent_stopped', result.stderr)
        self.assertIn('--spring.profiles.active=operations,private-preview,managed-preview,full-hosted\n', args)
        self.assertIn('-Xmx160m\n', args)
        self.assertIn('-Dnkw.attachment-parser.profile=compact-v1\n', args)
        self.assertIn('-Dnkw.attachment-parser.classpath-profile=immutable-image-v1\n', args)
        self.assertIn('-Dnkw.media.service-boundary=landlock-managed-v1\n', args)
        self.assertIn('--knowledge.processing-disclosure.path=', args)
        self.assertIn('processing-disclosure.json\n', args)
        self.assertNotIn('preview-password', args)


if __name__ == '__main__':
    unittest.main()
