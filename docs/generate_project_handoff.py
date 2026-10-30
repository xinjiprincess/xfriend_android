# -*- coding: utf-8 -*-
"""生成 XFAMILY 手机端项目与设计交接文档（0.11.0）。

运行：
  python docs/generate_project_handoff.py
输出（同名覆盖，两处保持一致）：
  D:/build_apk/XFAMILY-项目设计与开发交接文档-2026-10-17.html/.txt
  D:/xfriend-suite/docs/XFAMILY-项目设计与开发交接文档-2026-10-17.html/.txt

构建 / QA 完成后只需更新下方 EVIDENCE 常量并重新运行，正文会同步；不要手改生成物。
"""
from pathlib import Path
import html
import base64
import hashlib
import re

root = Path('D:/xfriend-suite')
out = Path('D:/build_apk')
out.mkdir(exist_ok=True)
name = 'XFAMILY-项目设计与开发交接文档-2026-10-17'

VERSION = '0.11.0'
VERSION_CODE = 11
DATE = '2026-10-17'

# ---- 证据常量：构建 / QA 完成后更新这里，再重新生成本文档 ----
# 若 build-0.11.log 与两个 APK 已存在，下面的脚本会自动回填结果/耗时/大小/SHA-256。
EVIDENCE = {
    'build_log': 'build-0.11-final.log',
    'build_extra_log': 'build-0.11-b.log（首轮修复后成功构建）；build-0.11-a.log（首次构建失败，保留排查）',
    'qa_log': 'build-0.11-qa.log',
    'qa_final_log': 'build-0.11-qa-final.log',
    'qa_final_fixture': '修复后 QA fixture：Xfriend-0.11-qa.apk 26,468,093 bytes / SHA-256 C6EAB964…FBB44；Xsalers-0.11-qa.apk 26,468,205 bytes / SHA-256 0B53FF5F…41DBF8（.artifacts/0.11-qa/，.qa 包名，非交付物）',
    'build_result': 'BUILD SUCCESSFUL',
    'build_time': '9m 46s',
    'build_tasks': '77 项（15 executed, 62 up-to-date）',
    'build_command': 'gradlew --no-daemon --max-workers=4 -Pkotlin.compiler.execution.strategy=in-process :app:assembleXfriendDebug :app:assembleXsalersDebug --console=plain',
    'apk_xf': 'D:/build_apk/XFriend-Android-0.11.0-debug.apk',
    'apk_xs': 'D:/build_apk/XSalers-Android-0.11.0-debug.apk',
    'apk_xf_bytes': '26,451,921 bytes · SHA-256 46693c8f2a57bbc1161f43318533235b470234b67b31f38ff2dd97ec2845457e',
    'apk_xs_bytes': '26,451,941 bytes · SHA-256 e4c4a9a7c36124f0e55e64b725272476bb2291114cca230a3f3d2bed323ca0c6',
    'qa_report': 'docs/qa-0.11/report.md',
    'qa_result': '首轮 P0=1/P1=1/P2=1 已修复；最终回归通过（report.md §11：XSalers 5 tab 可切换、分段 1dp 细边框、XFriend 底栏 80dp、0 FATAL/ANR）',
    'qa_device': 'Android API 35 模拟器（AVD XfriendSuiteQA，wm size 1080x2400 / 420dpi）',
    'tests': '单元测试已通过：两个 flavor 合计 574 项、0 failures / 0 errors（test-0.11.log，BUILD SUCCESSFUL in 5m 11s）',
    'lint': '未运行 0.11 lint（可用 build.ps1 执行完整 test+lint 链）',
}

# 自动回填构建日志与 APK 证据（文件不存在时保留占位符）
_build_log = root / EVIDENCE['build_log']
if _build_log.is_file():
    # Gradle 日志可能是 PowerShell Tee 生成的 UTF-16 LE，也可能是 UTF-8，按 BOM/空字节判断。
    _raw = _build_log.read_bytes()
    if _raw[:2] in (b'\xff\xfe', b'\xfe\xff') or b'\x00' in _raw[:200]:
        _log_text = _raw.decode('utf-16', errors='replace')
    else:
        _log_text = _raw.decode('utf-8', errors='replace')
    if 'BUILD SUCCESSFUL' in _log_text:
        EVIDENCE['build_result'] = 'BUILD SUCCESSFUL'
    elif 'BUILD FAILED' in _log_text:
        EVIDENCE['build_result'] = 'BUILD FAILED（见日志）'
    _m = re.search(r'BUILD (?:SUCCESSFUL|FAILED) in ([0-9a-zA-Z ]+)', _log_text)
    if _m:
        EVIDENCE['build_time'] = _m.group(1).strip()
    _m = re.search(r'(\d+) actionable tasks: ([^\r\n]+)', _log_text)
    if _m:
        EVIDENCE['build_tasks'] = f'{_m.group(1)} 项（{_m.group(2).strip()}）'
for _key, _path in (('apk_xf', EVIDENCE['apk_xf']), ('apk_xs', EVIDENCE['apk_xs'])):
    _p = Path(_path)
    if _p.is_file():
        _data = _p.read_bytes()
        EVIDENCE[_key + '_bytes'] = f'{len(_data):,} bytes · SHA-256 {hashlib.sha256(_data).hexdigest()}'

BUILD_BADGE = 'APK 已编译 · 模拟器 QA 回归通过（真机未验收）'

sections = []


def add(title, text):
    sections.append((title, text.strip()))


add('01 项目概况与交付状态', f'''XFAMILY 包含两个独立安装的 Android 应用，复用一个 Kotlin / Jetpack Compose 工程。当前交付版本为 {VERSION}（versionCode {VERSION_CODE}），日期 {DATE}。
XFriend：个人助手、专家、团队、资源与任务；面向通用工作。
XSalers：沟通采集与复盘、客户画像、跟进、商机与个人 CRM；面向个人销售。
两款产品保留各自图标及品牌，使用统一的 0.11 设计系统（暖纸画布、白色内容卡、单一主色、亮/暗主题、统一线性图标与 Xf 组件层）。

本次交付（0.11.0）：
• 两个独立开发签名 APK（XFriend / XSalers），版本名 0.11.0、versionCode 11。
• 共享设计系统：XfDesign（令牌/主题）、XfComponents（28 个组件）、XfIcons（60 个线性图标）、XfAppearance（主题与 5 色板）。
• 两 App 的全部主要页面、详情页与弹窗迁移到 Xf 组件；底栏与信息架构保持不变。
• 设计规范、落地说明、交付说明、QA 证据与项目交接文档。

当前验证状态：
• 构建：{EVIDENCE['build_result']}（日志 {EVIDENCE['build_log']}）。
• 模拟器 QA：{EVIDENCE['qa_result']}（报告 {EVIDENCE['qa_report']}，证据目录 docs/qa-0.11/）。
• 单元测试 / lint：{EVIDENCE['tests']}；{EVIDENCE['lint']}。
• 真机验收：未执行。模拟器截图与设计稿均不能代替真实设备、真实账号与真实模型链路验收。

本次未发布：服务器部署、电脑版安装包、生产签名安装包及应用商店版本。
本文是当前源码、构建日志与本地文档的交接快照，不是线上服务器健康报告。历史文档涉及旧版本状态时，以 0.11 交付说明和实际源码为准。''')

add('02 项目位置与目录总览', r'''D:\xfriend-suite\                    手机端主工程
  app\                               两个 Android product flavor，共享主体源码
    src\main\java\com\singu\xfriend\   页面、Xf 设计系统、运行时、CRM、同步、导入与任务
    src\main\res\                     图标、图片、Android 资源
    build.gradle.kts                  应用版本、依赖和 flavor 配置
    build\outputs\apk\                Gradle 生成的安装包
  branding\                          品牌与图标素材
  desktop-bridge\                    独立 Python 连接桥及导出工具
  trial-gateway\                     试用模型代理、额度与持久化复盘服务
  docs\                              方案、接口契约、交接记录、设计与 QA 证据
    design\                         0.11 设计规范与概念参考截图
      redesign-concept-android-spec.md   概念稿逐项提取的 Android 设计规范
      screens\                           概念稿 23 张参考截图（非真机）
    qa-0.11\                        0.11 模拟器 QA 环境、截图与报告
      prep.md / report.md             环境准备 / 验收报告
    XFRIEND-REDESIGN-0.11.md        0.11 视觉重塑落地说明
    XFAMILY-0.11-交付说明.txt        0.11 交付快照
    DELIVERY-0.11.html              0.11 交付报告
    verification-0.11.json          0.11 构建/QA 证据结构化记录
    README.md                       文档索引（当前 0.11 / 历史版本）
  scripts\                           打包、交付与历史辅助脚本
    release-0.11.ps1                 0.11 构建与交付脚本
  gradle\                            Gradle Wrapper
  build.gradle.kts                   AGP / Kotlin 插件版本
  settings.gradle.kts                工程与仓库设置
  gradle.properties                  构建参数
  local.properties                   本机 Android SDK 路径，不应跨机器直接照搬
  gradlew / gradlew.bat               构建入口
  build.ps1                          完整验证入口（test + assemble + lint）
  build-0.11.log                     0.11 正式构建证据
  build-0.11-qa.log                  0.11 QA fixture 构建证据（若执行）
  dist\ / .artifacts\                历史交付与 QA 产物
  .gradle\ / .qa-avd\                缓存、模拟器环境，不是产品源码

D:\octop\                            电脑版：Python API、React 控制台、Go 桌面壳
  dashboard\                         React 前端源码
  src\octop\                         Python 后端
  desktop\src\                       Go / Wails 桌面及连接桥
  docs\                              电脑版设计与接口参考
D:\xfriend-activation\               账号、激活和同步服务
  src\ / tests\ / deploy\ / scripts\
  SYNC-API.md / PHASE5-DEPLOY.md       同步与部署说明
D:\build_apk\                        面向用户的安装包和交接文件

注意：不要编辑 D:\octop\src\octop\dashboard 中的构建产物；电脑版前端应修改 dashboard 源码。手机端 UI 位于独立 Android 工程，不在电脑版 React 目录内。''')

add('03 技术栈与系统边界', '''Android：Kotlin 1.9.22、Android Gradle Plugin 8.5.0、Jetpack Compose、Material 3。
平台：minSdk 26（Android 8+），compileSdk / targetSdk 34；Java / Kotlin 字节码目标 17。
UI：Compose compiler 1.5.8、Compose BOM 2024.06.00、Haze 0.7.3 毛玻璃、Markwon 4.6.2 富文本。
网络与并发：OkHttp 4.12.0、Kotlin coroutines 1.8.1。
数据与媒体：Room 2.6.1、Android Keystore、Media3 ExoPlayer 1.4.1。
试用服务：Python 3.12+、aiohttp、SQLite WAL；没有额外消息队列。
电脑版：Python / FastAPI / asyncio / Octop harness、React / TypeScript / Vite、Go / Wails 桌面外壳。

0.11 UI 改造没有新增第三方依赖：沿用现有 Compose / Material3 / Haze / material-icons-extended（少量残留图标仍引用）与 Markwon。

系统调用关系：
Android 页面 → ViewModel / 业务协调 → 本地数据与任务 → 手机工具编排 + 模型 API
                                       ├→ 电脑连接桥 → 电脑版智能体与工具
                                       ├→ 试用网关 → 额度管理、转写与复盘
                                       └→ 账号服务 → 登录、授权与配置同步

“在手机运行智能体”指手机编排模型请求与允许的本机工具，并不表示手机内置离线大模型，也不表示完整移植电脑版 Python 运行时。
账号云同步、试用推理服务、云端智能体执行是三件不同的事。当前云端智能体执行未启用，不能因登录成功就显示云端可执行。''')

add('04 视觉与交互规范（0.11 重塑版）', f'''设计来源：D:\\octop\\docs\\design\\redesign-concept.html（概念稿 v1）；实施基线：docs/design/redesign-concept-android-spec.md。
落地代码：app/src/main/java/com/singu/xfriend/XfDesign.kt、XfComponents.kt、XfIcons.kt、XfAppearance.kt。

色彩（亮 / 暗）：
• 画布 #F6F5F1 / #0E0F12；表面 #FFFFFF / #181A1F；表面-2 #F7F6F2 / #1E2026；表面-3 #EEECE6 / #24262D。
• 分隔线 #E5E2DA / #282A31；强调线 #D6D2C7 / #373A43。
• 主文字 #1B1C1F / #F1F2F4；次要 #57585F / #AAAEB8；元信息 #8C8D94 / #7B7F89；装饰 #B6B7BD / #565A64。
• 语义色：成功 #1F9D6B / #37C08A；警告 #C27C0E / #E0A33A；危险 #D5443B / #F06B62；信息 #3B82F6 / #6FA8FF。

主色（5 组，设置页可切换；默认 XFriend 靛蓝、XSalers 紫罗兰）：
• 靛蓝 #4F5DF0（solid #4351E8）；青绿 #0E9384（solid #0B7F73）；琥珀 #B26B00（solid #996000）；玫瑰 #D94F6B（solid #C74059）；紫罗兰 #7055DE（solid #6247D6）。
• 深色下自动使用提亮 accent 与半透明 soft/line；不单独维护页面色。

排版：Display 28、页面标题 24、区块标题 17、卡片标题 16、正文 15、辅助 13、元信息 11.5、标签 12、大写小标 10.5、等宽 12.5、底栏标签 11（单位 sp）；字体使用系统字体，数字/命令用 Monospace。

尺寸：页边距 20dp；卡片内边距 16dp；区块间距 24dp；卡片圆角 14dp、Hero 18dp、弹窗 16dp、控件/按钮 10dp；主要触控区不小于 48dp；主按钮视觉高 44dp、小按钮 34dp；输入框 46dp；顶栏 56dp；底栏内容高 80dp + 系统导航栏 insets。

壳层与组件：
• 顶栏 XfTopBar：返回/品牌图标/标题/副标题/右侧动作；状态栏 insets。
• 底栏 ProductBottomBar：保留 Haze 毛玻璃与系统 insets，选中项为柔和主色胶囊；XFriend 4 项、XSalers 5 项不变。
• 页面内组件统一走 XfComponents（XfCard / XfButton / XfStatusChip / XfListRow / XfTextField / XfSegmented / XfDialog / XfEmptyState / XfBanner 等）。
• 外观：XfAppearance 提供跟随系统 / 浅色 / 深色 × 5 色板；本机 SharedPreferences（xf_appearance）保存，不进入账号同步。

交互原则：
• 每个区域只保留一个主操作；次操作用 ghost/次要按钮，危险操作用危险色。
• 发送前检查缺少的模型、工具与执行条件，保留用户草稿并给出对应入口。
• 运行中提供文本流、简短动作和停止入口；同一会话遵守单任务规则。
• 远程断连显示最后同步状态，不能直接把电脑任务改成失败。
• 普通智能体显示动作状态；音频与导入任务显示预计百分比，真实完成前不显示 100%，不展示分段数量。
• AI 内容保存到客户档案前允许核对与修改；截图中缺失字段不覆盖原有资料。
• 确认按钮写具体动作，例如“确认保存”；设计图的邮件发送只属于未来能力示例。
• 概念稿的群聊/会议/决策档案/桌面侧栏不实现，不加入无业务入口。
• 设计图用于说明目标体验，不是真机验收截图；图中的客户、在线状态和数据不是交付应用中的演示数据。''')

add('05 视觉重塑落地（0.11）', f'''本次为 UI / 主题层改造，业务逻辑未变。改动集中在约 32 个 UI/主题文件；SalesViewModel、AgentRuntime、CloudAccount、SalesApi、FriendRemoteProtocol、SalesCloudSyncEngine、SalesStore 等业务文件未改。

一、令牌与主题
• XfDesign.kt：XfColors（亮/暗）、XfAccentColors、XfAccent（indigo/teal/amber/rose/violet）、XfThemeMode（SYSTEM/LIGHT/DARK）、XfPalettes、XfType、XfDimens、XfShapes、XfElevation、XfTheme。
• XfTheme 同时映射 Material3 colorScheme/typography/shapes，并设置状态栏与导航栏颜色；旧页面使用 MaterialTheme 也能继承新比例。
• 页面禁止直接写 Color(0x...) 与自定义字号；新增 UI 必须从 Xf 令牌取色/字。

二、组件层（XfComponents.kt，28 个）
XfCard、XfSurface、XfSectionHeader、XfSectionLabel、XfPageHeader、XfTopBar、XfBrandTile、XfStarMark、XfButton、XfIconButton、XfLink、XfDot、XfStatusChip、XfChip、XfAvatar、XfIconTile、XfListRow、Hr、XfSearchField、XfTextField、XfSegmented、XfProgressBar、XfBanner、XfEmptyState、XfDialog、XfKeyValueRow、XfSwitch、XfLabeledText；XfAppearance.kt 另提供 XfAppearancePanel（主题模式 + 5 色板 + 预览）。
主要调用量（grep 计数，含组件定义附近示例）：XfButton 158、XfCard 56、XfIconTile 46、XfStatusChip 42、XfBanner 39、XfDialog 32、XfTextField 31、XfListRow 26、XfEmptyState 25、XfKeyValueRow 25、XfSegmented 15、XfAvatar 14、XfProgressBar 12、XfChip 11、XfSearchField 10、XfSwitch 4。

三、图标
• XfIcons.kt 手工实现 60 个 ImageVector（24 视窗、1.75 stroke、round cap/join），覆盖导航、状态、文件、模型、审批、导出等。
• 导航 20dp、行内 17dp、强调 24dp；颜色继承 currentColor/主色。
• 少数 Material 图标仍保留（约 17 处，如 Visibility/CloudOff/Smartphone），未完全清零。

四、页面范围
XFriend：对话、工作台、资源、我的、外观，以及模型配置、平台设备、迁移、任务中心、电脑会话、连接、同步、市场、蓝图、设计专家等既有详情。
XSalers：今日、客户（含客户档案/CRM 概览/编辑）、复盘（含记录详情/任务中心）、专家（含 AI 设计/编辑/市场）、我的（含外观）、模型配置、云同步、迁移等。
两 App 的 32 处确认/编辑弹窗统一为 XfDialog；6 处 ModalBottomSheet 保留 M3 壳，内容使用 Xf 组件。

五、与概念稿的差异（有意）
• 桌面侧栏、三栏聊天、上下文/成员栏、创建预览栏不迁移；改为顶栏 + 底栏 + 详情页 + ModalBottomSheet。
• 概念稿 4 套 accent 落地为 5 套（新增 XSalers 品牌紫）；XFriend 默认概念靛蓝（旧品牌蓝 #5267E8 不再作 UI 强调色，图标资产保留）。
• 底栏最终内容高 80dp（首轮 64dp 已按 QA P2 修复），保留 Haze 与选中胶囊。
• 群聊/会议/决策档案/设计系统页不实现（无业务）；不新增假入口。
• hover 行尾操作改为常驻/菜单；过渡以 Compose 默认与 180ms Crossfade 为主，不引入动画库。

六、验证与边界
• 设计规范与概念截图：docs/design/redesign-concept-android-spec.md、docs/design/screens/（23 张，桌面参考，非真机）。
• 构建证据：{EVIDENCE['build_log']}（{EVIDENCE['build_result']}）。
• 模拟器 QA：{EVIDENCE['qa_report']}（{EVIDENCE['qa_result']}）。
• {EVIDENCE['tests']}；{EVIDENCE['lint']}；未做真机验收，未做无障碍/TalkBack/对比度实测。''')

add('06 十六个关键界面的设计与实施（0.11 现状）', '''第 01 组：XFriend 对话与协作
01 对话首页：小型欢迎区、常用任务、最近对话、助手与执行位置选择；已迁移到 Xf 组件与暖纸画布。
02 任务对话：回答流、当前动作、执行位置、停止、结果与下一条草稿；消息/工具行/结果卡统一 Xf 样式。
03 专家工作台：搜索、用途、手机可运行 / 需要电脑 / 待配置模型；详情中管理；卡片与状态统一。
04 团队协作：成员与顺序协作。并行研究团队尚未实现；0.11 只改视觉。

第 02 组：XFriend 资源与可靠运行
05 资源中心：技能、知识、工作区文件；先预览后导入，文本预览与保存。
06 任务中心：进行中、待处理、已完成，展示结果与恢复入口。
07 我的与连接：账号同步、模型、电脑连接、云端状态、诊断、迁移、外观。
08 执行位置：手机和电脑能力分开；云端不可用。手机当前会话不能无缝迁移到电脑，电脑任务为独立会话。

第 03 组：XSalers 客户与销售工作
09 今日工作台：今日跟进、待分析沟通、进行中商机；录音、截图、文字入口。
10 客户与 CRM：统一客户、待办、商机、联系计划；保留客户导入与筛选 CSV 导出。
11 客户档案：概览、沟通、待办、商机、资料；画像、偏好、阶段可编辑，历史录音与分析归属客户。
12 销售专家：专家、团队、技能、知识，支持 AI 设计专家和导入，展示实际运行条件。

第 04 组：XSalers 沟通记录与行动确认
13 录音复盘：关联客户、分析方案、录音暂停与结束、执行条件、材料范围。
14 复盘结果：摘要 / 原文 / 行动 / 附件；区分事实、证据和建议，核对后创建跟进。
15 截图导入：保留原图、编辑识别字段、选择已有或新客户、预览变更、确认保存。
16 操作确认：已有 CRM 保存和电脑审批采用明确确认；新增邮件发送链路尚未实现，不应照图宣称可发送。

0.11 实施状态：以上界面已按概念稿方向完成视觉改造并进入 0.11 构建；逐页模拟器视觉验收见 docs/qa-0.11/report.md，真机验收尚未执行，不声明像素级复刻完成。''')

add('07 XFriend 运行与资源设计', '''基本流程：选助手 / 专家 / 团队 → 选择执行入口 → 预检查 → 创建任务 → 流式响应和工具执行 → 记录结果与检查点。
本机：使用用户配置的模型 API。XFriend 免密钥试用尚未接通，不能用 XSalers 的试用状态推断 XFriend 已可试用。
模式：ask / plan 以只读工具为主；craft 可执行工作区写入及销售草案建议。实际工具权限以运行时检查为准。
团队：沿用顺序协作；成员结果按现有运行时串联，不承诺并行研究。
任务恢复：已持久化任务、结果与检查点；恢复不是无条件重放所有副作用，结果不明的外部操作需要先核对。
资源文件：遍历已知专家可见工作区，排除隐藏目录及符号链接；列表最多 500 个文件，文本预览最多 32000 字符，使用系统文件保存入口导出。
电脑执行：独立远程任务和事件同步。当前手机会话迁移、远程附件、全部电脑版工具参数编辑不在已完成范围内。''')

add('08 XSalers 客户与 CRM 设计', '''客户是业务主入口。沟通记录、录音、截图、复盘结论、待办与商机均应关联客户，而不是散落在互不相关的列表。
客户档案：姓名、电话、公司、阶段与画像；需求、预算、偏好、角色和等级等内容可人工维护。AI 推断应与客户明确表达的事实区分。
待办：按客户归属，记录下一次跟进与行动；统一待办入口用于查看跨客户工作，不替代客户页内待办。
商机：关联客户与阶段，支持个人销售推进；不是已验收的企业级销售组织权限系统。
沟通记录：关联原始材料、转写文本、复盘与证据；录音可回放。
导出：保留筛选客户 CSV 导出。不要把客户列表 CSV 宣称为包含音频、附件、历史版本的完整数据备份。

确认写入：AI 生成建议 → 用户核对、编辑 → 预览变更 → 保存。截图导入将客户更新与沟通关联放在同一事务，空字段保留原值，拦截重复电话及核对期间的资料变化。
截图候选字段仅在具有原始截图证据和明确标签时预填；旧服务响应没有候选字段时允许人工补充，不伪造识别结果。''')

add('09 长录音、截图与后台复盘', '''目标链路：导入并持久化原材料 → 分段音频 → 分段 ASR → 保留原始片段与时间信息 → 分层整理上下文 → 专家或团队分析 → 输出画像建议、客户情况及跟进行动 → 用户核对保存。
长文本不能只截取前一段送模型；汇总应覆盖全部有效片段，保留证据定位。模型输出截断要有分段重试和已有结果恢复，不应让用户重新导入整个文件。

当前源码包含 AudioSegments、ReviewTranscription、ReviewProcessor、ReviewEvidence 等模块。手机端自有模型路径与试用网关路径不同，文件大小、时长和模型限制不能混为一谈。
持久化试用复盘 v2 的本地实现：90 秒音频片段、2 秒重叠；输出截断可递归拆分；保留原文、层级汇总及证据引用。当前文档中的服务限制为总音频 60 分钟、单 PCM WAV 128MiB、图片 10MiB、文本 2MiB、每任务最多 100 份来源。以上是该服务实现边界，不代表已部署服务或所有第三方模型都支持。
试用复盘 v2 的部署仍待完成与线上核验。旧试用接口可能仍不支持长音频流程，不能仅凭客户端 APK 更新就判定线上问题全部解决。

前端只提交任务并观察进度，可继续使用其他功能。百分比为预计进度，提交最终结果后才到 100%；错误详情可展开，支持从保存的进度重试。
录音支持暂停、继续、结束；暂停期间不写入音频、不计有效时长。目前录音页离开前台会结束并保存，不是后台无限录音，也不是自动录制系统电话。
截图支持批量与长图材料处理，保留原始文件供核对；界面展示有缩放，不承诺全分辨率瓦片查看器。
Android 前台服务与持久化任务提高可靠性，但仍需真机验证系统省电策略、强杀及断网后的恢复。''')

add('10 数据、账号与同步', '''本地普通配置：专家、团队、技能、知识与会话等使用现有 Store 数据结构及账号作用域。
销售数据：SalesDatabase 使用 Room，sales_entries 按 kind / id 标识记录，保留排序，内容 payload 加密。不能将“内容加密”描述为所有元数据和所有媒体文件均已加密。
凭据：Android Keystore / AES-GCM，使用 noBackup 目录；交接文件不包含真实密钥、密码或访问令牌。
任务：FriendRun 系列与 SalesTasks 保存运行状态、检查点和结果；账号切换应保持数据作用域隔离。

账号服务：沿用 /api/v2/auth/login 与 refresh，业务接口使用 access_token；租约不是 access_token。
同步实体：customer、communication、opportunity、followup、expert、team、skill、knowledge、model_profile。
推送：/api/v2/sync/push，操作包含 operation_id、entity_type、entity_id、base_revision、deleted、payload。
拉取：/api/v2/sync/pull，使用 space_id、cursor、limit。拉取游标不能用推送 revision 替代。
冲突：版本修订、删除墓碑、幂等操作及显式冲突处理；模型秘密字段按用户选择同步，不等于默认把所有密钥上传。
同步能力须与 D:\\xfriend-activation 中当前协议核对。本文不把本地接口文档当作生产部署验证。
账号同步成功只说明资料同步；不会自动启用云端智能体执行。

0.11 的外观偏好（主题模式、主色）单独保存在本机 SharedPreferences（xf_appearance），不参与账号同步，换机/重装恢复默认。''')

add('11 电脑版连接与配置包兼容', r'''主要电脑版接入点：
D:\octop\desktop\src\internal\mobilebridge\bridge.go
D:\octop\desktop\src\mobile_connection.go
D:\octop\desktop\src\desktop_server.go
D:\octop\src\octop\api\routers\mobile_runs.py
D:\octop\src\octop\infra\mobile_runs\
D:\octop\docs\mobile-runtime-v2-api.md

Go 内置连接桥将 /mobile/v2/* 转发到 /api/mobile/v2/*，负责配对与请求边界；手机凭据不能作为电脑版内部 JWT 直接透传。账户、空间、设备授权与撤销需要保持作用域一致。
协议包括 capabilities、resources、创建任务、快照、带 cursor 的事件、取消、审批。审批绑定 approval_id 与 summary_hash；复杂参数应回电脑版确认。
手机版当前远程任务以文字为主，未覆盖远程模型/模式覆盖、附件、DSH 会议及完整文件链路。连接断开保留最后同步状态，重连后以电脑实际任务状态更新。

独立 Python desktop-bridge 与电脑版 Go 内置桥是不同交付组件。改好一个不会自动更新另一个。Go 源码变化必须随新版电脑版安装包交付后才能生效。
专家与资源导入由 PortablePack* / DesktopImport / DesktopProtocol 承担；电脑 xpack 应遵循现有格式契约，不能用自造 JSON 替代。导入应预览专家、团队依赖、技能、知识和模型引用；手机不支持的引擎或工具应明确显示运行条件。
完整兼容性仍需使用电脑版真实导出包进行验收，不应只凭 ZIP 能打开就认定专家可运行。

交给电脑版开发者的事项：确认移动协议版本与能力列表；打包连接桥变更；用真实 xpack 验证导入；验证账号/空间切换、授权撤销、断线事件恢复；区分配置同步与远程执行入口。''')

add('12 源码定位速查', '''以下文件均相对于 app/src/main/java/com/singu/xfriend/。
设计系统（0.11 新增）：
• XfDesign.kt：XfColors / XfAccentColors / XfAccent / XfThemeMode / XfPalettes / XfType / XfDimens / XfShapes / XfElevation / XfTheme。
• XfComponents.kt：28 个共享组件（XfCard / XfButton / XfStatusChip / XfListRow / XfTextField / XfSegmented / XfDialog / XfEmptyState 等）。
• XfIcons.kt：60 个手绘 ImageVector。
• XfAppearance.kt：主题与 5 色板偏好、XfAppearancePanel。
兼容层：ProductBrand.kt、ProductAppearance.kt、FamilyComponents.kt（旧名转调 Xf 组件，保留旧调用点）。

入口与账号：MainActivity.kt、UnifiedRoot.kt、AccountScreen.kt、CloudAccount.kt。
XFriend 页面：FriendChatScreen.kt、FriendLibraryScreen.kt、FriendResourceScreen.kt、FriendAccountScreen.kt、FriendEditorScreen.kt、FriendBlueprintScreen.kt、FriendAvailabilityUi.kt。
本地智能体：AgentRuntime.kt、AgentRuntimeCheckpoint.kt、FriendModelClient.kt、ConversationMode.kt、Store.kt。
任务与恢复：FriendRun.kt、FriendRunService.kt、FriendRunWorker.kt、FriendRunQueue.kt、FriendRunRepository.kt、FriendRunCheckpoint.kt、FriendRunLiveness.kt。
电脑连接：FriendRemoteClient.kt、FriendRemoteController.kt、FriendRemoteScreen.kt、FriendRemoteProtocol.kt、FriendRemoteApproval.kt、FriendRemoteVault.kt、DesktopProtocol.kt、DesktopImport.kt。
销售主体：SalesScreen.kt、SalesViewModel.kt、SalesDomain.kt、SalesStore.kt、SalesDatabase.kt、SalesConcurrency.kt、CustomerWriteQueue.kt。
CRM：CrmOverviewScreen.kt、SalesCrmScreen.kt、CustomerEditors.kt、CustomerInsight.kt、CustomerAnalysisCard.kt、SalesCrmExport.kt、SalesReminders.kt。
复盘：SalesRecordPage.kt、SalesTaskScreen.kt、SalesTasks.kt、SalesMedia.kt、SalesPlayback.kt、ScreenshotCustomerReview.kt、SalesScreenshotSelection.kt。
模型与媒体处理：AudioSegments.kt、ReviewTranscription.kt、ReviewProcessor.kt、ReviewEvidence.kt、ReviewImageRegions.kt、ReviewContext.kt、ReviewToolLoop.kt、ReviewTrial.kt、SalesApi.kt、SalesNetwork.kt。
专家与市场：SalesExpertScreen.kt、ExpertDesigner.kt、SalesPlaybooks.kt、SalesMarketScreen.kt、ResourceMarketStore.kt、ResourceMarketRepository.kt。
配置包：PortablePack.kt、PortablePackCodec.kt、PortablePackInstall.kt、PortablePackJson.kt、PortablePackStore.kt、PortablePackSync.kt。
同步与迁移：SalesCloudSyncScreen.kt、SalesCloudSyncEngine.kt、SalesCloudSyncStorage.kt、SalesCloudSyncProtocol.kt、SalesCloudSyncData.kt、SalesCloudSyncMedia.kt、SalesMigration.kt、SalesMigrationCodec.kt。

修改原则：UI 调用现有业务层，避免把网络、加密、同步和录音处理塞入 Composable；新 UI 必须使用 Xf 令牌/组件，不新增硬编码颜色与字号；不绕过用户确认直接把模型建议写入客户资料。''')

add('13 构建、安装与证据', f'''当前环境：Android SDK 位于 C:\\Users\\zhang\\AppData\\Local\\Android\\Sdk；实际构建使用 JDK 20，编译目标 17。移机后应自行设置 local.properties / JAVA_HOME。

推荐构建与交付：
Set-Location D:\\xfriend-suite
pwsh -File scripts\\release-0.11.ps1            # QA fixture + 两个正式 APK，并复制到 D:\\build_apk
pwsh -File scripts\\release-0.11.ps1 -WithTests # 构建前额外跑两个 flavor 单元测试
pwsh -File scripts\\release-0.11.ps1 -QaOnly    # 仅构建 .qa 验收包，不覆盖正式交付
.\\build.ps1 -JavaHome 'C:\\Program Files\\Java\\jdk-20'   # test + assemble + lint 完整链

或直接 Gradle：
.\\gradlew.bat --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleXfriendDebug :app:assembleXsalersDebug --console=plain

XFriend applicationId：com.singu.xfriend.mobile
XSalers applicationId：com.singu.xsalers.mobile
源码输出：
app\\build\\outputs\\apk\\xfriend\\debug\\app-xfriend-debug.apk
app\\build\\outputs\\apk\\xsalers\\debug\\app-xsalers-debug.apk
注意：执行 QA fixture 构建后，上述 Gradle 输出路径会变成 .qa 包；正式交付以 D:\\build_apk 与 .artifacts\\0.11-release\\ 为准。
交付文件：
{EVIDENCE['apk_xf']}（{EVIDENCE['apk_xf_bytes']}）
{EVIDENCE['apk_xs']}（{EVIDENCE['apk_xs_bytes']}）

构建证据：
{EVIDENCE['build_log']}：{EVIDENCE['build_result']}，耗时 {EVIDENCE['build_time']}，任务统计 {EVIDENCE['build_tasks']}；0 个编译错误；仅历史弃用/未用变量 warning。
{EVIDENCE['build_extra_log']}。
最终构建包含 QA 首轮修复：SalesScreen IME 判断（XSalers 底栏 P0）、XfSegmented 选中项 1dp 细边框（P1）、bottomBarHeight 64→80dp（P2）、PlatformScreen 设备在线判断容错。
0.11 未默认执行单元测试与 lint：{EVIDENCE['tests']}；{EVIDENCE['lint']}。

模拟器 QA：
设备：{EVIDENCE['qa_device']}。
证据目录：docs/qa-0.11/（prep.md、setup-boot.png、_logs/、_raw/、报告 report.md）。
QA fixture：首轮 {EVIDENCE['qa_log']}；修复后重建 {EVIDENCE['qa_final_log']}（BUILD SUCCESSFUL in 10m 17s）。
{EVIDENCE['qa_final_fixture']}
报告：{EVIDENCE['qa_report']} — {EVIDENCE['qa_result']}。
回归截图：docs/qa-0.11/*-reg.png（xs-today/customers/review/experts/profile-reg、xs-appearance-reg-light/dark、xf-chat/workbench-reg）。

不要为正式交付加入 -PsalesUiFixture=true，该参数用于独立 QA fixture（.qa 包名）。两个 APK 是开发签名，非商店发布包。覆盖安装须使用兼容签名；建议先保留已有本机数据，不要为了更新先卸载。
旧 0.10 安装包保留在交付目录，当前应选择名称中 0.11.0 的文件（构建复制完成后）。''')

add('14 尚未完成的能力与建议验收顺序', '''0.11 的验证边界：本次只完成 UI 视觉重塑并构建；模拟器 QA 结果见 docs/qa-0.11/report.md，真机验收、单元测试、lint、无障碍与真实模型链路未完成或未执行，不能宣称全部通过。

第一优先：在真实手机覆盖安装 0.11，确认旧数据可读、登录/切换账号、模型配置、发送/停止/流式响应、切后台和恢复；检查新设计在真实屏上的字号、深色、底栏 Haze 与选中胶囊。
第二优先：视觉与无障碍。逐页核对 docs/design/redesign-concept-android-spec.md §1/§2/§4；覆盖浅色/深色、5 色板、fontScale 1.0/1.3/1.5/2.0、TalkBack、对比度与键盘避让；把仍残留的 Material 图标/typography 清零。
第三优先：长录音全链路。分别验证自有模型和试用路径；覆盖输出截断、断网、重试、去重、进度 100% 边界、音频回放和证据定位。持久化试用 v2 需单独部署和核验。
第四优先：客户关联与截图导入。验证已有客户匹配、空电话保护、重复电话、人工修改、确认后持久化、待办归属和 CSV 导出。
第五优先：电脑版真实安装包与手机端联调，验证配对、能力发现、任务事件、断线恢复、审批和 xpack。
第六优先：账号与配置云同步，验证不同设备/空间、冲突、删除墓碑、模型秘密字段选择和撤销。

明确未实现或未验收：云端智能体执行；XFriend 免密钥试用；并行研究团队；手机会话无缝迁往电脑；新增邮件发送；所有电脑版工具与附件完整映射；群聊/会议/决策档案；生产签名与上架；0.11 真机视觉与无障碍验收；0.11 单元测试与 lint。
上述内容是下一阶段计划，不把计划写成已完成。''')

add('15 现有文档索引与阅读顺序', f'''当前 0.11 交付依据：
D:\\xfriend-suite\\README.md
D:\\xfriend-suite\\docs\\README.md
D:\\xfriend-suite\\docs\\XFAMILY-0.11-交付说明.txt
D:\\xfriend-suite\\docs\\XFRIEND-REDESIGN-0.11.md
D:\\xfriend-suite\\docs\\DELIVERY-0.11.html
D:\\xfriend-suite\\docs\\verification-0.11.json
D:\\xfriend-suite\\docs\\design\\redesign-concept-android-spec.md
D:\\xfriend-suite\\docs\\design\\screens\\（概念参考截图）
D:\\xfriend-suite\\docs\\qa-0.11\\report.md（模拟器 QA）
D:\\xfriend-suite\\{EVIDENCE['build_log']}

历史版本（仅追溯，不覆盖 0.11 事实）：
D:\\xfriend-suite\\docs\\XFAMILY-0.10-交付说明.txt、docs\\xfamily-0.10\\
D:\\xfriend-suite\\docs\\DELIVERY-0.9.html、docs\\qa-ui-0.9.json
D:\\xfriend-suite\\docs\\verification-0.7.json、DELIVERY-0.7.html
D:\\xfriend-suite\\docs\\verification-0.6.json / 0.5.json
D:\\xfriend-suite\\docs\\MOBILE-DESIGN.md（0.2 旧方案）

架构与接口：
D:\\xfriend-suite\\docs\\XFRIEND-RUNTIME-DESIGN-0.8.html
D:\\xfriend-suite\\docs\\DESKTOP-HANDOFF-0.9.txt
D:\\xfriend-suite\\docs\\account-sync-api-0.7.md
D:\\xfriend-suite\\docs\\desktop-sync-handoff-0.7.md
D:\\xfriend-suite\\trial-gateway\\README.md
D:\\xfriend-suite\\desktop-bridge\\README.md
D:\\octop\\docs\\mobile-runtime-v2-api.md
D:\\xfriend-activation\\SYNC-API.md

建议新开发者先读 README 与本文的交付状态，再读 0.11 视觉落地说明与设计规范，最后按模块读接口契约并对照源码。旧方案的导航、配色与旧交付状态不能覆盖本次 0.11 设计事实。
本文只整理项目交接，没有再次部署服务器、修改应用业务或执行测试。''')

# ---------- 附录图片：概念设计参考 + 0.11 QA 实测（存在才嵌入） ----------
design_refs = [
    ('concept-home-light.png', '概念稿 · 首页（浅色，设计参考）'),
    ('concept-experts-light.png', '概念稿 · 专家列表（设计参考）'),
    ('concept-profile-light.png', '概念稿 · 个人中心（设计参考）'),
    ('concept-system-light.png', '概念稿 · 设计系统（令牌与组件参考）'),
]

# QA 截图按目录实际文件扫描，不假设命名；环境校准图不作为验收证据嵌入。
qa_captions = {
    'xf-login-light.png': 'XFriend · 登录（浅色）',
    'xf-chat-light.png': 'XFriend · 对话（浅色）',
    'xf-chat-dark.png': 'XFriend · 对话（深色）',
    'xf-chat-systemdark.png': 'XFriend · 对话（跟随系统深色）',
    'xf-workbench-light.png': 'XFriend · 工作台（浅色）',
    'xf-workbench-dark.png': 'XFriend · 工作台（深色）',
    'xf-resources-light.png': 'XFriend · 资源（浅色）',
    'xf-resources-dark.png': 'XFriend · 资源（深色）',
    'xf-profile-light.png': 'XFriend · 我的（浅色）',
    'xf-profile-dark.png': 'XFriend · 我的（深色）',
    'xf-appearance-light.png': 'XFriend · 外观（浅色）',
    'xf-appearance-dark.png': 'XFriend · 外观（深色）',
    'xf-appearance-follow-light.png': 'XFriend · 外观·跟随系统（浅色）',
    'xs-login-light.png': 'XSalers · 登录（浅色）',
    'xs-today-light.png': 'XSalers · 今日（浅色）',
    'xs-today-dark.png': 'XSalers · 今日（深色）',
    'xs-customers-light.png': 'XSalers · 客户（浅色）',
    'xs-customer-detail-light.png': 'XSalers · 客户档案（浅色）',
    'xs-review-detail-light.png': 'XSalers · 复盘详情（浅色）',
    'xs-experts-light.png': 'XSalers · 专家（浅色）',
    'xs-profile-light.png': 'XSalers · 我的（浅色）',
    'xs-appearance-light.png': 'XSalers · 外观（浅色）',
    'xs-appearance-dark.png': 'XSalers · 外观（深色）',
    'xs-appearance-reg-light.png': 'XSalers · 外观（回归·浅色）',
    'xs-appearance-reg-dark.png': 'XSalers · 外观（回归·深色）',
    'xs-today-reg.png': 'XSalers · 今日（回归）',
    'xs-customers-reg.png': 'XSalers · 客户（回归）',
    'xs-review-reg.png': 'XSalers · 复盘（回归）',
    'xs-experts-reg.png': 'XSalers · 专家（回归）',
    'xs-profile-reg.png': 'XSalers · 我的（回归）',
    'xf-chat-reg.png': 'XFriend · 对话（回归）',
    'xf-workbench-reg.png': 'XFriend · 工作台（回归）',
}
qa_exclude = {'setup-boot.png', 'wm-size-check.png'}


def existing(base, refs):
    return [(base / f, c) for f, c in refs if (base / f).is_file()]


design_images = existing(root / 'docs/design/screens', design_refs)
qa_dir = root / 'docs/qa-0.11'
qa_images = []
if qa_dir.is_dir():
    for p in sorted(qa_dir.glob('*.png'), key=lambda x: x.stat().st_mtime):
        if p.name in qa_exclude:
            continue
        qa_images.append((p, qa_captions.get(p.name, p.stem)))

title = 'XFAMILY 手机端项目与设计交接文档'
plain = title + f'\n版本：{VERSION} | 日期：{DATE}\n\n' + '\n\n'.join(t + '\n' + '─' * 40 + '\n' + b for t, b in sections)
plain += '\n\n附录：概念稿设计参考（非验收截图）\n' + '\n'.join(str(p) + ' — ' + c for p, c in design_images)
plain += '\n\n附录：0.11 模拟器 QA 截图\n' + ('\n'.join(str(p) + ' — ' + c for p, c in qa_images) if qa_images else '暂无 QA 截图（见 docs/qa-0.11/）')
source = root / 'app/src/main/java/com/singu/xfriend'
files = sorted(str(p.relative_to(root)).replace('\\', '/') for p in source.rglob('*.kt'))
plain += '\n\n附录：当前 Kotlin 源文件清单（自动读取，' + str(len(files)) + ' 个）\n' + '\n'.join(files) + '\n'

nav = ''.join(f'<a href="#s{i}">{html.escape(t)}</a>' for i, (t, b) in enumerate(sections))
body = ''.join(f'<section id="s{i}"><h2>{html.escape(t)}</h2><div class="prose">{html.escape(b)}</div></section>' for i, (t, b) in enumerate(sections))

def figures(items, caption_note):
    out = ''
    for path, caption in items:
        data = base64.b64encode(path.read_bytes()).decode()
        out += f'<figure><img src="data:image/png;base64,{data}" alt="{html.escape(caption)}"><figcaption>{html.escape(caption)} · {html.escape(caption_note)}</figcaption></figure>'
    return out

design_html = figures(design_images, '概念参考，不是 0.11 验收截图')
qa_html = figures(qa_images, '0.11 模拟器实测截图，仅代表该环境') if qa_images else '<p class="muted">暂无 QA 截图（见 docs/qa-0.11/）</p>'

page = f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>XFAMILY 项目交接</title><style>
:root{{color-scheme:light;--bg:#F6F5F1;--surface:#FFFFFF;--ink:#1B1C1F;--muted:#57585F;--line:#E5E2DA;--accent:#4F5DF0;--violet:#7055DE}}*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font:16px/1.85 system-ui,'Microsoft YaHei',sans-serif}}header{{padding:56px max(24px,calc((100vw - 1240px)/2));background:linear-gradient(115deg,#EFEDE7,#ECEEFE)}}h1{{font-size:34px;line-height:1.35;margin:12px 0}}header p{{max-width:850px;color:#57585F}}.badge{{display:inline-block;background:white;border:1px solid var(--line);border-radius:20px;padding:4px 16px;margin:4px;color:var(--accent);font-size:14px}}.layout{{display:grid;grid-template-columns:250px minmax(0,1fr);gap:28px;max-width:1280px;margin:auto;padding:32px 20px}}nav{{position:sticky;top:16px;align-self:start;max-height:92vh;overflow:auto}}nav a{{display:block;color:#57585F;text-decoration:none;padding:7px 10px;font-size:14px;border-left:2px solid var(--line)}}nav a:hover{{color:var(--accent);background:#ECEEFE}}section{{background:var(--surface);padding:28px;border:1px solid var(--line);border-radius:18px;margin-bottom:24px;scroll-margin-top:20px}}h2{{font-size:23px;line-height:1.5;margin:0 0 18px;color:#1B1C1F}}.prose{{white-space:pre-wrap;overflow-wrap:anywhere}}figure{{margin:24px 0}}img{{display:block;width:100%;height:auto;border-radius:14px;border:1px solid var(--line)}}figcaption{{font-size:13px;color:var(--muted);padding:8px}}details{{background:white;padding:24px;border:1px solid var(--line);border-radius:18px}}pre{{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px}}footer{{padding:20px;text-align:center;color:var(--muted)}}@media(max-width:850px){{.layout{{display:block}}nav{{position:static;max-height:none;margin-bottom:24px;columns:2}}section{{padding:20px}}h1{{font-size:28px}}header{{padding:32px 20px}}}}@media print{{nav{{display:none}}.layout{{display:block;padding:0}}body{{background:white;font-size:11pt}}header{{padding:20px}}section{{border:0;break-inside:auto;padding:12px 0}}figure{{break-inside:avoid}}details{{display:none}}a{{color:inherit}}}}
</style><header><div>XFAMILY · ENGINEERING HANDOFF</div><h1>{title}</h1><p>两款 Android 产品的定位、设计系统、源码目录、运行架构、接口对接和交付边界。供产品、手机端与电脑版开发者共同使用。</p><span class="badge">{VERSION} · {DATE}</span><span class="badge">{html.escape(BUILD_BADGE)}</span></header><div class="layout"><nav>{nav}<a href="#designs">附录 · 概念稿设计参考</a><a href="#qa">附录 · 0.11 模拟器 QA 截图</a><a href="#sources">附录 · 源文件清单</a></nav><main>{body}<section id="designs"><h2>附录 · 概念稿设计参考</h2><p>来自 docs/design/screens/，是桌面 Chromium 渲染的概念参考，不是 0.11 真机验收截图。</p>{design_html}</section><section id="qa"><h2>附录 · 0.11 模拟器 QA 截图</h2><p>来自 docs/qa-0.11/，仅代表该模拟器环境；真机验收未执行。</p>{qa_html}</section><details id="sources"><summary>当前 Kotlin 源文件清单 · {str(len(files))} 个文件</summary><pre>{html.escape(chr(10).join(files))}</pre></details></main></div><footer>以当前源码、0.11 构建日志与 QA 证据为交接依据；线上状态需单独核验。</footer></html>'''

for directory in [root / 'docs', out]:
    (directory / (name + '.html')).write_text(page, encoding='utf-8')
    (directory / (name + '.txt')).write_text(plain, encoding='utf-8-sig')
for suffix in ['.html', '.txt']:
    p = out / (name + suffix)
    print(str(p), p.stat().st_size)
print('sections', len(sections), 'design_images', len(design_images), 'qa_images', len(qa_images), 'kotlin_files', len(files))
