#!/usr/bin/env python3
"""Build optionally, then start the standalone Mirror Spring Boot JAR."""
from pathlib import Path
import argparse
import os
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=8090)
parser.add_argument('--local-dir', type=Path, default=root / '.runtime/personnel-v1')
parser.add_argument('--init-local', action='store_true', help='Generate local accounts and synthetic catalog configuration once')
parser.add_argument('--reference-data', action='store_true', help='Add the workbook-inspired synthetic personnel dataset once (requires demo mode)')
parser.add_argument('--build', action='store_true', help='Install local Foundry dependencies and package Mirror with UI and tests')
args = parser.parse_args()
if args.build:
    subprocess.run(['mvn', '-q', '-f', 'foundry/pom.xml', '-pl',
                    'foundry-api,foundry-storage-jdbc,foundry-storage-memory', '-am', 'install', '-DskipTests'], cwd=root, check=True)
    subprocess.run(['mvn', '-q', 'package'], cwd=root, check=True)
jar = root / 'target/mirror.jar'
if not jar.exists():
    parser.error('Build first: python3 scripts/run-mirror.py --build --init-local')
java = shutil.which('java')
if java is None:
    parser.error('Java 21+ must be available on PATH')
local_directory = args.local_dir.resolve()
configuration = local_directory / 'application.properties'
if args.init_local and not configuration.exists():
    subprocess.run([java, '-Dloader.main=gov.mirror.app.LocalSetup', '-cp', str(jar),
                    'org.springframework.boot.loader.launch.PropertiesLauncher', str(local_directory)], check=True)
if not configuration.exists():
    parser.error('Configure accounts in --local-dir or use --init-local for local acceptance data')
os.chdir(root)
options = [java, '-jar', str(jar), '--server.port=' + str(args.port),
           '--spring.config.additional-location=' + configuration.as_uri()]
if args.reference_data:
    options.append('--mirror.foundry.seed-reference=true')
os.execv(java, options)
