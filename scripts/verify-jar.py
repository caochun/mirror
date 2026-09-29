#!/usr/bin/env python3
"""Verify a copied executable JAR from a directory without repository sources."""
from pathlib import Path
import argparse
import http.cookiejar
import json
import re
import shutil
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--jar', type=Path, default=root / 'target/mirror.jar')
args = parser.parse_args()
java = shutil.which('java')
if java is None:
    parser.error('Java 21+ is required')
with tempfile.TemporaryDirectory(prefix='mirror-jar-check-') as scratch:
    directory = Path(scratch)
    jar = directory / 'mirror.jar'
    shutil.copy2(args.jar, jar)
    local = directory / 'local'
    subprocess.run([java, '-Dloader.main=gov.mirror.app.LocalSetup', '-cp', str(jar),
                    'org.springframework.boot.loader.launch.PropertiesLauncher', str(local)], cwd=directory, check=True, stdout=subprocess.DEVNULL)
    with (directory / 'server.log').open('w') as log:
        process = subprocess.Popen([java, '-jar', str(jar), '--server.port=0',
                                    '--spring.config.additional-location=' + (local / 'application.properties').as_uri()],
                                   cwd=directory, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 30
            while True:
                output = (directory / 'server.log').read_text()
                match = re.search(r'Mirror: (http://127\.0\.0\.1:\d+)', output)
                if match:
                    base = match.group(1)
                    break
                if process.poll() is not None or time.monotonic() >= deadline:
                    raise RuntimeError('Standalone JAR failed to start:\n' + output[-4000:])
                time.sleep(0.1)
            client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            def get(path):
                return client.open(base + path, timeout=10).read()
            html = get('/').decode()
            assets = re.findall(r'(?:src|href)="(/assets/[^\"]+)"', html)
            assert len(assets) >= 2, 'Bundled frontend assets missing'
            for asset in assets:
                assert len(get(asset)) > 100
            session = json.loads(get('/api/session'))
            password = next(line.split('=', 1)[1] for line in (local / 'credentials.txt').read_text().splitlines() if line.startswith('admin='))
            login = urllib.request.Request(base + '/api/login', data=urllib.parse.urlencode({'username': 'admin', 'password': password}).encode(),
                                           headers={'X-CSRF-TOKEN': session['csrf']})
            with client.open(login, timeout=10) as response:
                assert response.status == 204
            session = json.loads(get('/api/session'))
            model = json.loads(get('/api/model'))
            assert model['schema']['namespace'] == 'mirror.domain'
            assert len(model['schema']['objectTypes']) == 37
            assert len(model['actions']) == 14
            request = urllib.request.Request(base + '/api/mirror/commands/RegisterManualPerson',
                    data=json.dumps({'name': 'Standalone acceptance', 'organization': 'org', 'note': 'Bundled Action execution'}).encode(),
                    headers={'Content-Type': 'application/json', 'X-CSRF-TOKEN': session['csrf'], 'Idempotency-Key': 'standalone-registration'})
            with client.open(request, timeout=10) as response:
                assert json.load(response)['success']
            assert json.loads(get('/api/mirror/people'))['total'] == 1
            assert len(json.loads(get('/api/events'))['outbox']) == 1
            assert not (directory / 'src').exists() and not (directory / 'web').exists()
            print('Standalone JAR passed: bundled UI/assets, login, 37 object types, 14 Actions and atomic registration')
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
