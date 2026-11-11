"""FAST publication regression: scanner-owned input is never overwritten."""
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('complete_sbom', Path(__file__).with_name('complete_sbom.py'))
sbom = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sbom)


class SbomTests(unittest.TestCase):
    def full_inputs(self, root, architecture='x86_64'):
        source=root/'scanner.json';final=root/'complete.json'
        source.write_text(json.dumps(dict(bomFormat='CycloneDX',metadata=dict(component={'bom-ref':'container'}),
            components=[{'bom-ref':'existing-java','name':'existing-java'}],
            dependencies=[dict(ref='container',dependsOn=['existing-java'])])))
        return source,final,{'/usr/local/bin/redis-server':'a'*64,
            '/usr/lib/'+architecture+'-linux-gnu/libopenh264.so.2.6.0':'b'*64}

    def test_full_bundle_preserves_scanner_input_and_binds_exact_shipped_hashes(self):
        for architecture in ('x86_64','aarch64'):
            with self.subTest(architecture=architecture),tempfile.TemporaryDirectory() as temporary:
                source,final,shipped=self.full_inputs(Path(temporary),architecture)
                original=source.read_bytes();sbom.complete_full(source,final,shipped)
                self.assertEqual(original,source.read_bytes())
                result=json.loads(final.read_text())
                self.assertEqual('existing-java',result['components'][0]['name'])
                self.assertEqual(3,len(result['components']))
                for component in result['components'][1:]:
                    properties={p['name']:p['value'] for p in component['properties']}
                    self.assertEqual(shipped[properties['nkw:shipped:path']],component['hashes'][0]['content'])
                    self.assertIn(component['bom-ref'],result['dependencies'][0]['dependsOn'])
                    self.assertIn('not invented vulnerability coverage',properties['nkw:scanner:coverage'])

    def test_full_bundle_rejects_missing_extra_and_malformed_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            source,final,shipped=self.full_inputs(Path(temporary))
            bad=[{},dict(shipped,extra='c'*64),dict(shipped,**{'/usr/local/bin/redis-server':'invalid'})]
            for values in bad:
                with self.subTest(values=values),self.assertRaises(ValueError):
                    sbom.complete_full(source,final,values)
                self.assertFalse(final.exists())

    def test_full_bundle_cannot_replace_input_or_prior_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            source,final,shipped=self.full_inputs(Path(temporary))
            with self.assertRaises(ValueError):sbom.complete_full(source,source,shipped)
            final.write_text('retained')
            with self.assertRaises(FileExistsError):sbom.complete_full(source,final,shipped)
            self.assertEqual('retained',final.read_text())

    def test_full_bundle_rejects_duplicate_component_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            source,final,shipped=self.full_inputs(Path(temporary))
            sbom.complete_full(source,final,shipped)
            with self.assertRaises(ValueError):sbom.complete_full(final,source.with_name('duplicate.json'),shipped)

    def test_read_only_scanner_output_produces_separate_complete_sbom(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'raw.json'
            final = Path(temporary) / 'final.json'
            original = b'{"bomFormat":"CycloneDX","specVersion":"1.6","components":[{"name":"existing","version":"1"}]}'
            source.write_bytes(original)
            source.chmod(stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH)
            try:
                sbom.complete(source, final)
                self.assertEqual(original, source.read_bytes())
                result = json.loads(final.read_text())
                self.assertEqual('CycloneDX', result['bomFormat'])
                self.assertEqual('1.6', result['specVersion'])
                self.assertEqual({'name': 'existing', 'version': '1'}, result['components'][0])
                self.assertEqual({'type': 'application', 'name': 'redis-server', 'version': '8.10.2',
                    'properties': [{'name': 'nkw:unmodified-source-sha256',
                        'value': 'ae6973de9b6ad8e6cf21b1e764abccab59200689af6aabce10d4cf6bbb2e253f'}]}, result['components'][1])
            finally:
                source.chmod(stat.S_IRUSR | stat.S_IWUSR)

    def test_scanner_input_cannot_be_selected_as_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'raw.json'
            source.write_text('{}')
            with self.assertRaises(ValueError):
                sbom.complete(source, source)
            self.assertEqual('{}', source.read_text())

    def test_existing_final_sbom_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'raw.json'
            final = Path(temporary) / 'final.json'
            source.write_text('{}')
            final.write_text('existing')
            with self.assertRaises(FileExistsError):
                sbom.complete(source, final)
            self.assertEqual('existing', final.read_text())

    def test_invalid_scanner_json_does_not_create_final_sbom(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / 'raw.json'
            final = Path(temporary) / 'final.json'
            source.write_text('invalid')
            with self.assertRaises(json.JSONDecodeError):
                sbom.complete(source, final)
            self.assertFalse(final.exists())


if __name__ == '__main__':
    unittest.main()
