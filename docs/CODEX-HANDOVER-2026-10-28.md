# XFriend 星助理 · 完整接手文档

版本：2026-10-28 22:30（Asia/Shanghai）
上一轮执行者：开发代理（Lead）
接手对象：Codex 或其他代码代理
仓库：`D:\xfriend-suite`

---

## 0. 一句话现状

手机版「星助理单入口」的界面、会话、消息级回复数据链路、任务队列、设置与语音适配器**已完成并两次编译通过**；上一轮卡住的「测试连接按钮没有可见反馈」**已在代码层修复并提交**（失败/成功都会在配置页显示横幅，并带日志点），但**修复后的 APK 还没有在模拟器上点开验证**，真实模型对话也仍然缺中转站配置。ASR 未接真实服务。

也就是说：**代码是干净的、可编译的；验证链条差最后一段真机/模拟器点击。**

---

## 1. 用户目标与硬边界

目标（来自 XFriend 开发文档 01–04，v1.0 / 2026-10-27）：

- 打开手机版直接进入星助理主会话，不出现专家/团队/会议列表，也不出现「会话/记忆/任务」三底栏。
- 默认接入自有中转站，真实对话；个人调试版可切换其他 OpenAI 兼容服务。
- 中文语音：录音 → 停止 → 转写 → 可编辑 → 用户发送。
- 针对具体消息回复，引用关系持久化。
- 按需创建子代理，独立 context 与 checkpoint，前台只展示任务卡片。
- 断网/重启后可恢复会话与任务；不把静态界面或 Mock 当完成。

硬约束：

- 不伪造测试；没有环境就标 BLOCKED / NOT VERIFIED。
- 密钥不进仓库、日志、导出、安装包静态配置；只进平台安全存储。
- 不执行 `git reset` / `git clean` / 覆盖用户已有修改。
- 调试包独立标识；不关闭 TLS/鉴权/审批。
- 设计令牌唯一来源：`app/src/main/java/com/singu/xfriend/XfDesign.kt`；组件在 `XfComponents.kt`。

原型参考：`docs/XFRIEND-STAR-ASSISTANT-PROTOTYPE-2026-10-27.html`（8 节：目标、信息架构、5 台静态手机、可交互原型、**第 05 节并发任务**、状态与错误、A01–A20、落地对应）。

---

## 2. 仓库与构建事实

| 项 | 事实 |
| --- | --- |
| 工作目录 | `D:\xfriend-suite` |
| 版本控制 | **本轮已初始化 git**（用户同意）。`main` 分支，两个提交见下 |
| 提交 | `6f4d515` baseline（v0.14.0 + 星助理 WIP）；`674d708` fix(settings): surface model save/test results |
| 技术栈 | Kotlin 1.9.22、AGP 8.5.0、Compose BOM 2024.06.00、minSdk 26、compile/target 34 |
| 变体 | `xfriend`（`com.singu.xfriend.mobile`）、`xsalers`（`com.singu.xsalers.mobile`） |
| 版本 | versionCode 14 / 0.14.0 |
| JDK | PATH 的 java 是 zulu-17，`JAVA_HOME` 是 zulu-11（AGP 不接受）。**必须**用 `C:\Program Files\Java\jdk-20` |
| 构建 | `$env:JAVA_HOME='C:\Program Files\Java\jdk-20'; .\gradlew.bat --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleXfriendDebug` |
| 产物 | `app\build\outputs\apk\xfriend\debug\app-xfriend-debug.apk`（2026-10-28 17:35，28.0 MB） |
| 完整校验 | `.\build.ps1`（test ×2、assemble ×2、lint ×2，约 10 分钟，**本轮未跑**） |
| 模拟器 | AVD `Pixel_9`（1080×2424），当前**未运行** |
| 安装 | `adb install -r app\build\outputs\apk\xfriend\debug\app-xfriend-debug.apk` |
| 调试启动 | `adb shell am start -n com.singu.xfriend.mobile/com.singu.xfriend.MainActivity --ez local_workspace true` |

注意：`--ez local_workspace true` 只在 `BuildConfig.DEBUG` 生效，用于跳过账号墙验证单入口；正式包仍走登录。

仓库里有两个**不是本轮产生**的未跟踪文件：`beach-bike.html`、`beach-bike/`。不要删除、不要提交。

---

## 3. 本轮改动

### 3.1 相对 baseline 的提交 `674d708`

| 文件 | 改动 |
| --- | --- |
| `app/src/main/java/com/singu/xfriend/MainActivity.kt` | ① 新增 `android.util.Log`；② 新增 `modelNotice` 状态（配置页提示）；③ 重写 `testModel()`：入口/成功/失败写 `Log.i("XFriendModel", …)`（只记 host、model、hasKey 布尔，不记 Key 与正文），失败时把错误写进 `modelNotice`；④ 重写 `saveModel()`：不再走无反馈的 `change()`，直接 `persist` 并在成功后提示「已保存到本机安全存储：<url> · <model>」，失败走 `reportFailure` |
| `app/src/main/java/com/singu/xfriend/FriendChatScreen.kt` | ①「测试连接」按钮加 `loading = vm.testingModel`；② 按钮下新增 `XfBanner`：`modelNotice` 优先、`error` 兜底，成功/失败/中性三种 tone |

### 3.2 星助理整体改动地图（baseline `6f4d515` 内）

**新增文件**

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/com/singu/xfriend/StarScreen.kt`（558 行） | `StarChatPage`(:314)、`StarSettingsPage`(:481)、`StarHeader`、`StarTray`、`StarUserMessage`/`StarAssistantMessage`、`StarWelcome`、`StarRunCard`、`StarComposer`、`StarRecordingBar`、`StarHistorySheet`、`StarQuote`。回复通过长按气泡 →「回复这条」触发 |
| `app/src/main/java/com/singu/xfriend/StarVoice.kt`（137 行） | `StarVoiceRecorder`(:29)：MediaRecorder AAC 16 kHz 单声道、60 s 上限、临时文件结束即删；`starTranscribe`(:81)：OpenAI 兼容 `/audio/transcriptions`、9 类错误分类；`starAsrHint`(:129) |

**修改文件**

| 文件 | 关键点 |
| --- | --- |
| `Store.kt` | `Message` 增 `id/replyToId/replyToRole/replyToPreview`；`StarReply`；Store 增 `starUi/asrUrl/asrModel/asrKey`；JSON 与 secrets 同步 |
| `FriendRun.kt` | `replyTo*` 三字段 + encode/decode（:27–29、:55、:72） |
| `FriendRunQueue.kt` | `enqueue(..., reply)`(:30)，幂等键校验含 replyToId(:40)，落盘(:50) |
| `FriendRunRepository.kt` | `enqueue(... reply)`(:43)，retry 保留引用(:50) |
| `AgentRuntime.kt` | `pendingReply`(:113)；`quotedInput()`(:325)；user 消息带引用元数据(:384)；发送后清空(:385) |
| `FriendRunService.kt` | 执行前 `runtime.pendingReply = StarReply(...)`(:102) |
| `MainActivity.kt` | `replyTarget`(:70)；`send()` 携带引用(:197/206)，接收后清空(:211) |
| `AssistantScreen.kt` | `star` 分支(:78)；`tab:0` → `StarChatPage`(:191)；`settings` → `StarSettingsPage`(:199)；四页签与 13 个二级路由保留（回退开关） |
| `FriendChatScreen.kt` | `SettingsPage`(:820) 增「星助理单入口」开关；模型配置/测试/导出脱敏诊断沿用 |
| `AccountScreen.kt` | 调试本地工作区入口(:92–101)；**修复** `remember` 内先 `bindAuthorization` 再创建 VM，避免 generation 竞争导致写操作静默失败 |
| `AndroidManifest.xml` | 增加 `RECORD_AUDIO` |

**运行时数据（不入库）**

- `files/accounts/<64 位 hex scope>/xfriend.json`：会话、专家、配置，**无明文 Key**。
- 密钥经 AndroidKeyStore AES/GCM 加密，存于 `no_backup`。

---

## 4. 已验证的证据

1. **构建**：`BUILD SUCCESSFUL in 9m 39s`（2026-10-28 17:35，`:app:assembleXfriendDebug`，含本轮修复）。
2. **此前一轮安装验证**（修复前 APK）：`Streamed Install Success`；模拟器进入单入口首页（星标 +「星助理」+ 历史/设置；hero「打开就是星助理。」+ 4 条建议 + composer）。
3. **配置持久化**：点「保存配置」后 `xfriend.json` 落盘，内容示例：
   `{"experts":[...],"messages":{},"baseUrl":"https:\/\/api.openai.com\/v1","model":"gpt-4o-mini","modelUseTools":true,"conversationMode":"ask","starUi":true,"asrUrl":"","asrModel":"",...}`
   —— 无明文 API Key（A16 部分证据）。
4. **错误分支**：无凭据发送时出现「请先配置 API 密钥和模型（诊断编号 …）」（A04 的一个分支）。
5. **语音未配置**：设置页显示「未配置」徽标；模拟器无麦克风，录音/转写未验证 → BLOCKED。

### 尚未验证（重要）

- 本轮修复后的「测试连接」横幅与日志（编译通过，但**没有在设备上点过**）。
- 真实模型对话（缺中转站配置）。
- 消息级回复的端到端交互（长按菜单 → 引用条 → 发送）。
- 任务托盘/任务中心、重启恢复。
- `build.ps1`（lint + 单元测试）。

---

## 5. 下一步：把验证链闭合（约 30–40 分钟）

按顺序执行，全部命令在 `D:\xfriend-suite` 下：

```powershell
$sdk='C:\Users\zhang\AppData\Local\Android\Sdk'
$adb="$sdk\platform-tools\adb.exe"

# 1) 启动模拟器（无窗口）
Start-Process -FilePath "$sdk\emulator\emulator.exe" `
  -ArgumentList '-avd','Pixel_9','-no-window','-no-audio','-no-boot-anim','-gpu','swiftshader_indirect','-no-snapshot'
& $adb wait-for-device
# 等 sys.boot_completed=1
do { Start-Sleep 3; $b=(& $adb shell getprop sys.boot_completed).Trim() } while ($b -ne '1')

# 2) 安装并启动
& $adb install -r app\build\outputs\apk\xfriend\debug\app-xfriend-debug.apk
& $adb shell am force-stop com.singu.xfriend.mobile
& $adb shell am start -n com.singu.xfriend.mobile/com.singu.xfriend.MainActivity --ez local_workspace true

# 3) 打开设置 →「模型配置与连接测试」→ 填 Key →「保存配置」
#    预期：出现「已保存到本机安全存储：…」横幅
# 4) 点「测试连接」
#    预期：按钮短暂显示「测试中…」；随后出现：
#      - 成功：绿色「模型连接成功…」
#      - 失败：红色错误 + 诊断编号；logcat 中有 XFriendModel 记录
& $adb logcat -d -s XFriendModel | Select-Object -Last 20
```

如果「测试连接」仍无反应，说明点击根本没到 `onClick`，按以下顺序排查：

1. `adb shell input tap` 坐标是否与 `uiautomator dump` 的 clickable bounds 一致（1080×2424 全屏坐标，不是预览图坐标）；
2. 按钮是否因 `key.isNotBlank()` 为 false 而 disabled（页面里 `key` 来自 `vm.credentialDraft ?: s.apiKey`）；
3. `busy` 是否被上一次保存/发送挂住（logcat 里看 `change`/`send` 是否结束）；
4. 用 `Log.i` 点确认 `testModel()` 是否被调用。

---

## 6. 已知问题与阻塞

### P0

- **无可用中转站配置**：A02/A03/A05 无法验证。需要用户提供非敏感三项：`base_url`、`model_id`、凭据注入方式（Key 只进应用内安全存储，不贴对话/文档/仓库）。

### P1

| 项 | 状态 | 需要什么 |
| --- | --- | --- |
| A02 真实对话 | BLOCKED | 中转站 base_url + model + key |
| A04 错误分类 | 部分 | 逐项复现坏地址/坏 Key/缺模型/限流/额度 |
| A06 语音 | BLOCKED | 带麦克风真机 + ASR 服务 |
| A07/A08 回复 | 代码完成、未端到端 | 一次成功对话后的两条消息 |
| A09 幂等 | 部分 | 重复提交只产出一个 run（queue 已有 clientRequestId） |
| A10–A13 子代理/恢复 | 未实现 | P4；界面见原型第 05 节 |
| A14 调试隔离 | 部分 | scope 与本地入口已验证；签名/共存未验 |
| A15–A20 | 未验证 | 服务端与多设备 |

### P2

- `build.ps1` 未跑，lint/单元测试状态未知。
- 深色模式、平板布局、导出脱敏未过一遍。
- 任务托盘只读 `vm.runList`（500 ms 轮询）；子代理卡片与 checkpoint 恢复依赖 P4。
- `StarScreen.kt` 有若干未用参数/状态的编译 warning（无害，可清理）。

---

## 7. 后续阶段建议

1. **闭合验证链**（第 5 节）→ 把 PASS/FAIL 写进本文件附录。
2. **拿真实配置**跑 A02/A03/A04/A05，记录命令、时间、错误码、脱敏日志。
3. **A07/A08/A09**：回复关联、目标失效占位、重复提交幂等。
4. **ASR**（A06）：真机录音 → 转写 → 编辑 → 发送；取消/权限拒绝/无语音分支。
5. **P4 子代理**：持久队列已是 `FriendRunQueue`，但子代理 context、预算、父子取消、checkpoint 未实现；界面照原型第 05 节（任务托盘 + 任务面板 + 发送到）。
6. **收尾**：`build.ps1`、CHANGELOG、更新本文件。

---

## 8. 给 Codex 的提示词（直接复制）

```text
你是 XFriend 项目的接手续作者。仓库在 D:\xfriend-suite，已初始化 git（main 分支，最新提交 674d708）。
先读 docs/CODEX-HANDOVER-2026-10-28.md 与 docs/XFRIEND-STAR-ASSISTANT-PROTOTYPE-2026-10-27.html，再动手。

现状：手机版「星助理单入口」已完成并两次编译通过（Kotlin + Compose）。
上一轮修复了「模型配置页测试连接没有可见反馈」：FriendViewModel 增加 modelNotice 并在 MainActivity.kt 的
testModel()/saveModel() 中回显结果，FriendChatScreen.kt 的 SettingsPage 增加 XfBanner 与 loading。
但该修复尚未在模拟器上点开验证；真实模型对话仍缺中转站配置。

第一件事（不要改产品结构）：按下面步骤验证修复并给出证据。
1) 启动 Pixel_9 模拟器，安装 app\build\outputs\apk\xfriend\debug\app-xfriend-debug.apk；
2) adb shell am start -n com.singu.xfriend.mobile/com.singu.xfriend.MainActivity --ez local_workspace true；
3) 设置 → 模型配置 → 填 Key → 保存配置，预期出现「已保存到本机安全存储：…」；
4) 点测试连接，预期按钮短暂显示「测试中…」，随后出现成功/失败横幅；adb logcat -s XFriendModel 应有记录；
5) 若仍无反应，按文档第 5 节排查（坐标、key 为空、busy 挂住、onClick 未触发）。
把命令、时间、预期、实际、截图路径（或脱敏日志）写进交接记录。

环境：
- 构建必须用 JDK 20：
  $env:JAVA_HOME='C:\Program Files\Java\jdk-20'; .\gradlew.bat --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleXfriendDebug
- 完整校验：.\build.ps1（约 10 分钟）
- AVD：Pixel_9；不要动仓库里未跟踪的 beach-bike.html / beach-bike/。

通过后继续：用真实中转站配置跑 A02（流式与终态一致）、A04（错误分类）、A07/A08（消息级回复）、A09（幂等）。
每条给命令、预期、实际；没有环境就写 BLOCKED 与所需输入，不要用 Mock 替代。

硬约束：不使用 git reset/clean；不覆盖用户改动；不伪造测试；密钥不入库不入日志；不关闭 TLS/鉴权/审批；
未经确认不更换框架或删除旧业务数据。结束时按交接表（第 9 节）输出，并给出 P4 子代理的入口文件与停止条件。
```

---

## 9. 交接格式（每轮照填）

| 栏目 | 内容 |
| --- | --- |
| 本轮结论 | 完成/未完成，对应阶段与 A 编号 |
| 仓库事实 | 当前提交、技术栈、用户已有改动（含未跟踪文件） |
| 本轮变更 | 文件路径、原因、关键契约、兼容影响 |
| 测试证据 | 命令、环境、结果（PASS/FAIL/BLOCKED/NOT VERIFIED） |
| 运行与产物 | 启动方式、APK 路径、非敏感配置 |
| 数据与回退 | 迁移版本、备份方式、回退步骤、在途任务处理 |
| 遗留问题 | 复现步骤、影响、所需信息 |
| 下一步入口 | 从哪个文件、哪个阶段开始，停止条件 |

---

## 10. 文档索引

- 开发文档 01：产品与架构边界（单一入口、分层、数据模型、编排、子代理契约）
- 开发文档 02：手机版规格（页面结构、模型配置字段、事件、ASR、日志）
- 开发文档 03：阶段与验收（P0–P6、A01–A20、迁移回退）
- 开发文档 04：执行与交接提示词
- 原型：`docs/XFRIEND-STAR-ASSISTANT-PROTOTYPE-2026-10-27.html`
- 本文件：`docs/CODEX-HANDOVER-2026-10-28.md`


---

## 11. Linux 接续开发与 APK 交付（2026-10-29，Asia/Shanghai）

本节记录当前 Linux 工作区的实际结果；前文 Windows 命令和旧 APK 证据只作历史参考。

### 本轮范围与仓库事实

- 工作目录：`/home/lyon/xfriend_android/xfriend-source-2026-10-29`；起始提交 `d24e53f`。
- 接手时已有大量 CRLF 行尾变化和 5 组历史文档文件名变化，均保留；本轮未执行 reset/clean、未提交用户已有改动。
- Linux 初始无 Java、Android SDK、adb 或 AVD。已在 `.artifacts/android-toolchain/` 安装 Temurin JDK 17、Android SDK 34 / build-tools 34.0.0 / platform-tools；Gradle 缓存保存在 `.artifacts/gradle-home/`。
- 新增 `scripts/build-linux.sh`，对应 Windows `build.ps1` 的双 flavor 单元测试、打包和 lint。默认禁用 UI fixture，也支持传入指定 Gradle 任务。

### 本轮开发

- `StarScreen.kt` / `StarConversation.kt`：团队 `team:<id>` 历史键与运行时裸资源 ID 正确映射；同 ID 的专家与团队会话分开；旧消息按索引生成兼容列表键；引用目标不存在时显示「原消息已不可用」且保留元数据；ASR 密钥输入使用密码掩码与密码键盘。
- `MainActivity.kt`：切换资源或团队模式同步清空旧引用，避免跨页面串到其他会话；任务重试恢复原引用。
- `AccountScreen.kt`：将 debug 本地工作区的授权绑定放进 ViewModel factory，在构造 VM 之前同步执行，修复 Compose `RememberReturnType` lint 阻断并保留初始化 generation 顺序。
- `AgentRuntime.kt`：团队用户消息保留引用并分配稳定 ID，引用作为非授权上下文传递给每位成员；团队回复也分配稳定 ID；沿用检查点历史步骤去重，不自动重放未知副作用。
- `app/build.gradle.kts`：普通 debug 使用独立 `.debug` applicationId，版本名 `0.14.0-debug`；既有 `.qa` fixture 标识保持独立。正式 release applicationId 不变。
- 新增 `StarConversationTest`、`StarReplyQueueTest`、`StarReplyRuntimeTest` 共 13 项行为测试；原 8 处历史断言只忽略随机消息 ID，仍比较正文、角色及引用字段。

### 验证记录

- 完整命令：`bash scripts/build-linux.sh > .artifacts/build-linux-2026-10-29.log 2>&1`。
- 单元测试：**PASS**，XFriend 369 项 / 50 suites、XSalers 369 项 / 50 suites，合计 738 项，0 failures / 0 errors / 0 skipped；包括每变体 13 项新增星助理测试。报告：`app/build/reports/tests/testXfriendDebugUnitTest/index.html`、`app/build/reports/tests/testXsalersDebugUnitTest/index.html`。
- 首次完整构建：测试与两个 APK 打包通过，随后 lint 因既有本地入口 `remember` 返回 Unit 失败（1 error / 24 warnings）；已修复并重新运行完整命令。首次日志保留在 `.artifacts/build-linux-2026-10-29-first-attempt.log`。
- 最终完整构建：**PASS**，`BUILD SUCCESSFUL in 7m 36s`，112 tasks（41 executed / 71 up-to-date）；两变体测试再次各 369 项通过。
- 最终 lint：**PASS**，XFriend 0 errors / 24 warnings / 1 information，XSalers 0 errors / 26 warnings / 1 information。警告主要为依赖更新建议、未使用资源、设备标识和旧 API 使用；未通过关闭 lint 或添加 baseline 隐藏错误。报告：`app/build/reports/lint-results-xfriendDebug.html`、`app/build/reports/lint-results-xsalersDebug.html`。
- `adb devices -l`：命令成功，设备列表为空；未找到可用 AVD，亦无 `/dev/kvm`。本轮安装/启动/配置保存横幅/测试连接点击均为 **NOT VERIFIED（缺设备环境）**。
- 真实中转站聊天（A02–A05）与语音录制转写（A06）：**BLOCKED**，未提供服务配置与安全存储凭据；未使用测试替身冒充真实服务通过。
- A07–A09 与检查点恢复：新增本地行为测试 **PASS**（历史键/引用失效 5、引用队列 3、引用运行时 5），覆盖带引用团队中断后仅续跑第二成员、用户历史不重复；不等同于设备端完整交互验收。

### 安装、数据与回退

- 本轮普通 debug 包名：`com.singu.xfriend.mobile.debug` / `com.singu.xsalers.mobile.debug`。与历史无后缀包并存，数据及 AndroidKeyStore 命名空间隔离；不会自动迁移原安装的会话或密钥。
- 本地调试入口仅在 debug 生效：`adb shell am start -n com.singu.xfriend.mobile.debug/com.singu.xfriend.MainActivity --ez local_workspace true`。普通启动沿用账号入口；release 不接受本地调试绕过。
- 本轮没有数据库迁移，也未触碰真实设备或原应用数据。回退可直接移除独立 debug 应用；如已在 debug 应用产生数据，卸载前应先备份所需内容。
- 对本 debug 包后续升级须使用相同调试签名；新环境不能保证与此前 Windows 调试密钥一致，不能宣称可覆盖历史 APK。

### 下一步入口与停止条件

- 首先在设备安装本轮 APK，按前文第 5 节验证保存/测试连接可见反馈，再用应用内安全存储注入服务凭据验证真实聊天和 ASR。
- P4 入口：`FriendRun.kt`、`FriendRunQueue.kt`、`FriendRunRepository.kt`、`FriendRunService.kt`、`AgentRuntime.kt`、`AgentRuntimeCheckpoint.kt`。现有持久任务/检查点能力不代表独立子代理已经实现。
- P4 尚需独立 context、父子关系、预算/并发/深度、取消传播与任务详情 UI；涉及未知工具结果必须停在 `requires_review`，未重新核查权限不得继续，不以自动重放外部副作用通过验收。


### 最终 APK 与交付核验

验证时间：2026-10-29T03:32:41.846177+08:00。版本均为 `0.14.0-debug` / versionCode 14，Android 8.0+（minSdk 26 / targetSdk 34）。

| 应用 | 交付文件 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| XFriend | `dist/2026-10-29/XFriend-0.14.0-debug-20261029.apk` | 27,070,495 | `f884ad0bd1c6c4e2f5e3db53d37d714dde165be9cbbdf7d058c1361fd6a4e2b9` |
| XSalers | `dist/2026-10-29/XSalers-0.14.0-debug-20261029.apk` | 29,435,812 | `0a1313073913c98583c36e3a891b94c19db93e2e8ed97ea7e21e43c1fbc06569` |

- 两个交付副本均通过 `apksigner verify --verbose --print-certs`（APK v2）和 `zipalign -c 4`，交付副本与 Gradle 原始 APK 的 SHA-256 一致。
- `aapt dump badging` 已确认独立包名、版本、min/target SDK 和 `MainActivity`；普通 debug 未启用 UI fixture。
- 校验文件：`dist/2026-10-29/SHA256SUMS.txt`；机器可读验证：`dist/2026-10-29/verification.json`。
- XFriend 安装命令：`adb install -r dist/2026-10-29/XFriend-0.14.0-debug-20261029.apk`。该命令尚未在设备执行。

### 本轮交接表

| 栏目 | 内容 |
| --- | --- |
| 本轮结论 | 已完成本轮修复、APK 打包及本地验证；A07–A09 的本地行为测试通过，设备 E2E 与真实服务尚未验收；P4 独立子代理未实现。 |
| 仓库事实 | 起点 `d24e53f`，Kotlin 1.9.22 / AGP 8.5.0 / Compose 2024.06.00；原有未提交修改保留，本轮未创建提交。 |
| 本轮变更 | 团队历史和消息引用修复、ASR 密码掩码、debug 授权初始化 lint 修复、独立 debug 包名、Linux 构建脚本及 13 项新增行为测试。详见上文逐文件记录。 |
| 测试证据 | 双变体 738 项单测通过，lint 0 errors，最终构建成功；签名与对齐检查通过。日志与报告路径见本节。 |
| 运行与产物 | 上表 APK；普通启动仍走账号入口，debug 可使用前述 `local_workspace` 命令进入本地工作区；服务地址/模型/凭据需在应用内配置。 |
| 数据与回退 | 未迁移或删除旧数据，`.debug` 与旧应用共存；移除 debug 前备份其新增数据。既有未知副作用任务仍停在 `requires_review`，不自动重放。 |
| 遗留问题 | 无设备/AVD、无真实中转站和 ASR 配置；保存/连接反馈点击、长按回复交互和真机录音 NOT VERIFIED；独立子代理 context/预算/父子取消仍待 P4。 |
| 下一步入口 | 先设备安装并验证第 5 节；P4 从 `FriendRun*` / `AgentRuntime*` 开始。授权失效或外部操作结果未知时停止，核查后由用户显式继续。 |


---

## 12. P4 子代理与登录修复（0.15.0，2026-10-29，Asia/Shanghai）

本节覆盖上一节的“P4 未实现”状态；所有历史 APK、构建结果和未验证项仍按各自版本理解。

### 登录故障与修复

- 用户报错：“登录或离线授权已到期”，激活站账号仍激活。客户端原先将签发时间、离线租约、登录会话和空间订阅合并为同一错误，不能据此判断账号未激活。
- 根因证据：Android 对 `not_before` 使用零偏差，同时联网接受新租约时继续使用旧 `last_seen` 并累积未签名 `server_time`。这与电脑版 `auth-login-fix-20261019.md` 已修复的两项问题一致，Android 漏同步。
- 新 `AccountLease.kt` 先验签、核对账号/空间/设备，再对 `not_before` / `issued_at` 允许 300 秒签发偏差；离线、会话及订阅到期边界仍严格。
- 联网新租约只有验签成功后，才能以签名中的 `issued_at` 重建本机时间记录；旧错误未来记录不会永久阻挡再次登录，未签名 `server_time` 不再参与授权锚点。
- 错误提示已区分签名、绑定、手机时间、会话到期、离线授权到期和空间订阅到期。自动刷新失败保留具体错误，不再一律覆盖为“登录已过期”。
- 原账号和会话加密存储保留；未关闭 TLS、签名、账号绑定或到期校验。建议安装新版本后联网重新登录。是否能用用户真实账号登录仍需实登证据，不以本地签名测试代替。

### P4 实现与契约

- `FriendRun.kt` / `FriendRunQueue.kt` / `FriendRunRepository.kt`：旧 JSON 可读，新增 `agentParentId`（父子）与原 `parentId`（尝试链）分离；子代理独立 `contextId`、私有消息/历史幂等步骤、检查点、预算预留、截止时间及取消栅栏均写入原加密原子任务仓库。
- `SubagentRuntime.kt` / `AgentRuntime.kt`：模型可用 `spawn_subagent`、`list_subagents`、`get_subagent_result`、`cancel_subagent`。委派立即返回持久任务卡；子代理只接收显式任务输入，继承父模式和授权范围，复用所选专家在执行启动时的模型配置，并通过执行快照校验检查点；使用独立工作区和历史。
- 主会话后续 turn 可查询本会话早先子任务的有限摘要；不同专家、团队、群和账号不可跨界查询。子代理只能访问自己的后代，不能查看同级内部上下文。
- `FriendRunService.kt` / `FriendRunLiveness.kt`：信号驱动并发调度；每账号最多 3 个运行任务，同上下文单写者；团队/群根任务为避免共享专家工作区冲突保守串行，子上下文仍可并发。
- `FriendRunCompletion.kt`：协程完成回调在独立 IO 控制作用域收尾，未进入 body 就取消也会持久标记并释放名额；迟到旧回调不能修改替代任务。服务停止前同时检查运行名额与持久 queued 记录，避免父完成时丢失子任务唤醒；空闲冗余信号可正常排空停止。
- 配置快照使用共享 `configurationLock`，子任务历史使用私有锁；配置保存与子任务读取不会混用新地址/旧密钥，也不引入 Store/授权锁反向死锁。旧登录代次的 Worker 失效仅取消其任务树，不会取消新登录后无关的排队任务。
- 最大递归深度 2、每任务树最多 8 个子代理；单代理最多 8 次模型请求、64,000 token 预留，默认子代理 32,000；整树最多 32 次模型请求、128,000 token 预留。嵌套代理受父共享预算约束，重试/续聊不能重置消耗。
- 预留按请求 UTF-8 字节上界与输出预算保守计算，包含失败/恢复后的新网络尝试；不是服务商实际 token 用量或计费。已保存完整响应的恢复不会再次请求/预留。
- 根任务默认时限 5 分钟，子任务最多 3 分钟且不超过祖先截止时间。排队、重启和续聊不刷新截止；到期明确停止并保留记录。需要更长或新的工作时，应明确发起新的独立任务。
- 父任务取消持久级联整个逻辑尝试链和后代；晚到响应不能改回成功。未知文件副作用保持 `requires_review`；子任务不能通过重试/续聊绕过核查。
- 重启后，重新验证账号授权再调度尚未执行的 queued 任务；running 转为 interrupted 或 requires_review，不自动重放未知工具操作。安全检查点可在截止与预算内由用户显式继续。
- `StarScreen.kt` / `SubagentScreen.kt` / `AssistantScreen.kt` / `MainActivity.kt`：保留单入口；主会话只有任务卡，详情中读取独立 transcript；面板分“需要你 / 进行中 / 等待外部 / 已完成”；定向发送复用 context、新建 run/检查点，原输入与结果保留；取消发送目标不等于取消任务。
- `.debug` 包名与调试签名沿用，版本升为 `0.15.0-debug` / code 15；启动器名称显式标为调试，避免与原安装混淆。

### 兼容与回退

- 任务 JSON 采用新增可选字段，旧任务不丢失；独立上下文在加密任务仓库，不写入主会话历史。
- 本轮新增 `subagent_protocol` 指纹，旧版 XFriend 服务创建的 checkpoint 可能返回 `CHECKPOINT_CONFIG_CHANGED`，需明确重新发起；不能声称旧检查点无缝继续。旧数据、部分输出与文件证据保留。
- 使用同一 Linux 调试签名覆盖上一轮 `.debug` APK，可保留调试应用数据；与无后缀正式/历史应用仍隔离。未执行卸载或清除真实设备数据。
- 不建议用旧版代码覆盖已产生 P4 数据的安装；旧版本不能理解新任务图。需要回退时保留当前应用与数据备份，使用独立历史包进行比对。

### 本轮验证与产物

首轮完整命令：`bash scripts/build-linux.sh > .artifacts/build-p4-2026-10-29.log 2>&1`（两变体共 828 项测试通过，lint 0 errors，8m53s）。

调度竞态修复后的最终命令：`bash scripts/build-linux.sh > .artifacts/build-p4-final-2026-10-29.log 2>&1`。

- 最终完整构建 **PASS**：`BUILD SUCCESSFUL in 9m 38s`，112 tasks（45 executed / 67 up-to-date）。
- 双变体单元测试 **PASS**：XFriend 420 项，XSalers 420 项，合计 840 项，0 failures / 0 errors / 0 skipped。相对上一轮每变体新增 51 项，其中授权边界 6、P4 队列 19、运行时 8、截止时间 3、界面映射/预算 5、协调权限 4、生命周期竞态 6。
- lint **PASS**：XFriend 0 errors / 24 warnings / 1 information；XSalers 0 errors / 25 warnings / 1 information。未关闭检查或添加错误 baseline。
- 两个交付 APK 均通过 APK v2 签名与 ZIP 对齐检查，签名证书 SHA-256 与上一轮完全一致；交付副本与 Gradle 原始文件 SHA-256 一致。
- 原型资源仅用作设计依据，没有把虚假模型回答、示例子任务或模拟激活状态作为验收通过。真实用户账号登录、真实模型/ASR请求尚无凭据验证；激活站站点 HTTPS 可达不等于该账号已完成实登。
- 模拟器：已安装官方 API34 Google APIs x86_64 镜像并创建独立 `XFriend_P4_API34`，adb 能识别 `emulator-5556`；宿主用户无 KVM 读写权限，采用 `-accel off` 软件启动。首次系统包扫描/编译持续十余分钟，`sys.boot_completed` 一直为空。APK 29,595,166 bytes 已成功推送，但安装在系统 `PackageManagerInternal.freeStorage` 未初始化处失败；日志 `.artifacts/emulator-logs/p4-install.txt`、`XFriend_P4_API34-root-boot.log`。因此安装与点击验证 **NOT VERIFIED / BLOCKED（模拟器系统启动环境）**，没有截图或真实账号/模型 PASS。已停止本轮测试模拟器，保留 AVD 供有 KVM 或真机环境续验。

| 应用 | 产物 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| XFriend | `dist/2026-10-29-p4/XFriend-0.15.0-debug-20261029.apk` | 29,595,166 | `4922c2eb11db12a147ce379b1ba6edc70d34d7daa9e064e847738cbf746ba3a1` |
| XSalers | `dist/2026-10-29-p4/XSalers-0.15.0-debug-20261029.apk` | 29,595,275 | `32a115d8d623eeabc341cde6f83d5785288c45f8a34833b54becb65e125190a2` |

验证时间、签名证书指纹和机器可读结果见 `dist/2026-10-29-p4/verification.json`；校验文件 `dist/2026-10-29-p4/SHA256SUMS.txt`。

### 本轮交接表

| 栏目 | 内容 |
| --- | --- |
| 本轮结论 | P4 本机子代理功能及登录误判修复已实现并打包；本地行为、编译、lint、签名均通过。用户真实账号与真实模型不伪称验收通过。 |
| 仓库事实 | 起点仍为 `d24e53f`，保留原有未提交修改；版本升为 0.15.0 / code 15，本轮未创建提交。 |
| 本轮变更 | AccountLease/CloudAccount/AccountScreen 修复授权校验；FriendRun*、Subagent*、AgentRuntime*、Service/Worker/Completion、VM/路由接入持久子代理。逐项契约见本节。 |
| 测试证据 | 840 单测、双变体 lint 0 error、最终构建与签名/对齐通过；日志 `.artifacts/build-p4-final-2026-10-29.log`。 |
| 运行与产物 | 上表 APK；覆盖上轮同签名 `.debug` 应用安装后联网重新登录。设置中配置真实模型，工具能力开启后可按需委派；任务面板查看与继续。 |
| 数据与回退 | 加密任务 JSON 新增可选字段，原会话/密钥/结果保留；没有清除真实设备。旧 checkpoint 可能因协议快照变化拒绝继续；回退不得假设旧版理解 P4 数据。 |
| 遗留问题 | 真实账号实登、真实中转站/ASR与多设备外部任务尚未验证；软件模拟器未完整启动，设备 UI 未验；时间/预算达到上限后保留结果并明确重新发起。 |
| 下一步入口 | 安装 0.15 后验证该激活账号；以真实支持 tools 的模型验收委派/取消/恢复。外部操作未知必须停在 requires_review，禁止无证据自动重放。 |



## 13. 桌面供应商、订阅账号与手机执行能力（0.16 / 2026-10-29）


本节是最新状态；第 0 节及 11–12 节保留其交付时点的历史事实。用户指定桌面源码 `/home/lyon/Xfriend-desktop-source-20261029-033725/octop`，明确范围为 API 供应商和 Codex / Claude / Gemini 等订阅账号渠道；另明确要求使用 Astra ultra 完成手机权限与执行能力。手机模块由 `gpt-6-astra` / `ultra` 子代理实现，根代理接线、修复边界并统一验收。版本升为 **0.16.0-debug / code 16**。

### 结果与操作入口

- 供应商页：49 个桌面模板，四类协议的真实文字/工具请求，多个连接保存与当前模型切换；设备码授权、显式账号 JSON 导入、模型目录、令牌刷新、额度摘要。原有 API 配置与资源导入入口保留。
- Codex / Copilot / xAI 设备授权；WorkBuddy 国内/海外与 Gemini Antigravity 池显式导入；Claude/Gemini 普通订阅与桌面原生实现一致，**只查额度，不作为聊天推理渠道**。API 仍可用于 Claude/Gemini 聊天。WorkBuddy 的 Electron 加密文档不在 Android 解密。
- `FriendModelConnection` / `FriendProviderCatalog` / `FriendProviderAccounts` / `FriendProviderImports` / `FriendProviderAccountTransport` / `FriendProviderWire` / `FriendProviderResponse` / `FriendProviderTransport` / `FriendProviderSession` / `FriendProviderController` / `FriendProvidersScreen` 共同完成目录、协议、账号与界面。普通 API 和订阅账号凭据进入原有 AES/GCM 存储；配置代次/绑定摘要防止两文件部分提交串用凭据；刷新先安全落盘再请求。删除当前连接保留失效选择，禁止默默回退旧密钥。
- 官方 OpenAI Chat 输出预算映射到 `max_completion_tokens`；Responses 使用 `max_output_tokens`；Codex 按其桌面专用协议不发送不支持的输出参数。火山套餐路径严格固定；OpenCode session header、原生签名工具历史及 SSE 完整终态均接入。协议实现覆盖文字与工具，不宣称多模态全部实现。
- 输入框增加“问答 / 计划 / 执行”。手机写入与界面控制仅在执行模式；工具关闭时指引开启工具。提示词描述真实支持能力，禁止没有工具结果却声称已经完成。
- `PhonePolicy` / `PhoneStorage` / `PhoneAlarms` / `PhoneAccessibilityService` / `PhoneToolRuntime` / `PhoneCapabilitiesScreen` 与 Manifest/XML 实现本机文件、事项、一次/每天闹钟通知、定时通知、取消、系统权限页面与 SAF 导出。刷新记录会保留尚未到点的每日提醒，包含触发前最后一秒；错过的一次提醒标记 missed。
- 无障碍按需读当前界面，以账户、执行权限、本次开关和短时快照约束点击/滚动/返回/主页/打开指定应用；敏感屏隐藏、受保护目标交给用户。当前不支持自动输入文字。界面只返回有预算的合法 JSON，列表分页。
- 账户切换/退出/进程重启撤销本次控制；旧任务每次回查有效授权。稳定请求 ID 防止本机写入恢复重复；UI action 先记 pending、请求 ID 永不自动重放。未知 UI 结果硬停 requires_review；action_dispatched 需再观察确认。原始 UI 树只供内存工具循环，持久检查点用 stale_snapshot 标记替换。

完整使用说明与订阅、闹钟、定时任务的实际边界见 [MOBILE-0.16.md](MOBILE-0.16.md)。第三方声明在文档和 APK assets 内同时提供，见 [MODEL-PROVIDER-THIRD-PARTY-NOTICES.txt](MODEL-PROVIDER-THIRD-PARTY-NOTICES.txt)。

### 验证与产物

最终源码冻结后执行：`bash scripts/build-linux.sh > .artifacts/build-0.16-verified-2026-10-29.log 2>&1`。

- 完整构建 **PASS**：`BUILD SUCCESSFUL in 8m 31s`。此前修正过测试变量命名冲突，以及构建中修改代码导致的一次主/测试快照不同步；以上最终日志为冻结源码后的完整结果。
- 单元测试 **PASS**：XFriend 524 项；XSalers 524 项，共 **1048 项**，0 failures / 0 errors / 0 skipped；较 0.15 每变体新增 104 项。覆盖目录与加密配置契约、设备授权/导入/刷新/协议封装、四协议 SSE、畸形与重复工具调用、原生签名往返、额度隐私、运行时接线、问答/计划隔离、手机稳定请求 ID、未知 UI 效果停止、UI 树不落盘、临近到点的每日提醒保留。
- lint **PASS**：XFriend 0 errors / 25 warnings / 3 information；XSalers 0 errors / 26 warnings / 3 information；没有关闭检查或新增错误 baseline。
- 两个 APK 通过 APK v2 签名、ZIP 对齐和 aapt 版本检查；与 0.15 调试证书一致。交付副本与 Gradle 输出哈希相同；APK 内第三方许可与文档内容一致。
- 真实激活账号登录、真实 API/订阅请求、ASR、真机无障碍点击与后台提醒实际送达 **NOT VERIFIED**。本轮没有附加 adb 设备；此前软件模拟器首次启动未完成，不能把合成协议测试写成真实账号或设备 PASS。

| 应用 | 产物 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| XFriend | `dist/2026-10-29-providers-phone/XFriend-0.16.0-debug-20261029.apk` | 30,081,707 | `b6acaf68f4f896691088da001eb6a17a2e42c5ed13e791f599f3b6861753ff71` |
| XSalers | `dist/2026-10-29-providers-phone/XSalers-0.16.0-debug-20261029.apk` | 30,081,343 | `b1910330a5bf8c619c76d13d4cc1df2d6a7564ff1c6cbeb1635d43307ffce3d7` |

机器可读验证、签名证书、aapt 与对齐输出在 `dist/2026-10-29-providers-phone/`，校验文件为 `SHA256SUMS.txt`。签名证书 SHA-256：`01c610fa028d203f1e37248b0e812a10c5109cf20139c0e814e683d90827e7ec`。安装后打开“XFriend 星助理 · 调试”，联网重新登录激活账号；配置真实供应商并测试，选择执行模式，在手机能力页按需手动授予系统权限。

### 兼容、边界与交接表

- 0.15 P4 子代理、取消/预算/截止时间与授权时间校验修复保留；旧 API 配置不自动迁移覆盖。旧 `.debug` 包同签名覆盖升级可保留其数据，正式应用仍为独立包。
- 新增 `phone_protocol` 与当前供应商配置指纹，0.15 检查点可能返回 CONFIG_CHANGED；结果和任务证据保留，用户需明确重新发起。不能假设旧检查点无缝继续，也不应降版覆盖含新连接数据的安装。
- 闹钟当前是本应用 AlarmManager 通知，非系统时钟条目，不保证持续响铃；定时任务当前仅本机通知，不到点自动跑模型、脚本或外部动作。文件私有保存后由用户显式导出。
- Gemini 池不会在已开始的流中切账号自动重放；服务商可用模型、额度和第三方订阅协议仍以真实服务为准。OAuth 凭据不可用时需重新授权，不能伪造可用状态。

| 栏目 | 内容 |
| --- | --- |
| 本轮结论 | 供应商/订阅账号与手机执行模块已实现、测试并打包；真实账号与真机行为待安装验收。 |
| 仓库事实 | 基线 `d24e53f033496a68e0055bbd51d4bff4551e013f`；保留原有未提交变更与文件编码状态，本轮未创建提交。 |
| 本轮变更 | 上述 FriendProvider*、Phone*、Store、AgentRuntime/Checkpoint、Service、VM、账号作用域、路由与模式入口。 |
| 测试证据 | 1048 单测、双变体 lint 0 error、最终构建与签名/对齐/版本/副本哈希均通过。 |
| 运行与产物 | 上表 APK；使用步骤见 MOBILE-0.16；验证清单见 verification.json。 |
| 数据与回退 | 同包同签名升级；配置/凭据分离且绑定代次；旧 checkpoint 可能拒绝继续；没有清除设备数据。 |
| 遗留问题 | 真实账号登录、供应商实调、ASR、无障碍与厂商后台提醒限制未真机验证；常规 Claude/Gemini 订阅仅额度；自动 UI 输入与自动定时模型工作流未实现。 |
| 下一步入口 | 真机装 0.16、联网实登，配置支持工具的模型；按手机能力页授权并验收文件/事项/提醒/UI观察与操作。未知外部效果仍须人工核查，不能自动重放。 |

---

## 14. 默认执行、输入收件箱与任务管理器（0.17 / 2026-10-29）

本节是 0.17 实际完成情况；第 0–13 节保留其交付时点的历史事实。基线提交 `d24e53f033496a68e0055bbd51d4bff4551e013f`，未执行 reset/clean，未创建新提交，桌面参考目录只读，既有未提交修改全部保留。

### 实现结果

- **能力统一**：新增 `AgentCapability.kt`。`CapabilityPolicy` 同时决定工具 schema、系统提示词和派发前检查；`ConversationMode` 委托同一事实源。XFriend 默认 `craft`，`conversationModeVersion=1` 一次性把旧 `ask/plan` 迁到执行；非法值回退执行；在途 run 保留自身模式，旧 ledger 缺失模式按 `ask` 解码。
- **请求级只读**：`ConversationMode.effectiveForRequest` 识别“只讨论/先分析/不要执行/先规划/只给方案”，只约束当前请求；不会把会话永久锁回只读。
- **输入收件箱**：`SubmissionInbox.kt` + `FriendViewModel.send/submitInbox/retryInbox`。发送不再被 busy/executing 拦截；草稿立即保存到可见收件箱，失败保留原文与重试入口，不覆盖另一条草稿。
- **独立根任务与并发**：`FriendRun` 新增 `rootContextId`、`requestMessageId`；`FriendRunQueue.enqueue` 接收提交时历史快照，`saveRootContext` 持久化独立上下文，`IsolatedRootState` 执行；`claimConcurrent` 允许有独立快照的同专家根任务并发（总槽仍 3），团队/群聊与同一子代理 context 仍串行。`RootTurnMerge` 按请求 ID 合并最终回答，支持乱序完成。
- **资源仲裁**：`WorkspaceWriteLocks` 按规范路径串行化共享文件写入；`PhoneAccessibilityService` 的读取/动作通道加同步并在动作前复核快照、窗口、节点签名；HTTP 不持有 UI 锁。
- **任务管理器**：`TaskManagerIntent.kt` 只解析当前用户明确命令，重复显示/隐藏为状态，只有“切换”才 toggle；引用/转述不触发。`UnifiedTaskProjection.kt` 合并 FriendRun 与 PhoneRepository，保留 `source+id`，支持筛选/分页。聊天默认不显示任务托盘；隐藏不取消任务。`StarScreen.kt` 按用户消息显示各自状态、进行中输出，最终回答由 `replyToId` 绑定。
- **日期事项与缺参数**：缺通知时刻时先保存为 `needs_time`，缺通知/准确闹钟权限时保存为 `needs_permission`，再由用户补时刻或手动授权；日期事项本身不依赖通知权限。`phone_create_item` 增加 `due_date`，`PhonePolicy.dueDate/dueDateFromText` 按 IANA 时区校验并解析今天/明天/后天；无通知时刻不创建提醒。`phone_list_items` 增加 `kind/status/query` 分页查询。`PhoneCapabilitiesScreen` 显示目标日期。
- **自身配置保护**：新增 `AppConfigProtection.kt`。双 flavor 及 `.debug/.qa` 包名、系统设置/安装器、Intent/深链接、受保护文件名和越界工作区路径在执行层拒绝；无障碍读取/点击和 `launch_app` 在读取/派发前校验并返回 `APP_CONFIGURATION_PROTECTED`。正常事项、提醒、任务取消和管理器显示不受影响。
- **提示词**：`AgentRuntime` 注入当前时间/时区、请求上下文和同一能力清单；明确日期事项、跨会话查询、只读/计划边界、配置保护，以及“定时任务只发通知、不会自动联网查价”。

主要文件：`AgentCapability.kt`、`AppConfigProtection.kt`、`SubmissionInbox.kt`、`TaskManagerIntent.kt`、`UnifiedTaskProjection.kt`、`RootTurnMerge.kt`、`ConversationMode.kt`、`Store.kt`、`AgentRuntime.kt`、`FriendRun*.kt`、`SubagentRuntime.kt`、`SubagentPolicy.kt`、`PhonePolicy.kt`、`PhoneToolRuntime.kt`、`PhoneAccessibilityService.kt`、`PhoneStorage.kt`、`PhoneCapabilitiesScreen.kt`、`MainActivity.kt`、`StarScreen.kt`、`FriendChatScreen.kt`。

### 构建与测试

`bash scripts/build-linux.sh`（日志 `.artifacts/build-0.17-final3-2026-10-29.log`）：

- 单测：XFriend **536**、XSalers **536**，共 1072，0 failures / 0 errors / 0 skipped。0.16 每变体 524 项基线保留，新增 12 项覆盖迁移、能力一致性、配置保护、任务管理器命令、统一投影、收件箱、根任务并发/快照与乱序回答合并、日期项。
- lint：XFriend 0 errors / 25 warnings；XSalers 0 errors / 26 warnings，未关闭检查。
- 构建 `BUILD SUCCESSFUL in 10m 20s`；两个 APK 通过 v2 签名、ZIP 对齐、包名和版本检查，交付副本与 Gradle 输出一致。

| 应用 | 产物 | 字节 | SHA-256 |
| --- | --- | ---: | --- |
| XFriend | `dist/2026-10-29-execution-inbox-final3/XFriend-0.17.0-debug-20261029.apk` | 30,183,063 | `b2077d38203a50626f16d22c6fdd99f5427523fa607d09fb191a2006f1ee928d` |
| XSalers | `dist/2026-10-29-execution-inbox-final3/XSalers-0.17.0-debug-20261029.apk` | 30,182,830 | `bb4f9c8aa1500f20837a7715b03ecf4c3adcb611f9adfe6632442e71b2510a3f` |

包名：`com.singu.xfriend.mobile.debug` / `com.singu.xsalers.mobile.debug`；versionCode 17；versionName `0.17.0-debug`；签名证书 SHA-256 `01c610fa028d203f1e37248b0e812a10c5109cf20139c0e814e683d90827e7ec`。机器可读证据见 `dist/2026-10-29-execution-inbox-final3/verification.json` 和 `SHA256SUMS.txt`。

### 验收证据与未验证

| 验收项 | 结果 |
| --- | --- |
| 新安装/0.16 ASK 升级直接记录 | 通过纯函数与迁移测试；默认执行 |
| 旧检查点不被提权 | 通过；run 自带模式，缺失旧 ledger 按 ask |
| 连发 A/B/C、A 慢、B 先完成 | 通过队列/根快照/乱序合并测试（非真机模型） |
| busy/preflight 失败 | 收件箱保留原文、原因、重试；不覆盖草稿 |
| 同文件写/两个 UI 动作/独立网络 | 文件锁、无障碍同步通道、快照复核；HTTP 无 UI 锁 |
| 任务管理器默认隐藏/命令/引用不触发 | 通过命令解析和 UI 状态测试 |
| agent 不能改自身配置 | 双变体包名、系统页、Intent、路径在执行层拦截 |
| 记住明天问 Mac 价格 | due_date 落盘、返回 ID/日期/时区；不虚构提醒 |
| 通知/无障碍未授权 | 保存日期事项，返回具体缺失项与手动入口 |
| 定时自动查价 | 未实现；工具和提示词明确只发通知 |

`adb devices` 当前无设备，且未用模拟器替代 PASS。真实账号激活/供应商实调、真机无障碍点击、准确闹钟/通知送达、厂商后台限制、设备级进程重启恢复 **NOT VERIFIED**。未输出任何密钥或凭据。

### 升级注意

- 同签名 `.debug` 包可覆盖 0.16 并保留数据；不要用正式包或其它签名覆盖。
- 旧保存的 ask/plan 在升级后首次加载迁移为执行并持久化 `conversationModeVersion=1`；旧运行检查点仍按其快照模式恢复，不自动重放。
- 任务管理器显示状态是当前进程 UI 状态，重启后恢复默认隐藏；隐藏从不取消任务。
- 定时任务仍只发本机通知，不会到点运行模型或联网；需要自动查价时必须另行实现完整工作流。

---

## 15. 启动权限、画中画小窗、提示音与协议/预算修复（0.18 / 2026-10-30）

版本 **0.18.0-debug / versionCode 18**。基线仍为 `d24e53f033496a68e0055bbd51d4bff4551e013f`；未 reset/clean，未创建提交，既有未提交修改保留。

### 本轮实现

- **启动权限**：新增 `StartupPermissions.kt`，启动时检查麦克风、通知、准确闹钟、无障碍。麦克风与通知（Android 13+）走系统运行时请求；准确闹钟/无障碍只从明确按钮进入系统设置；拒绝不崩溃、不伪造成已授权，文字聊天继续可用。
- **画中画小窗**：Manifest 为 `MainActivity` 声明 `supportsPictureInPicture/resizeableActivity` 与配置变更；新增 `SmallWindow.kt`，开始录音成功后自动请求进入 9:16 小窗，也支持当前用户发送“小窗/打开小窗模式/进入画中画”。引用/网页/工具文本不触发；不支持设备直接提示。
- **提示音**：`Store.messageSounds`（默认关闭，按账户持久化）+ `AppSounds.kt`。发送成功和本次进程新完成根任务时播放短本机提示音；设置页提供手动开关；历史完成记录不会在启动时补响。
- **模型协议兼容**：`FriendModelClient.kt` / `FriendProviderResponse.kt` / `FriendProviderTransport.kt` 改进：
  - 合法 `finish_reason` 可代替可选的 `[DONE]` 终止；仅 EOF + 半截文本仍失败。
  - Content-Type 仅作提示，SSE 标成 `text/plain`、JSON 标成 `text/event-stream` 均按实际内容解析。
  - 忽略 `choices: []` 的 usage 尾包、重复 finish；兼容 `max_tokens/end_turn/tool_use` 等终止名；支持 content 数组和缺失 finish 的非流式 JSON；`length` 截断的工具参数不作为可执行调用。
- **预算误报**：普通根任务预算从 8 次/64k 提到 **24 次/512k**；子代理保持 8 次/64k；全局树上限 64 次/1,024k；新增根预算对子代理请求的强制检查。失败任务重新发起使用根预算，旧检查点保持原预算快照，不静默重放。
- **回归**：新增 6 项单测（协议兼容 3 项、根/子代理预算与重试预算 2 项、小窗命令 1 项），并修正原有测试对省略 `[DONE]` 的旧假设。

### 验证

`bash scripts/build-linux.sh`，日志 `.artifacts/build-0.18-final3-2026-10-30.log`，`BUILD SUCCESSFUL in 10m 47s`。

- XFriend 542 项、XSalers 542 项，共 1084，0 failures / 0 errors / 0 skipped。
- lint：XFriend 0 errors / 25 warnings；XSalers 0 errors / 26 warnings。
- 两个 APK 通过 APK v2 签名、ZIP 对齐、包名/versionCode/versionName 校验；副本与 Gradle 输出一致。

| 应用 | 产物 | 字节 | SHA-256 |
| --- | --- | ---: | --- |
| XFriend | `dist/2026-10-30-launch-pip-sound-final3/XFriend-0.18.0-debug-20261030.apk` | 30,228,984 | `fcd628b5de6079e1b594d69f7a38839323d2d4d5bcd90e0930519a6e94a5e44f` |
| XSalers | `dist/2026-10-30-launch-pip-sound-final3/XSalers-0.18.0-debug-20261030.apk` | 27,469,471 | `4132a607c70baaaaf328b5596e6348d5d7653b362e71c4965dcaa88d98d0d3bc` |

签名证书 SHA-256：`01c610fa028d203f1e37248b0e812a10c5109cf20139c0e814e683d90827e7ec`。机器可读证据：`dist/2026-10-30-launch-pip-sound-final3/verification.json`、`SHA256SUMS.txt`。

### 未验证

`adb devices` 无设备。启动权限弹窗、OEM 小窗行为、ToneGenerator 实际音量/DND、用户真实供应商上的 GDP 查询、真实账号与通知/无障碍仍未真机验证；不能把 JVM 协议/预算测试写成真实模型调用 PASS。

### 升级注意

- 同签名 `.debug` 包可覆盖 0.17；0.17 的收件箱、任务、事项、账户数据保留。
- 旧任务检查点保留原会话模式和预算快照；新根任务使用新预算。预算/协议失败仍以真实状态显示，不自动重放。
- 小窗是 Android 画中画，不是任意悬浮窗；不支持画中画的设备/系统会提示。
- 提示音默认关闭，需用户手动开启；不会绕过系统静音/DND。

---

## 16. 通话、非流式回复与持久记忆（0.19 / 2026-10-30）

版本 **0.19.0-debug / versionCode 19**。基线仍为 `d24e53f033496a68e0055bbd51d4bff4551e013f`；未 reset/clean，未创建提交，既有未提交修改保留。

### 实现

- **通话**：`StarScreen.kt` 右上角新增电话按钮；`StarCallOverlay.kt` 全屏显示“正在和星助手对话”，使用 `StarVoiceRecorder` → `starTranscribe` → `FriendViewModel.sendFromCall` → 完整任务结果 → `StarSpeech` TTS 朗读，再回到聆听。没有 ASR/麦克风权限时显示真实缺失项；通话不取消其它任务。
- **非流式 UI**：`AgentRuntime` 增加 `requestStream/emitDeltas`；服务层普通任务使用 `requestStream=false, emitDeltas=false`。供应商协议仍可在内部消费 SSE，但聊天不显示半截 delta，只合并完整回答。`StarScreen.kt` 在任意运行任务超过 10 秒时于顶部显示“正在处理消息…”。
- **持久记忆**：新增 `MemoryFact` / `MemoryPolicy` / `MemoryWorker`。账户级 Store 保存最多 24 条偏好、个人标签、长期约定、重要事项；保存前过滤密码、密钥、验证码、支付卡、身份证等。每个完成的普通根任务后，按触发词或每 4 个根任务（带 2 分钟去抖）在后台调用当前账户模型，要求返回完整 JSON 记忆列表；解析失败或空结果不清空旧记忆。记忆通过 `MemoryPolicy.prompt` 注入系统提示词，明确是用户资料而非系统指令/授权。
- **记忆 UI**：星助理设置 → 长期记忆；可查看分类、删除单条、清空全部、立即整理；显示后台整理状态。
- **兼容边界**：记忆只在本机当前账户，不跨账户或设备同步；不保存附件、屏幕原文、工具输出；不绕过自身配置保护。
- **测试**：`MemoryPolicyTest` 覆盖敏感过滤、去重/上限、JSON、提示词边界、触发/去抖、编码；通话/非流式逻辑通过现有运行时和构建回归，真机行为未验证。

### 验证与产物

`bash scripts/build-linux.sh`，日志 `.artifacts/build-0.19-final-2026-10-30.log`，`BUILD SUCCESSFUL in 12m 54s`。

- XFriend 546 项、XSalers 546 项，共 1092，0 failures / 0 errors / 0 skipped。
- lint：XFriend 0 errors / 25 warnings；XSalers 0 errors / 26 warnings。
- APK 通过 v2 签名、ZIP 对齐、包名/versionCode/versionName 和副本哈希检查。

| 应用 | 产物 | 字节 | SHA-256 |
| --- | --- | ---: | --- |
| XFriend | `dist/2026-10-30-call-memory-final/XFriend-0.19.0-debug-20261030.apk` | 27,516,695 | `27b9fdc0b071fb5b30152308823bfd68a60988bd638814948d8ddb441dcbec5d` |
| XSalers | `dist/2026-10-30-call-memory-final/XSalers-0.19.0-debug-20261030.apk` | 27,515,911 | `b0a80cecdde420c29ee91cedebc454c66249d9d3074c0df5ea79956a0dc80771` |

签名证书 SHA-256：`01c610fa028d203f1e37248b0e812a10c5109cf20139c0e814e683d90827e7ec`。机器可读证据：`dist/2026-10-30-call-memory-final/verification.json`、`SHA256SUMS.txt`。

### 记忆的事实与边界

0.18 及以前没有完整长期语义记忆：只有最近 12 条会话消息（长度受限）、运行检查点、词法知识检索、工作区文件和手机事项。0.19 的记忆是本机账户级事实列表，不是向量数据库，不保证跨设备/卸载保留，不把附件、屏幕内容或工具输出自动变成用户偏好。后台整理消耗当前模型请求；失败不会影响聊天任务。

### 未验证

`adb devices` 无设备。真机通话/麦克风/ASR/TTS、真实供应商后台记忆整理、权限弹窗、通知/无障碍和小窗仍需安装验收；不能把 JVM 测试写成真实调用 PASS。
