"""Package verified APKs; retain evidence and do not include local credentials."""
import hashlib
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sdk = Path('C:/Users/zhang/AppData/Local/Android/Sdk/build-tools/34.0.0')
desktop = Path.home() / 'Desktop'
dist = root / 'dist'
dist.mkdir(exist_ok=True)
assert 'BUILD SUCCESSFUL' in (root / 'verify-0.9-final.log').read_text(encoding='utf-8-sig')
qa = json.loads((root / 'docs/qa-ui-0.9.json').read_text(encoding='utf-8-sig'))
assert qa.get('passed'), 'Android UI verification must pass'
report = {'version': '0.9.0', 'date': '2026-10-17', 'products': {}, 'android_ui': qa,
          'limitations': ['未在用户实体手机验收', '本轮没有执行真实供应商模型端到端验证', '电脑版桥接改动需另行更新电脑客户端', '云端Agent运行服务未部署']}
env = dict(os.environ, JAVA_HOME='C:/Program Files/Java/jdk-20')
for flavor, brand in [('xfriend', 'Xfriend'), ('xsalers', 'Xsalers')]:
    suites = [ET.parse(p).getroot() for p in (root / f'app/build/test-results/test{brand}DebugUnitTest').glob('TEST-*.xml')]
    tests = sum(int(s.get('tests', 0)) for s in suites)
    failures = sum(int(s.get('failures', 0)) + int(s.get('errors', 0)) for s in suites)
    assert tests >= 259 and failures == 0
    lint = ET.parse(root / f'app/build/reports/lint-results-{flavor}Debug.xml').getroot()
    assert not any(i.get('severity') in ('Error', 'Fatal') for i in lint)
    apk = dist / f'{brand}-Android-0.9.0-debug.apk'
    shutil.copy2(root / f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk', apk)
    badging = subprocess.check_output([str(sdk/'aapt.exe'), 'dump', 'badging', str(apk)], encoding='utf-8')
    assert f"package: name='com.singu.{flavor}.mobile'" in badging
    assert "versionCode='9'" in badging and "versionName='0.9.0'" in badging
    with zipfile.ZipFile(apk) as z:
        dex = b''.join(z.read(n) for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n))
        assert b'SalesFixtureActivity' not in dex
        assert not re.search(rb'sk-[a-z0-9]{40,}', dex)
    cert = subprocess.check_output([str(sdk/'apksigner.bat'), 'verify', '--print-certs', str(apk)], encoding='utf-8', env=env)
    signature = re.search(r'certificate SHA-256 digest: ([a-f0-9]+)', cert).group(1)
    assert signature == '49c46c8c258586bb93c3c75dc0025720fe101b9f86e6d7e70c5cf32552e1f6db'
    report['products'][brand] = {'tests': tests, 'failures': failures, 'lint_errors': 0,
        'lint_warnings': sum(i.get('severity') == 'Warning' for i in lint),
        'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'bytes': apk.stat().st_size,
        'upgrade_signature_verified': True, 'qa_fixture_excluded': True}
    shutil.copy2(apk, desktop/apk.name)
(root/'docs/verification-0.9.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report['products'], ensure_ascii=False, indent=2))
