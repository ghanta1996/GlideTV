#!/usr/bin/env python3
"""Publish generated catalogue data without writing to the protected main branch."""
import argparse
import json
import os
from pathlib import Path
import subprocess

TAG = 'scene-catalogue'


def publish(catalogue):
    if catalogue.name != 'scenes.json':
        raise ValueError('The public catalogue asset must be named scenes.json')
    json.loads(catalogue.read_text())
    repo = os.environ['GH_REPO']
    current = subprocess.run(['gh', 'api', 'repos/' + repo + '/releases/tags/' + TAG],
                             capture_output=True, text=True)
    if current.returncode == 0:
        subprocess.run(['gh', 'release', 'upload', TAG, str(catalogue), '--clobber'], check=True)
        subprocess.run(['gh', 'release', 'edit', TAG, '--latest=false'], check=True)
    elif '404' in current.stderr:
        commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
        subprocess.run(['gh', 'release', 'create', TAG, str(catalogue), '--target', commit,
                        '--title', 'Glide scene catalogue', '--latest=false', '--notes',
                        'Current scene catalogue. Pack archives remain pinned to immutable version tags.'],
                       check=True)
    else:
        raise RuntimeError(current.stderr)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('catalogue', type=Path)
    publish(parser.parse_args().catalogue)
