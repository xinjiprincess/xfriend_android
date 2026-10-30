"""Isolated .qa Android end-to-end; credentials supplied at runtime, never bundled or logged."""
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SDK = Path('C:/Users/zhang/AppData/Local/Android/Sdk')
ADB = str(SDK / 'platform-tools/adb.exe')
PKG = 'com.singu.xfriend.mobile.qa'
key = os.environ.get('XFRIEND_QA_KEY', '')
if not key:
    raise SystemExit('Missing runtime QA credentials')
evidence = {'date': '2026-10-14', 'synthetic_only': True, 'environment': 'Android 35 emulator 720x1600', 'passed': False}
log = (ROOT / 'android-xfriend-0.8-qa.log').open('wb')
env = dict(os.environ, ANDROID_AVD_HOME=str(ROOT / '.qa-avd'))
env.pop('XFRIEND_QA_KEY', None)
emulator = subprocess.Popen([str(SDK / 'emulator/emulator.exe'), '-avd', 'XfriendSuiteQA', '-port', '5560', '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot', '-skin', '720x1600', '-gpu', 'swiftshader_indirect', '-feature', '-Vulkan', '-memory', '3072', '-cores', '4'], env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)

def adb(*args, timeout=30, input=None):
    return subprocess.run([ADB, '-s', 'emulator-5560', *args], input=input, capture_output=True, encoding='utf-8', errors='replace', timeout=timeout)
def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/xfriend-qa.xml')
    result = adb('shell', 'cat', '/sdcard/xfriend-qa.xml')
    return ET.fromstring(result.stdout)
def click(label):
    for _ in range(6):
        tree = ui()
        node = next((n for n in tree.iter('node') if n.get('text') == label or n.get('content-desc') == label), None)
        if node is not None:
            x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(1.5);return
        adb('shell','input','swipe','360','1300','360','560','350');time.sleep(1)
    raise RuntimeError('Control not found: '+label)
def shot(name):
    adb('shell', 'screencap', '-p', '/sdcard/xfriend-qa.png')
    adb('pull', '/sdcard/xfriend-qa.png', str(ROOT / f'docs/xfriend-0.8-{name}.png'))

try:
    deadline = time.monotonic()+160
    while time.monotonic()<deadline:
        if emulator.poll() is not None:raise RuntimeError('Emulator exited')
        try:
            if adb('shell','getprop','sys.boot_completed',timeout=5).stdout.strip()=='1':break
        except subprocess.TimeoutExpired:pass
        time.sleep(2)
    else:raise RuntimeError('Emulator boot timeout')
    installed=adb('install','-r',str(ROOT/'.artifacts/Xfriend-0.8-qa.apk'),timeout=60)
    if 'Success' not in installed.stdout:raise RuntimeError('QA install failed')
    adb('shell','pm','clear',PKG)
    adb('shell','wm','size','720x1600');adb('shell','wm','density','300')
    adb('shell','input','keyevent','82')
    adb('shell','am','start','-n',PKG+'/com.singu.xfriend.SalesFixtureActivity')
    time.sleep(8)
    credentials=json.dumps({'base_url':'https://api.xiaomimimo.com/v1','model':os.environ.get('XFRIEND_QA_MODEL','mimo-v2.6-flash'),'key':key})
    injected=adb('shell',f"run-as {PKG} sh -c 'umask 077; cat > files/qa-friend-model.json'",input=credentials)
    del credentials
    if injected.returncode:raise RuntimeError('Private QA configuration injection failed')
    adb('shell','am','force-stop',PKG)
    adb('shell','am','start','-n',PKG+'/com.singu.xfriend.SalesFixtureActivity','--ez','verifyRuntime','true')
    deadline=time.monotonic()+60
    while time.monotonic()<deadline:
        if 'FriendRunService' in adb('shell','dumpsys','activity','services',PKG,timeout=10).stdout:
            evidence['foreground_service_started']=True;break
        time.sleep(1)
    else:raise RuntimeError('Agent foreground service did not start')
    adb('shell','input','keyevent','3');evidence['sent_to_background']=True
    deadline=time.monotonic()+250
    while time.monotonic()<deadline:
        result=adb('shell','run-as',PKG,'cat','files/qa-friend-runtime-evidence.json',timeout=10)
        if result.returncode==0 and result.stdout.strip():
            evidence['runtime']=json.loads(result.stdout)
            if not evidence['runtime'].get('passed'):raise RuntimeError('Actual Android model/tool run failed')
            break
        time.sleep(2)
    else:raise RuntimeError('Agent run timed out')
    adb('shell','am','start','-n',PKG+'/com.singu.xfriend.SalesFixtureActivity')
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        tree=ui()
        if any(n.get('text')=='电脑执行' for n in tree.iter('node')):break
        if any(n.get('text')=='Close app' for n in tree.iter('node')):
            click('Close app');adb('shell','am','start','-n',PKG+'/com.singu.xfriend.SalesFixtureActivity')
        time.sleep(3)
    else:raise RuntimeError('Assistant UI did not load')
    shot('chat')
    click('任务记录');shot('tasks')
    click('返回');click('我的');shot('profile')
    click('模型配置');shot('model-settings')
    evidence['screens']=['chat','tasks','profile','model-settings']
    evidence['passed']=True
except Exception as error:
    evidence['failure_type']=type(error).__name__
    evidence['failure']=str(error)[:180]
    try:shot('failed')
    except Exception:pass
finally:
    # Configuration file is deleted by the fixture; clearing the isolated package also removes encrypted test credentials.
    try:adb('shell','pm','clear',PKG,timeout=20)
    except Exception:pass
    try:adb('emu','kill',timeout=10)
    except Exception:pass
    if emulator.poll() is None:emulator.terminate()
    log.close()
    (ROOT/'docs/qa-xfriend-runtime-0.8.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(evidence,ensure_ascii=False,indent=2))
if not evidence['passed']:raise SystemExit(1)
