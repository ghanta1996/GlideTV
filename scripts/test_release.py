"""Release invariants: reproducible archives and immutable pack versions."""
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('packs', Path(__file__).with_name('package-scenes.py'))
packs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packs)
publisher_spec = importlib.util.spec_from_file_location('publisher', Path(__file__).with_name('publish-scene-index.py'))
publisher = importlib.util.module_from_spec(publisher_spec)
publisher_spec.loader.exec_module(publisher)

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

    def test_first_catalogue_release_keeps_legacy_pack_hashes(self):
        previous = self.build('one')
        missing = urllib.error.HTTPError(packs.INDEX_URL, 404, 'Not found', {}, None)
        with patch.object(packs.urllib.request, 'urlopen', side_effect=[missing, io.BytesIO(json.dumps(previous).encode())]) as request:
            self.assertEqual(packs.previous_catalogue(), previous)
        self.assertEqual([call.args[0] for call in request.call_args_list],
                         [packs.INDEX_URL, packs.LEGACY_INDEX_URL])

    def test_catalogue_errors_do_not_bypass_immutability(self):
        error = urllib.error.HTTPError(packs.INDEX_URL, 503, 'Unavailable', {}, None)
        with patch.object(packs.urllib.request, 'urlopen', side_effect=error) as request:
            with self.assertRaises(urllib.error.HTTPError):
                packs.previous_catalogue()
        self.assertEqual(request.call_count, 1)


class CataloguePublishing(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.catalogue = Path(self.temp.name) / 'scenes.json'
        self.catalogue.write_text('{"packs": []}')
        env = patch.dict('os.environ', {'GH_REPO': 'example/launcher'})
        env.start()
        self.addCleanup(env.stop)

    def test_existing_catalogue_updates_asset_without_a_main_commit(self):
        with patch.object(publisher.subprocess, 'run') as run:
            run.return_value.returncode = 0
            publisher.publish(self.catalogue)
        calls = [call.args[0] for call in run.call_args_list]
        self.assertEqual(calls, [
            ['gh', 'api', 'repos/example/launcher/releases/tags/scene-catalogue'],
            ['gh', 'release', 'upload', 'scene-catalogue', str(self.catalogue), '--clobber'],
            ['gh', 'release', 'edit', 'scene-catalogue', '--latest=false']])

    def test_bootstrap_never_replaces_latest_app_release(self):
        with patch.object(publisher.subprocess, 'run') as run, patch.object(publisher.subprocess, 'check_output', return_value='abc123\n'):
            run.return_value.returncode = 1
            run.return_value.stderr = 'HTTP 404: Not Found'
            publisher.publish(self.catalogue)
        command = run.call_args.args[0]
        self.assertEqual(command[:4], ['gh', 'release', 'create', 'scene-catalogue'])
        self.assertIn('--latest=false', command)
        self.assertEqual(command[command.index('--target') + 1], 'abc123')

    def test_permission_error_does_not_create_or_upload(self):
        with patch.object(publisher.subprocess, 'run') as run:
            run.return_value.returncode = 1
            run.return_value.stderr = 'HTTP 403: Forbidden'
            with self.assertRaisesRegex(RuntimeError, '403'):
                publisher.publish(self.catalogue)
        self.assertEqual(run.call_count, 1)

if __name__ == '__main__':
    unittest.main()
