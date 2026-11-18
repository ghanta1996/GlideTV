"""Exercise the release gate against real Git histories, without network/signing keys."""
import contextlib
import importlib.util
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('version', Path(__file__).with_name('check-version.py'))
version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version)


class VersionPolicy(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        root_patch = patch.object(version, 'ROOT', self.root)
        root_patch.start()
        self.addCleanup(root_patch.stop)
        self.git('init', '-q', '-b', 'main')
        self.git('config', 'user.name', 'Release tests')
        self.git('config', 'user.email', 'release-tests@example.invalid')
        self.git('config', 'commit.gpgsign', 'false')
        self.git('config', 'tag.gpgsign', 'false')
        self.write('src/App.java', 'original')
        self.write('CHANGELOG.md', '# Changelog\n\n## 4.5.0\n\n- Initial version.\n')
        self.set_version('4.5.0', 10)
        self.base = self.commit()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, stderr=subprocess.PIPE).decode().strip()

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)

    def set_version(self, name='4.5.1', code=11):
        self.write('AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
                   f'android:versionName="{name}" android:versionCode="{code}"/>')

    def bump(self):
        self.set_version()
        self.write('CHANGELOG.md', '# Changelog\n\n## 4.5.1\n\n- Updated app.\n')

    def commit(self):
        self.git('add', '.')
        self.git('commit', '-qm', 'Test change')
        return self.git('rev-parse', 'HEAD')

    def check(self, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return version.check(kwargs.pop('base', self.base), kwargs.pop('head', 'HEAD'), **kwargs)

    def test_docs_and_generated_catalogue_skip_release(self):
        for path in ['README.md', 'CONTRIBUTING.md', 'assets/live/PROMPTS.md', 'scenes/CHANGELOG.md',
                     'docs/setup.html', 'screenshots/home.png', 'LICENSE', 'NOTICE', 'guide.rst',
                     'guide.adoc', 'scenes/index.json']:
            self.write(path, 'documentation or generated data')
        self.commit()
        self.assertEqual(self.check(), {'release_needed': 'false'})

    def test_no_changes_skip_release(self):
        self.assertEqual(self.check(), {'release_needed': 'false'})

    def test_code_without_bump_fails_even_with_docs(self):
        self.write('src/App.java', 'changed')
        self.write('README.md', 'updated docs')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'BOTH a higher'):
            self.check()

    def test_non_documentation_paths_require_bump(self):
        for path in ['build.sh', '.github/workflows/release.yml', 'assets/remote/index.html',
                     'res/drawable/icon.xml', 'bridge/Helper.java', 'lib/dependency.jar',
                     'scenes/catalog.json', 'assets/live/cloud.png', 'tests/Test.java',
                     'scripts/release.py', 'new-module/unknown.file']:
            with self.subTest(path=path):
                self.git('reset', '--hard', self.base)
                self.git('clean', '-fdq')
                self.write(path, 'changed')
                self.commit()
                with self.assertRaisesRegex(ValueError, 'BOTH a higher'):
                    self.check()

    def test_both_version_fields_must_increase(self):
        for name, code in [('4.5.1', 10), ('4.5.0', 11), ('4.4.9', 12), ('4.5.1', 9)]:
            with self.subTest(name=name, code=code):
                self.set_version(name, code)
                self.commit()
                with self.assertRaisesRegex(ValueError, 'BOTH a higher'):
                    self.check()

    def test_numeric_semver_comparison(self):
        self.set_version('4.10.0', 11)
        self.write('CHANGELOG.md', '## 4.10.0\n\n- Release.\n')
        head = self.commit()
        self.assertEqual(self.check(), {'release_needed': 'true', 'tag': 'v4.10.0', 'sha': head})

    def test_missing_or_empty_release_notes_fail(self):
        self.set_version()
        for notes in ['## 4.5.0\n\n- Old notes.\n', '## 4.5.1\n\n## 4.5.0\n\n- Old.\n']:
            self.write('CHANGELOG.md', notes)
            self.commit()
            with self.assertRaisesRegex(ValueError, 'non-empty CHANGELOG'):
                self.check()

    def test_invalid_versions_fail(self):
        for name, code in [('4.5.1-beta', 11), ('04.5.1', 11), ('4.5', 11),
                           ('4.5.1', 0), ('4.5.1', 2100000001)]:
            with self.subTest(name=name, code=code):
                self.set_version(name, code)
                self.commit()
                with self.assertRaises(ValueError):
                    self.check()

    def test_release_deletion_or_rename_to_docs_still_requires_bump(self):
        self.git('mv', 'src/App.java', 'README.md')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'BOTH a higher'):
            self.check()

    def test_full_multi_commit_push_is_checked(self):
        self.write('src/App.java', 'changed')
        self.commit()
        self.write('README.md', 'docs in final commit')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'BOTH a higher'):
            self.check()
        self.bump()
        head = self.commit()
        self.assertEqual(self.check()['sha'], head)

    def test_conflicting_tag_fails_and_same_commit_retry_passes(self):
        self.git('tag', '-a', 'v4.5.1', '-m', 'Taken version')
        self.bump()
        head = self.commit()
        with self.assertRaisesRegex(ValueError, 'already belongs'):
            self.check()
        self.git('tag', '-d', 'v4.5.1')
        self.git('tag', '-a', 'v4.5.1', '-m', 'Correct version')
        self.assertEqual(self.check()['sha'], head)

    def test_manual_release_tag_must_match_manifest(self):
        self.assertEqual(self.check(tag='v4.5.0')['tag'], 'v4.5.0')
        with self.assertRaisesRegex(ValueError, 'must match versionName'):
            self.check(tag='v4.5.1')

    def test_pr_docs_do_not_include_new_code_on_base_branch(self):
        self.git('checkout', '-qb', 'docs')
        self.write('README.md', 'docs')
        docs_head = self.commit()
        self.git('checkout', '-q', 'main')
        self.write('src/App.java', 'new main code')
        new_base = self.commit()
        self.assertEqual(self.check(base=new_base, head=docs_head, pull_request=True),
                         {'release_needed': 'false'})

    def test_stale_pr_version_is_compared_to_current_base(self):
        self.git('checkout', '-qb', 'feature')
        self.write('src/App.java', 'feature code')
        self.bump()
        feature_head = self.commit()
        self.git('checkout', '-q', 'main')
        self.bump()
        new_base = self.commit()
        with self.assertRaisesRegex(ValueError, 'BOTH a higher'):
            self.check(base=new_base, head=feature_head, pull_request=True)

    def test_initial_push_can_release(self):
        self.assertEqual(self.check(base='0' * 40)['tag'], 'v4.5.0')


if __name__ == '__main__':
    unittest.main()
