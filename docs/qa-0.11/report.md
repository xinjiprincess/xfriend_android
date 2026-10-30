# 0.11.0 Android 视觉与运行验收报告（模拟器 QA）

- 版本：0.11.0（versionCode=11），设计系统「Singu Xfriend 前端重塑概念稿 v1」
- 验收环境：Android Emulator `XfriendSuiteQA`（Pixel Tablet 面板，Android 15 / API 35），手机竖屏 1080×2400 @420dpi（411×914dp）
- 证据来源：全部截图均由 `adb shell screencap -p` + `adb pull` 实拍（PNG 魔数校验），未使用任何伪造/占位图
- 构建来源：正式包 + QA fixture 包（`-PsalesUiFixture=true`，包名 `.qa`）。**截图中顶部紫色「界面验收 · 独立 QA 安装 · 示例数据」提示条为 QA 构建专有**，其中数据为示例数据，不是真实账号数据
- 报告时间：2026-10-18 00:1x（Asia/Shanghai）
- **状态：本报告基于修复前 APK（SHA 见 §2）；P0/P1/P2 三项已由 Lead 修复并重编，等待最终 APK 回归（回归清单见 §9）**

## 0. 结论摘要

| 等级 | 数量 | 内容 | 状态 |
|---|---|---|---|
| **P0** | 1 | XSalers 主壳底部 5-tab 导航栏完全未渲染 → 无法在 5 个主 tab 间导航 | 已定位根因并修复，待回归 |
| **P1** | 1 | 分段控件（XfSegmented）选中项阴影在浅色下渲染为较重灰色描边 | 已修复（改 1dp line 细边框），待回归 |
| **P2** | 1 | 底栏高度 64dp，低于 spec §2.4 建议的 80dp；tab 文字距栏底仅约 9dp、距手势胶囊约 23dp，观感偏紧 | 已修复（64→80dp），待回归 |
| 非问题 | 2 | ① XSalers 客户详情"点不开"经复核为**截图脚本点击坐标误触**（点在"待办"tab 行），非产品缺陷；② QA 顶部提示条为 fixture 专有 | 已复核 |
| 未覆盖 | 1 | XSalers「专家」「我的」「外观」页因 P0 底栏缺失无法进入，**未截**（已标注，不伪造） | 待修复后补 |

整体视觉系统落地良好：暖纸画布/白色卡片/细边框/单一主色/亮暗主题/线性图标均已生效，深色模式文字可读，无崩溃、无 ANR。

---

## 1. 环境

| 项目 | 值 |
|---|---|
| adb | `C:\Users\zhang\AppData\Local\Android\Sdk\platform-tools\adb.exe` |
| emulator | `C:\Users\zhang\AppData\Local\Android\Sdk\emulator\emulator.exe`（36.4.10.0） |
| AVD | `XfriendSuiteQA`（`ANDROID_AVD_HOME=D:\xfriend-suite\.qa-avd`，API 35 / Android 15 / x86_64 / google_apis_playstore_tablet） |
| 启动 | `emulator -avd XfriendSuiteQA -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim -no-snapshot -no-snapshot-save -netdelay none -netspeed full`（后台运行，boot_completed=1） |
| 显示 | 物理 2560×1600 Pixel Tablet 面板 → `wm size 1080x2400` 覆盖为手机竖屏；**`wm density` 已 reset 到物理 420dpi**（AVD 曾残留 override 300dpi，会导致 576×1280dp 的网页级宽度；本报告全部证据为修正后的 411×914dp） |
| 设备准备 | `svc power stayon true`、关 keyguard、动画 scale=0（仅设备设置，不改源码） |
| 已装包 | 正式包 `com.singu.xfriend.mobile` / `com.singu.xsalers.mobile`；QA 包 `com.singu.xfriend.mobile.qa` / `com.singu.xsalers.mobile.qa`，均 `versionName=0.11.0 versionCode=11` |

## 2. APK 与安装

| 包 | 路径 | 大小 | SHA-256 / 说明 |
|---|---|---|---|
| Xfriend 正式 | `.artifacts\0.11-release\XFriend-Android-0.11.0-debug.apk` | 26,451,773 | `1BBA8510A289D767004898BEDDAB2AA42D54DA82317761C01D83E4A1A2132B49`（与 Lead 提供一致） |
| Xsalers 正式 | `.artifacts\0.11-release\XSalers-Android-0.11.0-debug.apk` | 26,451,741 | `531F39BABD5D932AF29D744EB7964F8E5E96935EF6956EE9D8FA6FAB2A6DE406`（一致） |
| Xfriend QA | `.artifacts\0.11-qa\Xfriend-0.11-qa.apk` | 26,467,805 | `E0BA58F8FE2B3E520799E6EDEEB8B8136417301D320A0D4E2937D84D401E5681` |
| Xsalers QA | `.artifacts\0.11-qa\Xsalers-0.11-qa.apk` | 26,467,893 | `EAB232AE4BF73C4700B4B5CBEF38960925A5FC50F7F9913C726487FE22C730D1` |

安装：4 个包 `adb install -r -t` 全部 `Success`；`dumpsys package` 校验 `versionName=0.11.0`、`versionCode=11`（安装记录：`_logs\install.txt`）。

启动命令：
- 正式包：`adb shell am start -n com.singu.xfriend.mobile/com.singu.xfriend.MainActivity`（Xsalers 同理），首次启动为登录页
- QA fixture：`adb shell am start -n com.singu.xfriend.mobile.qa/com.singu.xfriend.SalesFixtureActivity`（两个 `.qa` 包同一 Activity；Xfriend 空 store、Xsalers 注入示例客户/沟通/商机/待办）

---

## 3. 截图索引（均为 1080×2400，真实 adb screencap）

| 文件 | 页面 | 主题 | 大小 | 结论 |
|---|---|---|---|---|
| `xf-login-light.png` | Xfriend 正式包登录页（首次启动） | 浅色 | 146,404 | 正常：品牌星标、标题/副标题、输入框、主按钮、辅助链接、版本号 `XFAMILY · XFriend 0.11.0` |
| `xs-login-light.png` | XSalers 正式包登录页 | 浅色 | 151,324 | 正常：同结构，版本号 `XFAMILY · XSalers 0.11.0` |
| `xf-chat-light.png` | Xfriend 对话 tab（QA 空 store） | 浅色 | 246,878 | 正常：暖纸底+白卡、hero 输入区、问答/计划/执行分段、底栏 4 tab（选中胶囊） |
| `xf-chat-dark.png` | Xfriend 对话 tab | 深色 | 240,342 | 正常：深色画布/卡片/文字可读；底栏深色正常 |
| `xf-workbench-light.png` | Xfriend 工作台 tab | 浅色 | 239,314 | 正常；**分段控件选中「专家」有灰色描边（P1 证据）** |
| `xf-workbench-dark.png` | Xfriend 工作台 tab | 深色 | 236,631 | 正常 |
| `xf-resources-light.png` | Xfriend 资源 tab | 浅色 | 203,522 | 正常（列表行/图标 tile 风格一致） |
| `xf-resources-dark.png` | Xfriend 资源 tab | 深色 | 200,955 | 正常 |
| `xf-profile-light.png` | Xfriend 我的 tab | 浅色 | 214,791 | 正常：头像/工作空间卡、账户分组行、右箭头、底栏选中「我的」胶囊 |
| `xf-profile-dark.png` | Xfriend 我的 tab | 深色 | 213,589 | 正常 |
| `xf-appearance-light.png` | 我的→外观（主题模式/主色/预览） | 浅色 | 255,954 | 正常：主色 5 色板、预览 chip/按钮；**分段选中「跟随系统」灰色描边（P1）** |
| `xf-appearance-dark.png` | 我的→外观（已点「深色」） | 深色 | 252,548 | 正常：画布 #0E0F12、卡片 #181A1F、文字可读；同日 App 内切换生效 |
| `xf-chat-systemdark.png` | 外观=跟随系统 + `cmd uimode night yes` 后重启 | 系统深色 | 233,143 | 正常：跟随系统生效（与 App 内深色结果一致） |
| `xs-today-light.png` | XSalers 今日 tab（QA 示例数据） | 浅色 | 264,615 | **页面本身正常**（暖纸/白卡/紫色主色/状态 chip）；**底部无导航栏 → P0** |
| `xs-today-dark.png` | XSalers 今日 tab | 系统深色 | 264,534 | 深色正常（画布 #0E0F12）；**底部仍无导航栏 → P0** |
| `xs-customers-light.png` | XSalers 客户 tab（CRM 概览，经"查看全部"进入） | 浅色 | 129,263 | 正常：分段 客户/待办/商机/联系计划、搜索、客户行 |
| `xs-customers-dark.png` | XSalers 客户 tab（CRM 概览） | 深色 | 128,660 | 正常 |
| `xs-customer-detail-light.png` | 客户档案（示例客户·陈经理） | 浅色 | 198,973 | 正常：头像/阶段 chip、记录沟通/拨打电话、概览分段、客户画像键值 |
| `xs-review-detail-light.png` | 沟通复盘详情（方案沟通复盘） | 浅色 | 227,647 | 正常：标题/关联客户/摘要·原文·行动·附件分段/AI 分析/原文证据/操作按钮 |
| `setup-boot.png` | 环境链路验证（初始化时） | — | 2,183,001 | 仅环境证据（含 system ANR 对话框，见 §7） |

**未截（如实标注）**：XSalers「专家」「我的」「外观」页（被 P0 底栏缺失阻断，无法进入）；XSalers 复盘详情深色（时间原因未补）；Xfriend 正式包登录页深色（登录页无主题开关，且 App 内主题偏好未写入正式账号，未截）。

## 4. 页面结论（逐页可用性）

### Xfriend（QA fixture）
| 页面 | 浅色 | 深色 | 备注 |
|---|---|---|---|
| 登录页（正式包） | ✅ | 未截 | 无账号也可展示；未做真实登录（超出 QA 范围） |
| 对话 | ✅ | ✅ | 空 store 空态正常（"请先配置 API 密钥和模型"横幅） |
| 工作台 | ✅ | ✅ | 空专家空态正常 |
| 资源 | ✅ | ✅ | 正常 |
| 我的 | ✅ | ✅ | 外观入口正常 |
| 外观 | ✅ | ✅ | 主题模式、主色 5 色板、预览、通知入口均正常；深色切换即时生效 |
| 跟随系统深色 | ✅ | — | `cmd uimode night yes` + 重启后自动深色 |

### Xsalers（QA fixture）
| 页面 | 浅色 | 深色 | 备注 |
|---|---|---|---|
| 今日 | ✅（页面）| ✅ | **底栏缺失 P0**；示例数据卡片完整 |
| 客户（CRM 概览） | ✅ | ✅ | 可从"查看全部"进入；分段/搜索/客户行正常；**底栏缺失 P0** |
| 客户档案 | ✅ | 未截 | 从客户 tab 点客户名可进入（见 §5 非问题说明） |
| 复盘详情 | ✅ | 未截 | 需滚动到"最近复盘"后点击进入 |
| 专家 / 我的 / 外观 | **未截（P0 阻断）** | 未截 | 底栏缺失导致无法进入 |
| 系统深色 | ✅ | — | 深色页面正常（画布/卡片/文字） |

---

## 5. 问题清单

### P0-1 XSalers 主壳底部 5-tab 导航栏完全未渲染（无法导航）
- **页面/截图**：`xs-today-light.png`、`xs-today-dark.png`（底部区域整片画布 `#F6F5F1`，无栏背景、无图标、无文字）
- **现象**（冷启动 QA fixture 必现，重装/强停重启/切换浅深色均复现）：
  - `uiautomator dump` 全页仅 68–72 个节点，**不含任何底栏/5 tab 文字节点**；底部唯一节点为系统手势区 `[0,2337][1080,2400]`（不可点击）。
  - 底栏应驻留区域（y≈2100–2400）像素扫描为纯画布色，**不是透明、也不是被手势条遮挡，而是根本没有渲染**。
  - 按 5 等分坐标（x=108/324/540/756/972）在 y=2242 与 y=2308 逐点点击，标题始终停在「今日」，页面无任何变化 → 5 个主 tab 全部不可达。
- **期望**（spec §2.4 / §4.11）：M3 NavigationBar 风格底栏，4/5 项、80dp+insets、毛玻璃与选中主色胶囊；Xfriend 侧同组件表现正常（对照 `xf-*.png`）。
- **根因（已定位并经 Lead 确认）**：`app/src/main/java/com/singu/xfriend/SalesScreen.kt:146` 的 `if (!deep && !WindowInsets.isImeVisible) ProductBottomBar(...)` —— `WindowInsets.isImeVisible` 在该组合（QA ComponentActivity、targetSdk 34、API 35 模拟器）下误报 true，导致整条底栏被跳过；`deep` 初值均为 false，可排除。对照 Xfriend `AssistantScreen.kt:91` 使用 `WindowInsets.ime.getBottom(LocalDensity.current) > 0`，同一设备下正常。
- **建议修复**：改为与 Xfriend 一致的 IME 判断（`ime.getBottom(density) == 0`）。
- **状态**：✅ 已修复（重编中），待回归验证。

### P1-1 分段控件（XfSegmented）选中项阴影渲染为较重灰色描边
- **页面/截图**：`xf-workbench-light.png`（「专家」选中）、`xf-appearance-light.png` / `xf-appearance-dark.png`（「跟随系统」/「深色」选中）、`xs-customer-detail-light.png`（「概览」选中）
- **现象**：选中段为白底+约 2–4px 灰色圆角"外圈"，在浅色 `surface2` 上像重描边/描边发光，与设计稿 "surface + shadow-1（极轻 0 1px 2px rgba(24,22,16,.05)）" 不一致；深色下也可见灰边。
- **期望**（spec §4.5 `.seg-btn`、§4.2 卡片语言）：选中为白底 + 极轻投影或 1px `line` 细边框，不应出现明显灰色外圈。
- **根因（已定位）**：`XfComponents.kt:651` `Modifier.background(colors.surface).shadow(1.dp, RoundedCornerShape(9.dp))` —— Compose elevation 阴影在浅色背景上呈现为灰色光晕，偏离 CSS 阴影语义。
- **建议修复**：改为 `1.dp` `colors.line` 细边框（本次 Lead 已按此修复）；或 shadow 使用透明 ambient/spot 色并降为 0dp。
- **状态**：✅ 已修复（重编中），待回归验证。

### P2-1 底栏高度偏低、tab 文字距手势区偏近（观感偏紧）
- **页面/截图**：`xf-chat-light.png`、`xf-workbench-light.png`、`xf-profile-light.png` 等所有含底栏的 Xfriend 页面
- **实测**（1080×2400 @420dpi，1dp=2.625px；数据取自 `xf-chat-light.png` 像素扫描 + `uiautomator dump`）：
  - 底栏白色背景：y=2166（1px `line` 顶边）→ y=2337，合计 **171px ≈ 65.1dp**（`bottomBarHeight=64dp`+1dp 分割线）；
  - tab 文字（11sp nav label）ye 2273–2313，**文字底边距白色栏底 24px ≈ 9.1dp**；
  - 其下 y=2337–2400 为 24dp 系统手势区（画布色）；手势胶囊 y≈2374–2381，x=440–638；
  - 文字底边 → 手势区上沿 **24px ≈ 9.1dp**；→ 手势胶囊顶 **61px ≈ 23.2dp**；→ 屏幕底 **87px ≈ 33.1dp**。
- **期望**（spec §2.4）：底栏 `NavigationBar 80dp + 系统 insets`，内容与手势区保持更从容的间距。
- **判定**：无重叠/遮挡/裁切，属观感与规范偏差 → **P2**（若产品强调"贴手势条"观感可升 P1，由 Lead 决定）。
- **建议修复**：`XfDesign.kt:315 bottomBarHeight 64.dp → 80.dp`（或底栏内容增加 8dp 底部内边距）。
- **状态**：✅ 已修复（64→80dp，重编中），待回归验证。

### 非问题（已复核，不计缺陷）
1. **XSalers 客户详情"点不开"**：早期 `xs-customer-detail-light.png` 与 CRM 列表页字节完全相同，复核为**截图脚本点击坐标落在 CRM「待办」tab 行**，非产品问题。切换到「客户」tab 后点击客户名（420dpi 下坐标约 (390,950)）可正常打开「客户档案」页（`xs-customer-detail-light.png`，header=客户档案）。报告保留正确截图。
2. **QA 顶部提示条**（紫色底「界面验收 · 独立 QA 安装 · 示例数据」）：仅存在于 QA fixture 构建，使用 `primaryContainer` 配色，不属产品界面；正式 APK 无此条。

---

## 6. 视觉系统对照（spec §1/§2/§4）

| 检查项 | 结论 | 证据/实测 |
|---|---|---|
| 暖纸画布 light `#F6F5F1` | ✅ | `xf-chat-light.png` 采样 `#F6F5F1`（占比 41.0%）、`xs-today-light.png` 采样 `#F6F5F1`（占比 64.3%） |
| 白色卡片 `#FFFFFF` + 1px `#E5E2DA` 细边框 | ✅ | 卡片实测 `#FFFFFF`；像素扫描可见 `#E5E2DA` 1px 描边/分割线 |
| 深色画布 `#0E0F12` / 卡片 `#181A1F` | ✅ | `xf-chat-dark.png` / `xs-today-dark.png` 采样完全命中（dark canvas 占比 39.2% / 56.6%） |
| 单一主色 | ✅ | XFriend 蓝 `#5267E8` / `#4050E8` 量化簇；XSalers 紫 `#7055DE` / `#6040D0` 量化簇；图标/按钮/选中态一致 |
| 圆角层级（卡 14 / 控件 10 / 按钮 8） | ✅ | 目检卡片 14dp、分段外层 12dp/内 9dp、按钮圆角一致，无明显混用 |
| 字号层级（PageTitle 24 / Section 17 / Body 15 / Caption 11） | ✅ | 标题/区块/正文/辅助层级清晰；无 <11sp 正文（导航 label 11sp） |
| 底栏毛玻璃/半透明 + 选中胶囊 | ⚠️ Xfriend ✅ / XSalers 缺失（P0） | Xfriend：半透明白底 + 1px 线 + 主色柔胶囊（选中项）；XSalers：无底栏 |
| 线性图标语言 | ✅ | 导航/工具条/列表行图标均为线性 Outlined 风格，描边粗细一致 |
| 深色模式文字可读性 | ✅ | 深色下正文 `#F1F2F4`/`#AAAEB8` 层级可读，状态 chip 语义色可辨；未见低对比正文 |
| 重叠/截断/错位 | ✅（除 P0） | 已审页面无重叠、无文字截断、无贴边错位；Xfriend 底栏文字与手势条间距见 P2 |
| 触摸目标 | ✅（抽查） | 底栏 tab 可点击区高约 55dp（≥48dp）；列表行 ≥56dp；未发现过小触控区 |

---

## 7. 运行与崩溃（logcat / ANR）

- 全程 `logcat -d` 过滤 `FATAL EXCEPTION|AndroidRuntime.*FATAL|ANR in|am_anr|Process: com.singu`：**Xfriend 0 命中、XSalers 0 命中**（完整日志：`_logs\logcat-xfriend-full.txt`、`_logs\logcat-xsalers-full.txt`）
- 两 App 冷启动均 `Status: ok`（模拟器冷启动 7–12s），无崩溃、无 ANR、无白屏卡死。
- 一次性环境异常：初始化改 `wm size` 后 SystemUI 曾弹 "System UI isn't responding"，点 Wait 后恢复（`setup-boot.png` 中可见该对话框）；此后所有验收过程稳定。属模拟器 SystemUI 环境问题，与 App 无关，已记录。
- QA fixture 页面加载需要约 4–6s（示例数据注入），首张截图已按内容就绪条件等待，避免截到加载态。

## 8. 未覆盖 / 限制

1. **XSalers 专家/我的/外观页未截**：被 P0 底栏缺失阻断；修复回归时补验。
2. **XSalers 复盘详情深色未截**；客户档案深色未截（时间原因，浅色已覆盖该页）。
3. 未做真实登录/云同步/模型调用（QA fixture 无真实后端），仅验证 UI 与导航；示例数据均来自 fixture。
4. 未做像素级 diff/自动化对比（无基线图）；视觉判断基于人工目检 + 像素采样统计 + spec 文本。
5. AVD 为 Pixel Tablet 面板经由 `wm size/density` 模拟手机形态（411×914dp），与真实手机存在硬件级差异（字体渲染、系统栏细节），结论适用于视觉/导航验收，不替代真机验收。

## 9. 回归清单（最终 APK）

| # | 回归项 | 验证方法 | 通过标准 |
|---|---|---|---|
| 1 | **P0** XSalers 底栏出现且可切换 | 冷启动 QA fixture → `uiautomator dump` 应含 5 个 tab 文本节点 → 依次点击 5 tab 截图「今日/客户/复盘/专家/我的」 | 5 个 tab 均存在、均能切页，截图留证 `xs-tab1..5` |
| 2 | **P0 修复后补截** XSalers 我的→外观 浅/深 | 我的 tab → 外观 → 点深色 | 入口存在、深色生效，截 `xs-appearance-light/dark.png` |
| 3 | **P1** 分段控件灰边消失 | 重截 Xfriend 工作台（专家段）与 Xsalers 客户/客户详情（概览段） | 选中项为 1px 细边框，无灰色光晕 |
| 4 | **P2** 底栏高度 80dp | 重截 Xfriend 对话/工作台底栏，量白栏高度与文字-手势区间距 | 白栏≈213px（80dp@2.625）且文字距手势区更从容 |
| 5 | 关键页面回归 | Xfriend 对话/我的/外观深色；Xsalers 今日/客户 | 无布局回归、无崩溃 |
| 6 | 崩溃检查 | `adb logcat -d` 过滤 FATAL/ANR | 0 命中 |

## 10. 附件

- 截图：本目录 `*.png`（见 §3 索引，均真实 `adb screencap`）
- 日志：`_logs\install.txt`、`_logs\logcat-xfriend-full.txt`、`_logs\logcat-xsalers-full.txt`、`_logs\env-check.txt`
- 原始 UI dump / 校准截图：`_raw\`（含 uiautomator xml、无效截图归档 `invalid-*`）
- 工具脚本：`_tools\`（`qa-lib.ps1`、`shot.ps1`、`tab.ps1`、`ui.ps1`、`env-check.ps1`、`phone-size.ps1`、`img-stats.py`）；环境准备说明见 `prep.md`

---

## 11. 最终构建回归（修复后构建，结论：**通过**）

- 回归时间：2026-10-18 00:29–00:40（Asia/Shanghai）
- 环境：同一模拟器 `emulator-5554`，1080×2400 @420dpi（411×914dp），Android 15/API 35
- 目的：验证 P0（XSalers 底栏）、P1（分段灰边）、P2（底栏 80dp）三项修复

### 11.1 最终包校验（SHA-256 与 Lead 提供值逐一比对通过）

| 包 | 大小 | SHA-256 | versionCode/Name | 安装 |
|---|---|---|---|---|
| XFriend 正式（最终） | 26,451,921 | `46693C8F2A57BBC1161F43318533235B470234B67B31F38FF2DD97EC2845457E` ✅ | 11 / 0.11.0 | Success |
| XSalers 正式（最终） | 26,451,941 | `E4C4A9A7C36124F0E55E64B725272476BB2291114CCA230A3F3D2BED323CA0C6` ✅ | 11 / 0.11.0 | Success |
| Xfriend QA（修复后） | 26,468,093 | `C6EAB9646C57C0806203E0308DEF8C0CDF4F6DE60F35A9C28006C45AAAAFBB44` ✅ | 11 / 0.11.0 | Success |
| Xsalers QA（修复后） | 26,468,205 | `0B53FF5FE77D41E000A01321C9450D575EB65E2B87A25116435FB951AF41DBF8` ✅ | 11 / 0.11.0 | Success |

> 修复前构建 SHA 见 §2（已作废，仅作历史记录）。

### 11.2 回归截图（真实 adb screencap，1080×2400）

| 文件 | 验证点 | 结论 |
|---|---|---|
| `xs-today-reg.png` | XSalers 冷启动底栏出现、5 项、今日选中胶囊 | ✅ P0 通过 |
| `xs-customers-reg.png` | 点「客户」tab 切换成功 | ✅ |
| `xs-review-reg.png` | 点「复盘」tab 切换成功 | ✅ |
| `xs-experts-reg.png` | 点「专家」tab 切换成功 | ✅ |
| `xs-profile-reg.png` | 点「我的」tab 切换成功 | ✅ |
| `xs-appearance-reg-light.png` | 我的→外观（滚动后入口可达；主题模式/主色/预览正常，底栏保留） | ✅ P0 修复后入口可达 |
| `xs-appearance-reg-dark.png` | 外观点「深色」后深色生效、文字可读、分段细边框 | ✅ |
| `xf-chat-reg.png` | XFriend 底栏加高后的对话页 | ✅ P2 |
| `xf-workbench-reg.png` | 工作台分段「专家」选中项 | ✅ P1 通过（细边框、无灰晕） |

### 11.3 量化验证

| 项目 | 修复前 | 修复后（实测） | 判定 |
|---|---|---|---|
| XSalers 底栏 | 完全未渲染（0 tab 节点，纯画布） | uiautomator 检出 5 个 tab：今日/客户/复盘/专家/我的；label bounds y=2252–2292；点击带 y=2160–2305（145px≈55dp）；可逐一切换 | ✅ P0 通过 |
| XfSegmented 选中项 | `shadow(1.dp)` 灰色光晕 | 白底 + 1dp `line` 细边框（`xf-workbench-reg.png`、`xs-appearance-reg-light/dark.png` 目检一致） | ✅ P1 通过 |
| Xfriend 底栏高度 | 白栏 171px ≈ 65.1dp | 顶线 y=2124，白栏 y=2127→2336 = **210px ≈ 80dp**（@420dpi 2.625px/dp） | ✅ P2 通过 |
| Xfriend 底栏与手势区 | label 底边距栏底 9.1dp | 白栏 80dp，手势胶囊 y≈2363–2373 位于其下 24dp 系统手势区，间距观感改善 | ✅ P2 通过 |
| 崩溃/ANR（logcat） | 0 命中 | **0 命中**（`_logs\logcat-final-regression.txt`） | ✅ |

### 11.4 回归过程中发现/澄清

- **外观入口恢复可达**：QA 构建「我的」页为长列表，入口位于列表偏下（`SalesScreen.kt:412` `SalesSettingRow(XfIcons.Palette, "外观", ...)`），向下滚动即可见并正常进入；`xs-appearance-reg-light/dark.png` 为滚动后真实截图。首轮回归脚本报 `appearance entry missing` 系**未滚动导致的脚本误报**，非产品缺陷。
- 回归未新增问题；既有标注（如 XSalers 复盘详情深色未截）不影响本结论。

### 11.5 最终结论

**回归通过，可以交付。** P0（XSalers 底栏 5 tab 导航）、P1（分段控件灰边）、P2（底栏 80dp）三项修复均已在最终构建上验证生效；两个正式包与两个 QA 包 SHA-256 与交付声明一致、`versionCode 11 / versionName 0.11.0`；回归期间无 FATAL EXCEPTION / ANR，未发现新问题。

