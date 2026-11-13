#!/usr/bin/env python3
"""Build immutable scene archives and a downloadable catalogue, without the APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
INDEX_URL = 'https://raw.githubusercontent.com/ghanta1996/GlideTV/main/scenes/index.json'

def build(tag, output):
    catalog = json.loads((ROOT / 'scenes/catalog.json').read_text())
    assert catalog['schemaVersion'] == 1
    if tag.startswith('scene-v') and tag != 'scene-v' + catalog['catalogVersion']:
        raise ValueError('Scene tag must match catalogVersion')
    if not re.fullmatch(r'(?:scene-)?v\d+\.\d+\.\d+', tag):
        raise ValueError('Invalid release tag')
    previous = {}
    try:
        with urllib.request.urlopen(INDEX_URL, timeout=15) as response:
            previous = json.load(response)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    old = {(p['id'], p['version']): p['sha256'] for p in previous.get('packs', [])}
    if previous and tuple(map(int, catalog['catalogVersion'].split('.'))) < tuple(map(int, previous['catalogVersion'].split('.'))):
        raise ValueError('Cannot publish an older catalogue')
    output.mkdir(parents=True, exist_ok=True)
    generated = {'schemaVersion': 1, 'catalogVersion': catalog['catalogVersion'], 'packs': []}
    hashes = []
    ids = set()
    for pack in catalog['packs']:
        assert re.fullmatch(r'[a-z0-9-]+', pack['id']) and pack['id'] not in ids
        ids.add(pack['id'])
        assert re.fullmatch(r'\d+\.\d+\.\d+', pack['version'])
        assert pack['kind'] in ('city', 'landscape')
        required = {'cloud.png'} | ({'city.png', 'city-night.png'} if pack['kind'] == 'city' else set())
        assert set(pack['assets']) == required
        archive = output / ('scene-' + pack['id'] + '-v' + pack['version'] + '.zip')
        info = {k: pack[k] for k in ('id', 'version', 'kind', 'title')}
        info['schemaVersion'] = 1
        with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_STORED) as z:
            files = {name: (ROOT / source).read_bytes() for name, source in pack['assets'].items()}
            files['pack.json'] = (json.dumps(info, sort_keys=True, indent=2) + '\n').encode()
            for name, content in sorted(files.items()):
                entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_STORED
                entry.create_system = 3
                entry.external_attr = 0o644 << 16
                z.writestr(entry, content)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        if old.get((pack['id'], pack['version']), digest) != digest:
            raise ValueError('Pack artwork changed: increase version for ' + pack['id'])
        entry = {k: v for k, v in pack.items() if k != 'assets'}
        entry.update(sha256=digest, size=archive.stat().st_size, url='https://github.com/ghanta1996/GlideTV/releases/download/' + tag + '/' + archive.name)
        generated['packs'].append(entry)
        hashes.append(digest + '  ' + archive.name)
    assert sum(bool(p.get('fallback')) for p in generated['packs']) == 1
    (output / 'scenes.json').write_text(json.dumps(generated, indent=2) + '\n')
    (output / 'SCENE-SHA256SUMS').write_text('\n'.join(hashes) + '\n')
    return generated

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/scenes')
    args = parser.parse_args()
    build(args.tag, args.output)
