"""FAST Linux filesystem admission tests; synthetic files, no network or providers."""
from contextlib import contextmanager
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import uuid

HELPER = Path(__file__).with_name('resolve-disclosure.sh').resolve()


@unittest.skipUnless(os.name == 'posix', 'Managed-host filesystem admission requires Linux')
class DisclosureFileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Hosted-runner checkouts need not be traversable by the fixture user.
        # Keep the fixture under non-writable ancestors without changing checkout
        # permissions or trusting the world-writable /tmp directory.
        cls.fixture_parent = Path('/run') / ('nkw-disclosure-test-' + uuid.uuid4().hex)
        cls.fixture_context = None
        if os.geteuid() == 0:
            cls.fixture_context = tempfile.TemporaryDirectory(prefix='.disclosure-tests-', dir=Path.cwd())
            cls.fixture_parent = Path(cls.fixture_context.name)
            cls.fixture_parent.chmod(0o755)
        else:
            assert cls.fixture_parent.parent == Path('/run') and not cls.fixture_parent.exists()
            subprocess.run(['sudo', '-n', 'install', '-d', '-m', '755', '-o', str(os.getuid()),
                '-g', str(os.getgid()), str(cls.fixture_parent)], check=True, capture_output=True, timeout=10)

    @classmethod
    def tearDownClass(cls):
        if cls.fixture_context is not None:
            cls.fixture_context.cleanup()
        else:
            assert cls.fixture_parent.parent == Path('/run') and cls.fixture_parent.name.startswith('nkw-disclosure-test-')
            # Nonrecursive: unexpected contents are an error, never discarded.
            subprocess.run(['sudo', '-n', 'rmdir', '--', str(cls.fixture_parent)],
                check=True, capture_output=True, timeout=10)

    @contextmanager
    def fixture(self, kind='direct'):
        with tempfile.TemporaryDirectory(prefix='.disclosure-test-', dir=self.fixture_parent) as temporary:
            parent = Path(temporary)
            parent.chmod(0o755)
            root = parent / 'secrets'
            root.mkdir(mode=0o755)
            target = root / 'processing-disclosure.json'
            if kind == 'projected':
                version = root / '.version-v1'
                version.mkdir(mode=0o755)
                target = version / target.name
                (root / '..data').symlink_to(version.name, target_is_directory=True)
                (root / target.name).symlink_to('..data/' + target.name)
            target.write_bytes(b'{"synthetic":true}\n')
            target.chmod(0o444)
            if kind == 'projected':
                version.chmod(0o555)
            root.chmod(0o555)
            try:
                yield parent, root, target
            finally:
                # Only this newly allocated synthetic fixture is made removable.
                for directory, children, _ in os.walk(parent, topdown=False):
                    for child in children:
                        path = Path(directory) / child
                        if not path.is_symlink():
                            path.chmod(0o755)
                    Path(directory).chmod(0o755)

    def run_helper(self, *args):
        import pwd
        nobody = pwd.getpwnam('nobody')
        command = [shutil.which('bash'), '--noprofile', '--norc', '-s', '--', *(str(arg) for arg in args)]
        options = {}
        if os.geteuid() == 0:
            options.update(user=nobody.pw_uid, group=nobody.pw_gid, extra_groups=[])
        else:
            sudo = shutil.which('sudo')
            self.assertIsNotNone(sudo, 'Linux qualification needs its unprivileged fixture user')
            command = [sudo, '-n', '-u', '#' + str(nobody.pw_uid),
                       '-g', '#' + str(nobody.pw_gid), '--', *command]
        return subprocess.run(command, cwd='/', input=HELPER.read_text(),
            capture_output=True, text=True, timeout=10, **options)

    def assert_denied(self, result):
        self.assertEqual(2, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertEqual('preview_disclosure_configuration_unavailable\n', result.stderr)

    def test_readonly_direct_and_projected_snapshots_preserve_bytes(self):
        for kind in ('direct', 'projected'):
            with self.subTest(kind=kind), self.fixture(kind) as (_, root, target):
                before = target.read_bytes()
                result = self.run_helper(root)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(str(target.resolve()) + '\n', result.stdout)
                self.assertEqual('', result.stderr)
                self.assertEqual(before, target.read_bytes())

    def test_readonly_secret_root_alias_is_canonicalized(self):
        with self.fixture() as (parent, root, target):
            alias = parent / 'root-alias'
            alias.symlink_to(root.name, target_is_directory=True)
            result = self.run_helper(alias)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(str(target.resolve()) + '\n', result.stdout)

    def test_snapshot_cannot_escape_the_operator_secret_root(self):
        with self.fixture() as (parent, root, target):
            root.chmod(0o755)
            outside = parent / 'outside.json'
            target.rename(outside)
            outside.chmod(0o444)
            target.symlink_to('../outside.json')
            root.chmod(0o555)
            self.assert_denied(self.run_helper(root))

    def test_writable_file_secret_directory_or_ancestor_is_denied(self):
        for changed in ('file', 'root', 'ancestor'):
            with self.subTest(changed=changed), self.fixture() as (parent, root, target):
                {'file': target, 'root': root, 'ancestor': parent}[changed].chmod(0o666 if changed == 'file' else 0o777)
                self.assert_denied(self.run_helper(root))

    def test_missing_dangling_and_nonregular_snapshots_are_denied(self):
        for kind in ('missing', 'dangling', 'directory'):
            with self.subTest(kind=kind), self.fixture() as (_, root, target):
                root.chmod(0o755)
                target.unlink()
                if kind == 'dangling':
                    target.symlink_to('absent.json')
                if kind == 'directory':
                    target.mkdir(mode=0o555)
                root.chmod(0o555)
                self.assert_denied(self.run_helper(root))

    def test_invalid_inputs_and_control_characters_do_not_echo_paths(self):
        for args in ((), ('/',), ('relative-private-marker',), ('/', '/')):
            with self.subTest(arguments=len(args)):
                self.assert_denied(self.run_helper(*args))
        with self.fixture() as (parent, root, _):
            renamed = parent / 'private-marker\ninvalid'
            root.rename(renamed)
            self.assert_denied(self.run_helper(renamed))


if __name__ == '__main__':
    unittest.main()
