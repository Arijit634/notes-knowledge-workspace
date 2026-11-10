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
