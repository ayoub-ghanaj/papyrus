#!/usr/bin/env python3
"""Install 1.0.0, create a fixture, upgrade, and check data. For disposable CI runners only."""
import hashlib
import os
from pathlib import Path
import platform
import subprocess
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
VERSION = ET.parse(ROOT / 'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
BASELINES = {
    'Windows': ('exe', 'ab9c16fa9891e26cb5aed271c9a4db5ab6156db50b1f79d5143f83da2df2c80b'),
    'Linux': ('deb', 'c691ef28b1265a3ea36e303d4e85658063ab85fe248b0ef85ecd0e4d3952fbe8'),
}

def run(*args):
    result = subprocess.run([str(arg) for arg in args], cwd=ROOT, timeout=300)
    if result.returncode not in (0, 3010):
        raise SystemExit(f'Installer check failed ({result.returncode}): {args[0]}')

def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or not os.environ.get('RUNNER_TEMP'):
        raise SystemExit('Run this only on a disposable GitHub Actions runner')
    system = platform.system()
    extension, expected = BASELINES[system]
    work = Path(os.environ['RUNNER_TEMP']) / 'papyrus-installer-check'
    work.mkdir(parents=True, exist_ok=True)
    baseline = work / f'Papyrus-1.0.0-{system.lower()}-x64.{extension}'
    url = f'https://github.com/ayoub-ghanaj/papyrus/releases/download/v1.0.0/{baseline.name}'
    with urllib.request.urlopen(url, timeout=60) as source, baseline.open('wb') as output:
        import shutil
        shutil.copyfileobj(source, output)
    with baseline.open('rb') as source:
        if hashlib.file_digest(source, 'sha256').hexdigest() != expected:
            raise SystemExit('Baseline installer checksum mismatch')
    current = ROOT / 'dist' / f'Papyrus-{VERSION}-{system.lower()}-x64.{extension}'
    if system == 'Windows':
        installed = work / 'installed' / 'Papyrus'
        def install(file):
            # OpenJDK's MSI wrapper passes these options through to msiexec.
            run(file, '/qn', '/norestart', f'INSTALLDIR={installed}', '/L*V', work / (file.stem + '.log'))
        java = installed / 'runtime/bin/java.exe'
        app = installed / 'app'
    else:
        installed = Path('/opt/papyrus')
        def install(file):
            run('sudo', 'apt-get', 'install', '-y', file)
        java = installed / 'lib/runtime/bin/java'
        app = installed / 'lib/app'
    data = work / 'library'
    def probe(mode, version):
        classpath = os.pathsep.join(map(str, [ROOT / 'target/test-classes', app / f'papyrus-{version}.jar', app / 'lib/*']))
        run(java, '--enable-native-access=ALL-UNNAMED', '-cp', classpath, 'papyrus.UpgradeProbe', mode, data)
    install(baseline)
    probe('seed', '1.0.0')
    install(current)
    probe('verify', VERSION)
    if system == 'Windows':
        run(current, 'uninstall')
    else:
        run('sudo', 'apt-get', 'remove', '-y', 'papyrus')
    if not (data / 'test1.db').is_file() or not (data / 'user-kept.txt').is_file():
        raise SystemExit('Uninstall removed library data')
    print('Fresh install, upgrade, migration, and uninstall data preservation passed')

if __name__ == '__main__':
    main()
