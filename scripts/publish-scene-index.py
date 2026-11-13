#!/usr/bin/env python3
"""Publish the released scene catalogue through the GitHub CLI's credentials."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('catalogue', type=Path)
args = parser.parse_args()
repo = os.environ['GH_REPO']
path = 'repos/' + repo + '/contents/scenes/index.json'
payload = {'message': 'Update downloadable scene catalogue', 'branch': 'main', 'content': base64.b64encode(args.catalogue.read_bytes()).decode()}
current = subprocess.run(['gh', 'api', path], capture_output=True, text=True)
if current.returncode == 0:
    payload['sha'] = json.loads(current.stdout)['sha']
elif '404' not in current.stderr:
    raise RuntimeError(current.stderr)
with tempfile.NamedTemporaryFile(mode='w', suffix='.json') as request:
    json.dump(payload, request)
    request.flush()
    subprocess.run(['gh', 'api', '-X', 'PUT', path, '--input', request.name, '--silent'], check=True)
