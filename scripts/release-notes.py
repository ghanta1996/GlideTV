#!/usr/bin/env python3
"""Extract the exact version's changelog entry for a GitHub release."""
import argparse
from pathlib import Path
import re
parser = argparse.ArgumentParser()
parser.add_argument('--tag', required=True)
parser.add_argument('--scenes', action='store_true')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
version = args.tag.removeprefix('scene-v' if args.scenes else 'v')
text = (root / ('scenes/CHANGELOG.md' if args.scenes else 'CHANGELOG.md')).read_text()
heading = ('Catalogue ' if args.scenes else '') + version
match = re.search(r'^## ' + re.escape(heading) + r'\n(.*?)(?=^## |\Z)', text, re.M | re.S)
if not match:
    raise SystemExit('A changelog entry is required for ' + args.tag)
print(match.group(1).strip())
