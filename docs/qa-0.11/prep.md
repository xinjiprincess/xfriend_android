# 0.11 APK 视觉与运行验收 — 环境准备（prep）

> 任务板 `task-5`：0.11 APK 模拟器视觉与运行验收。
> 当前状态：**环境就绪，等待 0.11 APK（正式包 + QA 包）**。本文件记录环境、工具、已验证链路与第二阶段待办；不是验收结论。

- 准备时间：2026-10-17 23:11–23:3x（Asia/Shanghai）
- 负责人：teammate `visual-qa`
- 写入范围：仅 `docs/qa-0.11/`（源码零改动）

## 1. 环境

| 项目 | 值 |
|---|---|
| SDK / adb | `C:\Users\zhang\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| emulator | `C:\Users\zhang\AppData\Local\Android\Sdk\emulator\emulator.exe`（36.4.10.0） |
| AVD Home | `ANDROID_AVD_HOME=D:\xfriend-suite\.qa-avd` |
| AVD | `XfriendSuiteQA`（项目自带，Pixel Tablet 面板 / API 35 / Android 15 / `google_apis_playstore_tablet` x86_64） |
| 序列号 | `emulator-5554` |
| 加速 | WHPX 可用（`emulator -accel-check` → 0） |
| 启动命令 | `emulator -avd XfriendSuiteQA -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim -no-snapshot -no-snapshot-save -netdelay none -netspeed full` |
| 启动结果 | **成功**，`sys.boot_completed=1`，`init.svc.bootanim=stopped`（本次冷启动约 2 分钟；历史日志曾 272s） |
| 屏幕 | 物理 `2560x1600`（Pixel Tablet 面板）→ **`wm size 1080x2400` 覆盖为竖屏手机形态**，密度 420dpi（dp ≈ 411×914） |
| 设计/证据分辨率 | 所有验收截图 `1080x2400`，与历史 QA 证据（`suite-0.7-*.png` 720x1600、`qa-system-ui-anr-0.7.png` 1080x2400）同链路，真实 `adb screencap` |

说明：项目 AVD 是平板面板且默认横屏；视觉规范（YAML 令牌、底栏 4/5 项、20dp 页边距）面向手机，故按项目历史 QA 做法用 `wm size` 覆盖为手机分辨率（仅设备设置，不改源码）。若 Lead 要求平板/横屏形态，可 `_tools/phone-size.ps1 -Reset` 还原后补拍。

设备侧准备（幂等，仅设置项）：`svc power stayon true`、`screen_off_timeout=max`、`wm dismiss-keyguard`、三个动画 scale=0（保证截图稳定）。

已知一次性异常：改分辨率后 SystemUI 曾弹 「System UI isn't responding」，已点 Wait 消除；此后重启 App/切页稳定。若 0.11 验收中再出现，按 P1/system 问题记录并重试。

## 2. 已产出文件

```
docs/qa-0.11/
├─ prep.md                     ← 本文件
├─ setup-boot.png              ← 环境链路证据（真实 screencap，1080x2400，2,183,001 bytes）
├─ _tools/
│  ├─ qa-lib.ps1               ← 公共函数库（adb/截图/ui dump/底栏点击/logcat 过滤）
│  ├─ env-check.ps1            ← 一键环境自检（设备/分辨率/密度/截图链路/屏幕常亮）
│  ├─ wait-boot.ps1            ← 等 boot_completed=1 + pm 就绪（最长 300s）
│  ├─ phone-size.ps1           ← wm size 1080x2400 覆盖 / -Reset 还原 / -Status
│  ├─ shot.ps1                 ← 截屏：adb shell screencap -p → adb pull → PNG 魔数校验
│  ├─ tab.ps1                  ← 底栏点击：文案精确 → 底栏自动探测 → wm size 等分兜底
│  ├─ ui.ps1                   ← texts / dump / find / tap / tabs（uiautomator dump 找坐标）
│  └─ img-stats.py             ← 截图统计（尺寸/亮度/主色/采样点/暖纸色占比）
├─ _logs/                      ← env-check.txt、emulator-out/err.log、后续 logcat 摘录
└─ _raw/                       ← uiautomator dump xml、校准截图（非验收证据）
```

### 常用命令

```powershell
# 环境自检（含截图链路）
& D:\xfriend-suite\docs\qa-0.11\_tools\env-check.ps1 -OutPng setup-boot.png -Setup

# 等模拟器就绪（若重启）
& D:\xfriend-suite\docs\qa-0.11\_tools\wait-boot.ps1 -TimeoutSec 300

# 截一张（自动加 PNG 魔数校验，失败 3 次重试）
& D:\xfriend-suite\docs\qa-0.11\_tools\shot.ps1 -Name xf-chat-light -WaitSec 3

# 底栏第 2 项（0-based）：Xfriend 4 项 / Xsalers 5 项
& D:\xfriend-suite\docs\qa-0.11\_tools\tab.ps1 -App xfriend -Index 1 -WaitSec 3
& D:\xfriend-suite\docs\qa-0.11\_tools\tab.ps1 -App xsalers -Index 2 -WaitSec 3

# 页面文本/坐标：列出 / 找 / 点 / 自动探测底栏
& D:\xfriend-suite\docs\qa-0.11\_tools\ui.ps1 -Action texts
& D:\xfriend-suite\docs\qa-0.11\_tools\ui.ps1 -Action find -Text '外观'
& D:\xfriend-suite\docs\qa-0.11\_tools\ui.ps1 -Action tap  -Text '外观'
& D:\xfriend-suite\docs\qa-0.11\_tools\ui.ps1 -Action tabs

# 截图客观统计（用于报告佐证：暖纸 #F6F5F1 / 深色画布 #0E0F12 占比、采样点色值）
& C:\Users\zhang\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe `
    D:\xfriend-suite\docs\qa-0.11\_tools\img-stats.py docs\qa-0.11\xf-chat-light.png --points '540,300;540,1200'
```

## 3. 已验证的验收链路（真实执行，非模拟）

| # | 验证项 | 结果 |
|---|---|---|
| 1 | `emulator -list-avds` + 启动项目 AVD `XfriendSuiteQA` | ✅ boot_completed=1（无 fallback） |
| 2 | `adb devices` / `wm size` / `wm density` | ✅ emulator-5554 device / 1080x2400（Override）/ 420 |
| 3 | `adb shell screencap -p` + `adb pull` | ✅ `setup-boot.png` 2,183,001 bytes，PNG 魔数校验通过 |
| 4 | `uiautomator dump` + 文本坐标解析 | ✅ 中文文本/坐标解析正常（见 `_raw/ui-texts.xml`） |
| 5 | 底栏自动探测（不依赖固定文案） | ✅ Xfriend 02 fixture 探测到 3 项：对话(175,2296)/专家(540,2296)/我的(905,2296) |
| 6 | 底栏点击切页 | ✅ 点「专家」后页面切换（`_raw/calibration-xf-after-tab1.png`） |
| 7 | QA fixture Activity 启动链路 | ✅ 0.9 QA 包 `com.singu.xfriend.mobile.qa`、`com.singu.xsalers.mobile.qa` 的 `com.singu.xfriend.SalesFixtureActivity` 均可冷启动（`Status: ok`） |
| 8 | 日志/崩溃采集 | ✅ `logcat -d` 可采；过滤函数已就绪（FATAL/AndroidRuntime/ANR/包名） |

> 第 5–7 项是用 `.artifacts/Xfriend-0.9-qa.apk`、`Xsalers-0.9-qa.apk` 做**工具链彩排**，只为校准点击/截图脚本；0.11 到达后会用 0.11 QA 包 `install -r` 覆盖，**报告只采用 0.11 APK 的真实截图**。
> 校准发现（0.9 旧版）：Xfriend 底栏 3 项、点击带 y≈2212–2319，label 中心 y=2296；Xsalers 0.9 无底栏（0.11 设计新增 5 项，届时自动探测）。
> 旧 QA 包当前仍安装（同签名的 0.11 QA 包可直接 `install -r` 覆盖；若签名不同会先 `uninstall` 再装）。

## 4. 第二阶段待办（等 Lead 通知 APK 就绪）

**输入（Lead 交付）：**
- 正式包：`app\build\outputs\apk\xfriend\debug\app-xfriend-debug.apk`（`com.singu.xfriend.mobile`，应用名 Xfriend）、`app\build\outputs\apk\xsalers\debug\app-xsalers-debug.apk`（`com.singu.xsalers.mobile`）
- QA 包：`.artifacts\0.11-qa\Xfriend-0.11-qa.apk`（`com.singu.xfriend.mobile.qa`）、`Xsalers-0.11-qa.apk`（`com.singu.xsalers.mobile.qa`）；启动 `com.singu.xfriend.SalesFixtureActivity`（两个包同一 Activity），Xfriend 空 store / Xsalers 注入示例数据，无需登录

**步骤：**
1. 校验 APK：存在、大小、`versionName=0.11.x`（`aapt`/`dumpsys package`）；旧包同名则 `install -r` 并确认版本号变化。
2. 正式包：`adb install -r` ×2 → 冷启动 `am start -n com.singu.xfriend.mobile/com.singu.xfriend.MainActivity` → 截登录页（首次启动）`xf-login-light.png`；Xsalers 同 `xs-login-light.png`（若 Xsalers 直接进主壳则记录实际情况，不伪造）。
3. QA 包：`adb install -r` ×2 → 启动 fixture Activity → 逐页截图：
   - Xfriend：对话 / 工作台 / 资源 / 我的 / 我的→外观（浅色 + 点深色各一张）
   - Xsalers：今日 / 客户 / 复盘 / 专家 / 我的 / 我的→外观（浅色 + 深色）
   - 文件名：`xf-chat-light.png`、`xf-appearance-dark.png`、`xs-today-light.png` …（每张前 sleep 2–3s，切页用 `tab.ps1`/`ui.ps1 -Action tap`）
4. 深色模式两种来源都验证：(a) App 内 我的→外观→深色；(b) `adb shell cmd uimode night yes` 后重启 App（跟随系统）。若外观页无入口/无深色分段 → 记 P1，不卡住。
5. 崩溃检查：每轮页面截图后 `logcat -d` 过滤 `FATAL EXCEPTION|AndroidRuntime|ANR`；`dumpsys activity activities | grep -i anr` 辅助；留存 `_logs/logcat-xfriend.txt`、`logcat-xsalers.txt`、`anr-check.txt`。
6. 用 `read_image` 逐张目检，对照 `docs/design/redesign-concept-android-spec.md` §1（令牌：暖纸 #F6F5F1/深色 #0E0F12、白卡 #FFFFFF + 1px #E5E2DA、品牌主色 XFriend #5267E8 / XSalers #7055DE）、§2（字号：PageTitle 24 / SectionTitle 17 / Body 15 / Caption 11；圆角 14 卡/10 控件/8 按钮；20dp 页边距；底栏 80dp+insets）、§4（卡片/按钮/chip/列表/底栏毛玻璃与选中胶囊）与 `docs/design/screens/concept-*.png`。
7. 产出 `docs/qa-0.11/report.md`：环境、安装/启动结果、页面×结论表、问题清单（P0 崩溃/无法进入/明显错位；P1 视觉不一致；P2 建议）、截图索引（路径+客观统计）、logcat 摘录、深色可读性结论。
8. `send_message` 给 lead：P0/P1 列表 + 报告路径；完成后 `adb emu kill` 关闭模拟器。

**验收判据（来自 spec §1/§2/§4 + 任务书）：** 暖纸画布/深色画布正确；白卡+细边框；单一主色（品牌色）一致；圆角层级；字号层级（标题 24/17、正文 15、辅助 11）；底栏毛玻璃/半透明与选中胶囊；线性图标语言；深色模式文字可读；无重叠/截断/错位/贴边。

## 5. 风险与备注

- task-5 的 `blocked_by`（task-1..4）尚未完成，`team_task_update action=claim` 返回 `not ready to claim`；等 Lead 完成依赖后再占用。工作按“先做环境准备”的指示推进。
- 模拟器保持运行以避免二次冷启动；若会话间隔过长导致 adb 失联，用 `wait-boot.ps1` 恢复，必要时重启 AVD。
- 本机 PowerShell 为 Windows PowerShell 5.1：所有 `.ps1` 已存为 **UTF-8 with BOM**（否则中文标签乱码），脚本内已规避 `-AsByteStream` 等 PS7 专有参数。
- 不安装第三方工具；截图全部来自 `adb screencap`；不伪造/占位任何截图。
