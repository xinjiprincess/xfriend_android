# Xfriend / Xsalers Android Suite 0.19

当前开发版本为 **0.19.0 / versionCode 19**：右上角新增通话入口与“正在和星助手对话”界面；聊天回复改为等待完整消息，超过 10 秒显示“正在处理消息…；新增账户级持久化长期记忆（偏好、个人标签、长期约定、重要事项），后台调用当前模型整理并注入系统提示词，用户可查看/删除/清空。使用方式与边界见 [0.19 说明](docs/MOBILE-0.19.md)。保留 0.18 的启动权限、画中画小窗、提示音、协议兼容和预算修复。调试包沿用 `.debug` 标识与本机签名，可覆盖上一轮调试包升级。以下 0.18 及更早记录保留作历史快照。

0.18 历史源码为 **0.18.0 / versionCode 18**：启动权限、画中画小窗、发送/回复提示音、OpenAI-compatible 协议兼容和根任务预算修复。见 [0.18 说明](docs/MOBILE-0.18.md)。

0.17 历史源码为 **0.17.0 / versionCode 17**：普通请求默认执行；连续输入进入可见收件箱并成为独立根任务，最多 3 槽并发/排队；回答按请求绑定；任务管理器默认隐藏并支持明确的显示/隐藏命令；日期型事项可跨会话查询；agent 在执行层不能修改软件自身配置。使用方式、验收证据和未验证项见 [0.17 说明](docs/MOBILE-0.17.md)。

0.16 历史源码为 **0.16.0 / versionCode 16**：新增桌面供应商 API / 订阅账号接入、四类推理协议、手机事项/文件/定时通知与无障碍控制入口，使用方式和实际边界见 [0.16 说明](docs/MOBILE-0.16.md)。

2026-10-29 接续：星助理单入口修复团队历史、消息引用与重试恢复，ASR 凭据输入改为密码掩码。Linux 完整校验入口为 `bash scripts/build-linux.sh`；普通调试包使用独立 `.debug` 包名。产物、验证结果和未完成事项见 [接手记录第 11 节](docs/CODEX-HANDOVER-2026-10-28.md#11-linux-接续开发与-apk-交付2026-10-29asiashanghai)。

0.14 历史源码为 **0.14.0 / versionCode 14**：Xfriend 增加可在手机组织执行的持久专家群聊，继承本机 Agent、模型工具循环、取消与检查点恢复，并保留电脑／云协作入口。使用方式和本轮构建、测试及安装包证据见 [手机版 0.14 说明](docs/MOBILE-0.14.md)。以下 0.12 内容保留为历史交付快照。

## 0.12 历史交付快照

> **历史版本：0.12.0**（versionCode 12，2026-10-18）  
> 本次为 **XSalers 功能版**：新增「通话录音接管」——接管系统/录音机已保存的通话录音，复用既有转写与复盘链路；视觉系统沿用 0.11，不新增设计令牌或组件。XFriend 无新增功能。  
> **交付状态：正式 APK 已构建**（2026-10-18 03:28，debug 双 flavor，无 fixture）；Xfriend 26,499,349 bytes / SHA-256 `C3457F4301E1814D824A31BA34ABE63CA838DE78FA02E5A1FEF1740D0AA33C63`，Xsalers 26,499,509 bytes / SHA-256 `8B6FB83D686142C87478B7CE82FCF155E77829706932AC4015B41861B2CFC9C8`；两 flavor 单测 600 项通过，模拟器界面/接管/去重/删除链路验证通过。

0.12 的入口：XSalers「今日 → 接管通话录音」，或「我的 → 通话录音接管」。它只读取已保存到磁盘的通话录音，**不做通话中录音**，也不新增后台常驻服务。功能与边界详见 [docs/CALL-RECORDING-0.12.md](docs/CALL-RECORDING-0.12.md)，交付快照见 [docs/XFAMILY-0.12-交付说明.txt](docs/XFAMILY-0.12-交付说明.txt)。

## 0.12 新增：通话录音接管

- **扫描**：读取系统音频库（MediaStore，需音频读取权限）与 App 私有 `recordings` 目录；按文件名/目录关键词、电话号码、日期时间识别疑似通话录音。
- **接管**：用户勾选后复制或解码为 PCM16 WAV 到 App 私有目录，生成 `kind=audio` 的沟通记录；能解析出客户电话时自动匹配客户。
- **转写**：复用既有模型链路；配置模型/试用额度后自动转写，未配置时只导入并提示后续转写。
- **清理**：文字保存后出现「待清理源录音」；二次确认后删除源音频，文字与复盘报告保留。Android 30+ 的系统媒体库删除走系统确认弹窗。
- **边界**：不支持通话中录音；无后台定时/常驻扫描；单条录音 ≤ 4 小时、≤ 4 GB，候选最多 200 条；接管/复制/解码在本机完成，转写沿用现有模型链路会把音频发送给所配置的 ASR 服务（默认 MiMo 云端），不写入本应用自有服务器。

新代码集中在 `SalesCallRecordings.kt`、`SalesCallRecordingsScreen.kt`、`SalesViewModel.kt` 的通话录音段，以及 `Communication` / `SalesData` 的兼容字段；完整清单见 [docs/CALL-RECORDING-0.12.md](docs/CALL-RECORDING-0.12.md) §7。

## 0.12 交付状态

- **正式 APK 已构建**（2026-10-18 03:28，debug 双 flavor，无 fixture）：Xfriend `D:\xfriend-suite\.artifacts\0.12-release\Xfriend-0.12.0.apk`（26,499,349 bytes，SHA-256 `C3457F4301E1814D824A31BA34ABE63CA838DE78FA02E5A1FEF1740D0AA33C63`，mtime 03:28:27）；Xsalers `...\Xsalers-0.12.0.apk`（26,499,509 bytes，SHA-256 `8B6FB83D686142C87478B7CE82FCF155E77829706932AC4015B41861B2CFC9C8`，mtime 03:25:49）；均已复制到 `D:\build_apk`。
- **构建**：`build-0.12-release.log`，BUILD SUCCESSFUL in 13m 58s，93 actionable tasks: 45 executed / 48 up-to-date，0 compile errors / 16 warnings；同一日志含两 flavor 单测。
- **测试**：Xfriend 300 项、Xsalers 300 项，合计 **600 项，0 failures / 0 errors**（各 38 suites，2026-10-18）；0.11 基线 574 项。
- **模拟器 E2E**：授权前后、候选、接管（「待分析」1→2）、去重、删除源录音（已转写种子记录）均验证；删除后外部文件消失、文字/报告保留、无崩溃。fixture 无法配置模型，真实 ASR 转写未端到端验证。
- **截图**：`docs/qa-0.12/callrec-01-home.png` ~ `callrec-07-deleted.png` 共 7 张已回填；`callrec-06-pending-delete.png` 是 `callrec-04-granted.png` 底部「待清理源录音」卡片的裁剪（1080×540），非独立全屏截图。
- **lint**：未执行（非阻断）。

```powershell
# 0.12 正式构建 + 两 flavor 单测（实际执行命令；日志 build-0.12-release.log）
.\gradlew.bat --no-daemon --max-workers=4 -Pkotlin.compiler.execution.strategy=in-process -PsalesUiFixture=false :app:assembleXfriendDebug :app:assembleXsalersDebug :app:testXfriendDebugUnitTest :app:testXsalersDebugUnitTest --console=plain
```

> `scripts\release-0.11.ps1` 是 0.11 的版本固定脚本（产物/日志名仍为 0.11），不要直接用于 0.12；0.12 构建、复制到 `D:\build_apk` 与证据回填已完成。正式包不要加 `-PsalesUiFixture=true`。

## 沿用的 0.11 视觉系统

0.11 按 [Singu Xfriend 前端重塑概念稿 v1](D:/octop/docs/design/redesign-concept.html) 与 [Android 设计规范](docs/design/redesign-concept-android-spec.md) 落地共享设计系统；0.12 未改视觉，仅复用：

- **令牌层**：`XfDesign.kt` 提供亮/暗 `XfColors`、`XfType`、`XfDimens`、`XfShapes`、`XfElevation`；页面不直接写颜色/字号。浅色画布 `#F6F5F1`、表面 `#FFFFFF`、主文字 `#1B1C1F`、线 `#E5E2DA`；深色 `#0E0F12` / `#181A1F` / `#F1F2F4` / `#282A31`。
- **主色 5 组**：靛蓝 `#4F5DF0` / 青绿 `#0E9384` / 琥珀 `#B26B00` / 玫瑰 `#D94F6B` / 紫罗兰 `#7055DE`；XFriend 默认靛蓝，XSalers 默认紫罗兰，可在「我的 → 外观」切换。
- **主题**：跟随系统 / 浅色 / 深色；本机 `SharedPreferences`（`xf_appearance`）持久化，不进入账号同步。
- **图标与组件**：60 个手绘 24 视窗 / 1.75 stroke 线性图标；28 个 `Xf*` 组件（卡片、按钮、状态 chip、列表行、输入框、分段控件、弹窗、空状态等）。旧 `ProductBrand` / `ProductAppearance` / `FamilyComponents` 保留兼容层。
- **壳层**：`XfTopBar` 56dp；底栏 80dp 内容高 + 1dp 顶线 + Haze 毛玻璃 + 选中主色胶囊，保留系统导航栏 insets。

## 两个 App

| 应用 | 包名 | 主要能力 |
|---|---|---|
| Xfriend | `com.singu.xfriend.mobile` | 对话与多轮任务、专家/团队、技能与知识、工作区资源、本机模型配置、电脑配对与远程任务、后台任务与检查点、账号同步与迁移 |
| Xsalers | `com.singu.xsalers.mobile` | 今日工作台、客户与 CRM、录音/截图/文本导入与复盘、**通话录音接管（0.12 新增）**、客户档案、AI 专家设计、专家与团队、云同步与迁移 |

两款应用独立安装、独立保存登录状态/媒体/模型密钥/工作空间；共用 Kotlin + Jetpack Compose 工程，通过 Gradle product flavors 构建。开发签名；覆盖安装同签名旧版即可升级，**不要先卸载**，以免清除本机数据。

## 构建环境

JDK 20、Android SDK 34、minSdk 26（Android 8+）、compileSdk/targetSdk 34、Kotlin 1.9.22 / AGP 8.5.0、Compose BOM 2024.06.00。

```powershell
# 完整验证链（test + assemble + lint）
.\build.ps1 -JavaHome 'C:\Program Files\Java\jdk-20'
```

产物位置与历史构建证据：

- Gradle 输出：`app\build\outputs\apk\xfriend\debug\app-xfriend-debug.apk`、`app\build\outputs\apk\xsalers\debug\app-xsalers-debug.apk`。
- 0.12 交付副本：`D:\build_apk\Xfriend-0.12.0.apk` / `D:\build_apk\Xsalers-0.12.0.apk`，与 `.artifacts\0.12-release\` 同文件同 SHA-256（见上）。
- 0.12 构建证据：`build-0.12-release.log`（BUILD SUCCESSFUL 13m58s，93 tasks）；QA fixture `D:\xfriend-suite\.artifacts\0.12-qa\Xsalers-0.12-qa.apk`（28,336,288 bytes），仅界面验收用、不对外。
- 0.11 历史交付与证据：`D:\build_apk` 中 0.11.0 APK、`build-0.11-final.log`、`.artifacts\0.11-release\`；仅历史追溯，不代表 0.12 状态。
- QA fixture：独立 `.qa` 包名（`com.singu.xfriend.mobile.qa` / `com.singu.xsalers.mobile.qa`），仅用于模拟器截图验收，不属于交付安装包。

## 目录

```
D:\xfriend-suite\                    手机端主工程
  app\src\main\java\com\singu\xfriend\  页面、Xf 设计系统、运行时、CRM、通话录音接管、同步、任务
  app\src\main\res\                    图标、图片与 Android 资源
  branding\                            品牌与图标素材
  desktop-bridge\                      独立 Python 连接桥（历史/辅助）
  trial-gateway\                       试用模型代理、额度与复盘服务
  scripts\                             构建/交付/QA 辅助脚本（release-0.11.ps1 为 0.11 版）
  docs\                                交付、设计、QA、接口与交接文档
    CALL-RECORDING-0.12.md              0.12 通话录音接管功能说明
    design\redesign-concept-android-spec.md   0.11 设计规范（0.12 沿用）
    design\screens\                           概念稿参考截图（23 张）
    qa-0.11\                                 0.11 模拟器 QA 证据
    qa-0.12\                                 0.12 QA 截图（7 张，含 06 裁剪图）
    XFRIEND-REDESIGN-0.11.md                 0.11 视觉落地说明
    README.md                                 文档索引
  build.ps1                            完整验证入口
D:\build_apk\                        面向用户的安装包与交接文件
D:\octop\                            电脑版（Python API、React 控制台、Go 桌面壳）
D:\xfriend-activation\               账号、激活与同步服务
```

## 文档

| 文档 | 内容 |
|---|---|
| [docs/README.md](docs/README.md) | 文档索引与阅读顺序（当前 0.12 / 历史 0.11 及更早） |
| [docs/CALL-RECORDING-0.12.md](docs/CALL-RECORDING-0.12.md) | 0.12 通话录音接管：能力边界、流程、权限隐私、数据兼容、代码与验证 |
| [docs/XFAMILY-0.12-交付说明.txt](docs/XFAMILY-0.12-交付说明.txt) | 0.12 交付快照：版本、功能、证据、APK 实际值与未验证边界 |
| [docs/DELIVERY-0.12.html](docs/DELIVERY-0.12.html) | 0.12 交付报告（评审用，含 7 张截图与 APK 值） |
| [docs/verification-0.12.json](docs/verification-0.12.json) | 0.12 构建/测试/QA 证据结构化记录（实际值） |
| [docs/XFRIEND-REDESIGN-0.11.md](docs/XFRIEND-REDESIGN-0.11.md) | 0.11 视觉落地说明（0.12 沿用） |
| [docs/design/redesign-concept-android-spec.md](docs/design/redesign-concept-android-spec.md) | 概念稿逐项提取的 Android 设计规范 |
| [docs/DELIVERY-0.11.html](docs/DELIVERY-0.11.html) | 0.11 交付报告（历史） |
| [docs/qa-0.11/report.md](docs/qa-0.11/report.md) | 0.11 模拟器 QA 报告（历史） |
| [docs/XFAMILY-项目设计与开发交接文档-2026-10-17.html](docs/XFAMILY-项目设计与开发交接文档-2026-10-17.html) | 项目交接主文档（0.11 时点） |

## 验收边界（如实说明）

- **0.12 正式 APK 已构建**：`build-0.12-release.log` 13m58s / 93 tasks / 0 compile errors；Xfriend 26,499,349 bytes（SHA-256 `C3457F43…33C63`），Xsalers 26,499,509 bytes（SHA-256 `8B6FB83D…C9C8`）；实际值见 `verification-0.12.json`、`DELIVERY-0.12.html` 与交付说明。
- **0.12 测试通过**：Xfriend 300 + Xsalers 300 = **600 项，0 failures / 0 errors**；lint **未执行（非阻断）**。
- **0.12 QA 为模拟器人工验证**：授权/候选/接管（「待分析」1→2）/去重/删除源录音（已转写种子记录）已验；删除后外部文件消失、文字/报告保留、无崩溃。`06-pending-delete.png` 是 04 底部卡片裁剪；fixture 无法配置模型，真实 ASR 转写未端到端；模拟器不代表真机；MP3/高采样率/无权限/外部存储异常路径由 `SalesCallRecordingsTest` 单测覆盖。
- **0.11 历史状态**：正式构建通过（`build-0.11-final.log`，9m46s），模拟器 QA 回归通过（`docs/qa-0.11/report.md` §11）；0.11 的 APK/SHA 见 [docs/XFAMILY-0.11-交付说明.txt](docs/XFAMILY-0.11-交付说明.txt)。
- **业务边界沿用既有版本**：手机为 Kotlin 编排 + 模型 API，不含完整电脑版 Python 运行时；云端智能体执行、XFriend 免密钥试用、团队并行研究、手机会话迁移电脑、邮件发送、群聊/会议均未实现或未验收。
- **音频与隐私**：接管、复制与解码在本机完成，不写入本应用自有服务器；转写沿用现有模型链路，会把音频发送给所配置的 ASR 服务（默认 MiMo 云端），不是端到端离线方案。详见 [docs/CALL-RECORDING-0.12.md](docs/CALL-RECORDING-0.12.md) §5。
- **交付形态**：开发签名 APK，非应用商店发布包；服务端部署、桌面安装包、生产签名与上架不在本次范围。
