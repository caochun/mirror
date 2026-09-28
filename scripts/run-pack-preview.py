#!/usr/bin/env python3
"""Build optionally, then serve the Pack explorer and its React bundle on loopback only."""
from pathlib import Path
import argparse
import os
import subprocess
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=8090)
parser.add_argument('--build', action='store_true', help='Build UI and run preview HTTP tests before starting')
args = parser.parse_args()
if args.build:
    subprocess.run(['npm', 'ci'], cwd=root / 'pack-explorer/web', check=True)
    subprocess.run(['npm', 'run', 'build'], cwd=root / 'pack-explorer/web', check=True)
    subprocess.run(['mvn', '-q', '-pl', 'pack-explorer', '-am', 'test', '-Dtest=PreviewServerTest',
                    '-Dsurefire.failIfNoSpecifiedTests=false'], cwd=root, check=True)
report = root / 'pack-explorer/target/surefire-reports/TEST-gov.mirror.explorer.PreviewServerTest.xml'
if not report.exists() or not (root / 'pack-explorer/web/dist/index.html').exists():
    parser.error('Build first: python3 scripts/run-pack-preview.py --build')
properties = {p.get('name'): p.get('value') for p in ET.parse(report).findall('./properties/property')}
paths = [root / 'pack-explorer/target/classes', *sorted((root / 'foundry').glob('*/target/classes'))]
paths += [Path(p) for p in properties['java.class.path'].split(os.pathsep) if p.endswith('.jar')]
classpath = os.pathsep.join(dict.fromkeys(str(p) for p in paths if p.exists()))
java = str(Path(properties['java.home']) / 'bin/java')
os.chdir(root)
os.execv(java, [java, '-cp', classpath, 'gov.mirror.explorer.PreviewServer',
               str(root / 'domain-pack'), str(root / 'pack-explorer/web/dist'), str(args.port)])
