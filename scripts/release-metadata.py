#!/usr/bin/env python3
"""Package a release APK and its metadata for the in-app updater."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tag', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    manifest = ET.parse(root / 'AndroidManifest.xml').getroot()
    ns = '{http://schemas.android.com/apk/res/android}'
    version = manifest.attrib[ns + 'versionName']
    if args.tag != 'v' + version:
        parser.error('Tag must match AndroidManifest.xml versionName: v' + version)
    output = root / 'build/release'
    output.mkdir(parents=True, exist_ok=True)
    apk = output / ('GlideTV-' + args.tag + '.apk')
    shutil.copyfile(root / 'build/GlideTV.apk', apk)
    stable_apk = output / 'GlideTV.apk'
    shutil.copyfile(apk, stable_apk)
    digest = hashlib.sha256()
    with apk.open('rb') as source:
        for chunk in iter(lambda: source.read(65536), b''):
            digest.update(chunk)
    checksum = digest.hexdigest()
    metadata = {'versionName': version, 'versionCode': int(manifest.attrib[ns + 'versionCode']), 'apk': apk.name, 'sha256': checksum}
    (output / 'update.json').write_text(json.dumps(metadata, indent=2) + '\n')
    helper = output / 'glide-memory-helper.dex'
    shutil.copyfile(root / 'build/bridge-dex/classes.dex', helper)
    helper_hash = hashlib.sha256(helper.read_bytes()).hexdigest()
    (output / 'SHA256SUMS').write_text(checksum + '  ' + apk.name + '\n' + checksum + '  ' + stable_apk.name + '\n' + helper_hash + '  ' + helper.name + '\n')
    subprocess.run(['python3', str(root / 'scripts/package-scenes.py'), '--tag', args.tag, '--output', str(output)], check=True)
    print('Prepared ' + apk.name)

if __name__ == '__main__':
    main()
