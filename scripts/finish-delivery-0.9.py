"""Fill the delivery report from actual verification results and copy a readable bundle."""
import html
import json
import re
import shutil
from pathlib import Path

root = Path(__file__).resolve().parents[1]
docs = root/'docs'
report = json.loads((docs/'verification-0.9.json').read_text(encoding='utf-8'))
page = docs/'DELIVERY-0.9.html'
text = page.read_text(encoding='utf-8')
counts = '；'.join(f"{brand}：{data['tests']} 项单元测试通过，Lint {data['lint_errors']} 错误 / {data['lint_warnings']} 警告" for brand, data in report['products'].items())
text = re.sub(r'<section id="verification">.*?</section>', '<section id="verification"><h2>验证记录</h2><p>'+counts+'</p><p>两个产品均通过 assembleDebug。Android 35 模拟器 720×1600 下，独立 QA 包的深浅色导航、专家编辑返回、客户详情及复盘流程通过；未检测到应用 FATAL/ANR。</p><p>交付 APK 为 0.9.0（versionCode 9），同原开发签名，已排除 QA 页面及示例数据。共享模型密钥模式扫描通过。</p><p>本轮未连接用户实体手机、未执行真实模型端到端调用；模拟器界面检查不能代替厂家后台策略、200% 字体或手机—电脑真实模型验收。桌面源码有定向测试，完整桌面 make all 与新版桌面安装器未在本轮执行。</p><p><a href="verification-0.9.json">机器可读验证记录</a> · <a href="qa-ui-0.9.json">Android UI 验证详情</a></p></section>', text, flags=re.S)
images = []
for scenario in report['android_ui']['scenarios']:
    for shot in scenario['screenshots']:
        if shot['name'] in ('chat', 'experts', 'expert-editor', 'today', 'customer-detail', 'review'):
            images.append(shot['png'])
gallery = ''.join('<figure><img style="max-width:100%;height:auto;border-radius:14px" src="'+html.escape(Path(p).name)+'" alt="'+html.escape(Path(p).stem)+'"><figcaption>'+html.escape(Path(p).stem)+'</figcaption></figure>' for p in images)
text = re.sub(r'<section id="screens">.*?</section>', '<section id="screens"><h2>实际 Android 截图</h2><p>独立 QA 安装包，明确标注的示例数据。应用运行截图，不是设计效果图。</p><div class="grid">'+gallery+'</div></section>', text, flags=re.S)
text = text.replace('源码实现汇总 · 验收记录待填', '0.9.0 APK 已构建 · 模拟器界面验收通过')
text = text.replace('0.9 为本轮目标版本标识。本报告记录实现范围与交接边界，不作为安装包已发布、已安装或端到端验收通过的证明。', '两款开发签名 APK 已复制到桌面，可覆盖同签名旧版。真机、真实供应商与完整电脑连接验收边界见下文。')
text = text.replace('交付范围：0.9 源码实现与桌面交接汇总。验证和截图补齐后，依据实际证据更新发布与安装状态。', '交付：0.9.0 开发签名 APK、实际测试记录、Android 截图及电脑版对接说明。')
page.write_text(text, encoding='utf-8')
destination = Path.home()/'Desktop/Xfriend-Xsalers-0.9-交付说明'
destination.mkdir(exist_ok=True)
for name in ['DELIVERY-0.9.html', 'DESKTOP-HANDOFF-0.9.txt', 'verification-0.9.json', 'qa-ui-0.9.json']:
    shutil.copy2(docs/name, destination/name)
for p in images:
    shutil.copy2(root/p, destination/Path(p).name)
readme = root/'README.md'
old = readme.read_text(encoding='utf-8')
readme.write_text('# 0.9.0 · 手机简化版\n\n当前交付请看 [0.9 报告](docs/DELIVERY-0.9.html) 与 [验证记录](docs/verification-0.9.json)。Xfriend 对话／专家／我的；Xsalers 今日／客户／复盘／我的。保留独立应用和原升级签名。新电脑会话还需要配套桌面源码更新，见 [交接说明](docs/DESKTOP-HANDOFF-0.9.txt)。\n\n以下为此前版本的工程与部署背景；其中 0.7/0.8 验收记录不替代 0.9 结果。\n\n'+old, encoding='utf-8')
print(destination)
