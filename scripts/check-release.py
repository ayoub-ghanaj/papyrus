#!/usr/bin/env python3
"""Reject inconsistent application versions before packaging or creating release drafts."""
import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--tag')
args = parser.parse_args()
version = ET.parse(root / 'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
if not re.fullmatch(r'\d+\.\d+\.\d+', version):
    raise SystemExit('Expected a stable major.minor.patch application version')
if args.tag and args.tag != 'v' + version:
    raise SystemExit(f'Tag {args.tag} does not match pom.xml version {version}')
for language in ('en', 'fr', 'ar'):
    resource = (root / f'src/main/resources/papyrus/messages_{language}.properties').read_text(encoding='utf-8')
    label = next((line for line in resource.splitlines() if line.startswith('version=')), '')
    if not label.endswith(' ' + version):
        raise SystemExit(f'Incorrect UI version in {language}')
if not (root / 'RELEASE_NOTES.md').read_text(encoding='utf-8').startswith(f'Papyrus {version}'):
    raise SystemExit('Release notes do not match the application version')
print(f'Version {version} is consistent')
