"""FAST registry credential lifetime regression; controlled transport, no network."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest

WORKFLOW = Path(__file__).resolve().parents[2] / 'workflows' / 'managed-preview-release.yml'
BACKEND = WORKFLOW.with_name('backend-release.yml')
TARGETS = (
    (WORKFLOW, 'Publish the same qualified image without rebuilding', 'Verify publication provenance', '-private-preview'),
    (BACKEND, 'Publish the same image without rebuilding', 'Verify source digest workflow', ''),
)


def step(name, workflow=WORKFLOW):
    body = workflow.read_text().split('      - name: ' + name + '\n', 1)[1]
    return body.split('      - name: ', 1)[0]


class PublicationAuthTests(unittest.TestCase):
    def test_full_publication_requires_both_no_cost_and_codec_clearance(self):
        bash = os.environ.get('BASH_FOR_TESTS') or shutil.which('bash')
        command = step('Enforce the no-cost publication choice').split('        run: |\n', 1)[1]
        command = command.split('      - uses:', 1)[0]
        for publish, free, full, clearance, expected in (
            ('true','true','true','false',1),('true','false','true','true',1),
            ('true','true','true','true',0),('false','false','true','false',0),
            ('true','true','false','false',0)):
            env = dict(os.environ,PUBLISH=publish,FREE_VERIFIED=free,FULL_RUNTIME=full,REDISTRIBUTION=clearance)
            result = subprocess.run([bash,'--noprofile','--norc','-c','set -euo pipefail\n'+textwrap.dedent(command)],
                                    env=env,capture_output=True,timeout=10)
            with self.subTest(publish=publish,free=free,full=full,clearance=clearance):
                self.assertEqual(expected,result.returncode)

    def run_transport(self, push_exit, target):
        workflow, publish_name, _, suffix = target
        bash = os.environ.get('BASH_FOR_TESTS') or shutil.which('bash')
        self.assertIsNotNone(bash, 'Bash is required for the publication shell regression')
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            auth = root / 'synthetic-registry-auth'
            image = 'sha256:' + 'a' * 64
            (root / 'preview-image').write_text(image)
            (root / 'media-deployment-image').write_text(image)
            fake = root / 'bin'
            fake.mkdir()
            (fake / 'python3').write_text('#!/bin/bash\nexit 0\n')
            (fake / 'docker').write_text(textwrap.dedent('''\
                #!/bin/bash
                set -eu
                case "$1" in
                  login) cat >/dev/null; printf present > "$FAKE_AUTH" ;;
                  logout) rm -f "$FAKE_AUTH" ;;
                  manifest) exit 1 ;;
                  tag|pull) test -f "$FAKE_AUTH" ;;
                  push) test -f "$FAKE_AUTH"; exit "$FAKE_PUSH_EXIT" ;;
                  image)
                    case "$*" in
                      *RepoDigests*) printf '%s@sha256:%s\\n' "$FAKE_REPOSITORY" "$FAKE_DIGEST" ;;
                      *revision*) printf '%s\\n' "$GITHUB_SHA" ;;
                      *) printf '%s\\n' "$FAKE_IMAGE" ;;
                    esac ;;
                  *) exit 98 ;;
                esac
                '''))
            for path in fake.iterdir():
                path.chmod(0o755)
            env = os.environ.copy()
            bash_directory = Path(bash).resolve().parent
            env.update(PATH=os.pathsep.join(p.as_posix() for p in (fake, bash_directory, bash_directory.parent / 'usr' / 'bin')),
                RUNNER_TEMP=root.as_posix(), FAKE_AUTH=auth.as_posix(), FAKE_IMAGE=image,
                FAKE_DIGEST='b' * 64, FAKE_PUSH_EXIT=str(push_exit),
                EXPECTED_DOCKER=(fake / 'docker').as_posix(), EXPECTED_PYTHON=(fake / 'python3').as_posix(),
                GH_TOKEN='synthetic-test-input-with-no-authority', GITHUB_ACTOR='synthetic',
                GITHUB_REPOSITORY='Arijit634/notes-knowledge-workspace', GITHUB_SHA='c' * 40,
                VERSION='1.0.0', FAKE_REPOSITORY='ghcr.io/arijit634/notes-knowledge-workspace'+suffix,
                GITHUB_OUTPUT=(root / 'output').as_posix())
            publish = step(publish_name, workflow).split('        run: |\n', 1)[1]
            guard = 'set -euo pipefail\ntest "$(command -v docker)" -ef "$EXPECTED_DOCKER"\ntest "$(command -v python3)" -ef "$EXPECTED_PYTHON"\n'
            result = subprocess.run([bash, '--noprofile', '--norc', '-c', guard + textwrap.dedent(publish)],
                cwd=root, env=env, capture_output=True, timeout=15)
            self.assertEqual(push_exit, result.returncode, 'Controlled publication exited unexpectedly')
            self.assertTrue(auth.exists(), 'Registry credentials must remain for the attestation/verification steps')
            cleanup = step('Clear ephemeral registry credentials', workflow)
            self.assertIn('        if: always()\n', cleanup)
            command = cleanup.split('        run: ', 1)[1].strip()
            result = subprocess.run([bash, '--noprofile', '--norc', '-c', guard + command],
                cwd=root, env=env, capture_output=True, timeout=15)
            self.assertEqual(0, result.returncode)
            self.assertFalse(auth.exists(), 'Final cleanup must clear registry credentials')

    def test_success_keeps_credentials_until_attestations_then_cleans_up(self):
        for target in TARGETS:
            with self.subTest(workflow=target[0].name):
                self.run_transport(0,target)
                workflow=target[0].read_text()
                self.assertLess(workflow.index('      - name: '+target[2]),
                    workflow.index('      - name: Clear ephemeral registry credentials'))

    def test_failed_publication_still_has_unconditional_final_cleanup(self):
        for target in TARGETS:
            with self.subTest(workflow=target[0].name):self.run_transport(17,target)


if __name__ == '__main__':
    unittest.main()
