"""Release invariants: reproducible archives and immutable pack versions."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('packs', Path(__file__).with_name('package-scenes.py'))
packs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packs)

class SceneReleases(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scenes').mkdir()
        (self.root / 'cloud.png').write_bytes(b'fixture-artwork')
        self.catalog = {'schemaVersion': 1, 'catalogVersion': '1.0.0', 'packs': [{'id': 'generic', 'version': '1.0.0', 'kind': 'landscape', 'title': 'Test', 'fallback': True, 'assets': {'cloud.png': 'cloud.png'}}]}
        self.write_catalog()
        self.root_patch = patch.object(packs, 'ROOT', self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def write_catalog(self):
        (self.root / 'scenes/catalog.json').write_text(json.dumps(self.catalog))

    def build(self, output, previous=None, tag='scene-v1.0.0'):
        with patch.object(packs.urllib.request, 'urlopen', return_value=io.BytesIO(json.dumps(previous or {}).encode())):
            return packs.build(tag, self.root / output)

    def test_archive_is_reproducible_and_pinned(self):
        first = self.build('one')
        second = self.build('two', first)
        self.assertEqual(first['packs'][0]['sha256'], second['packs'][0]['sha256'])
        self.assertIn('/scene-v1.0.0/', second['packs'][0]['url'])
        with zipfile.ZipFile(self.root / 'two/scene-generic-v1.0.0.zip') as z:
            self.assertEqual(set(z.namelist()), {'cloud.png', 'pack.json'})
            self.assertEqual(json.loads(z.read('pack.json'))['version'], '1.0.0')

    def test_changed_art_requires_new_pack_version(self):
        previous = self.build('one')
        (self.root / 'cloud.png').write_bytes(b'changed-artwork')
        with self.assertRaisesRegex(ValueError, 'increase version'):
            self.build('two', previous)
        self.catalog['packs'][0]['version'] = '1.0.1'
        self.catalog['catalogVersion'] = '1.0.1'
        self.write_catalog()
        updated = self.build('three', previous, 'scene-v1.0.1')
        self.assertNotEqual(previous['packs'][0]['sha256'], updated['packs'][0]['sha256'])

    def test_wrong_tag_and_catalogue_downgrade_fail(self):
        with self.assertRaisesRegex(ValueError, 'match catalogVersion'):
            self.build('one', tag='scene-v2.0.0')
        with self.assertRaisesRegex(ValueError, 'older catalogue'):
            self.build('two', {'catalogVersion': '2.0.0', 'packs': []})

    def test_missing_fallback_fails(self):
        self.catalog['packs'][0]['fallback'] = False
        self.write_catalog()
        with self.assertRaises(AssertionError):
            self.build('one')

if __name__ == '__main__':
    unittest.main()
