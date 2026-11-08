import base64
import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('preview_initialize',Path(__file__).with_name('initialize.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class PreviewSecretPreparationTest(unittest.TestCase):
    def arguments(self):
        return ['https://preview.example.test','syntheticnamespace','ap-mumbai-1','synthetic-bucket',
                'synthetic.apps.googleusercontent.com','synthetic-client-secret','synthetic-access','synthetic-storage']

    def test_invalid_origins_and_configuration_cannot_be_accepted(self):
        for origin in ['http://preview.example.test','https://localhost','https://preview.example.test/',
                       'https://preview.example.test:443','https://owner@preview.example.test',
                       'https://preview.example.test?key=synthetic','https://preview.example.test#fragment']:
            args=self.arguments();args[0]=origin
            with self.subTest(origin=origin),self.assertRaises(ValueError):module.configuration(*args)
        for index,value in [(1,'namespace/other'),(2,'untrusted.invalid/path'),(3,'../bucket'),
                            (4,'bad\nclient.apps.googleusercontent.com'),(5,'bad\rsecret'),(6,''),(7,'bad\nsecret')]:
            args=self.arguments();args[index]=value
            with self.subTest(field=index),self.assertRaises(ValueError):module.configuration(*args)

    def test_new_deployment_keys_are_separate_and_not_reused_from_the_operator_environment(self):
        first=module.configuration(*self.arguments());second=module.configuration(*self.arguments())
        values=[first[0][name] for name in module.KEYS]+list(first[1:])
        self.assertEqual(len(values),len(set(values)))
        self.assertTrue(all(len(base64.b64decode(value,validate=True))==32 for value in values))
        self.assertTrue(all(first[0][name]!=second[0][name] for name in module.KEYS))
        self.assertFalse(any('localhost' in value for value in first[0].values()))


if __name__=='__main__':unittest.main()
