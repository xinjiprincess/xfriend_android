# 通话录音接管（0.12.0）

> 适用范围：XSalers（`com.singu.xsalers.mobile`）  
> 版本：0.12.0 / versionCode 12  
> 一句话：把系统或录音机已经保存的通话录音接管到 App 私有目录，复用既有「语音转写 → 复盘」链路；文字保存后可删除源音频，只留文字与复盘报告。

## 1. 背景

Android 10（API 29）起，第三方应用不能在通话过程中直接录音。用户真正能拿到的是系统通话录音机、厂商录音机或第三方录音 App 已经落盘的通话录音文件。

0.12 的「通话录音接管」不是通话中录音，也不新增后台录音服务，而是：

- 在用户授权后，扫描系统音频库（MediaStore）与 App 自己的 `recordings` 目录；
- 识别疑似通话录音（文件名/目录关键词、电话号码、日期时间、音频扩展名）；
- 用户勾选后复制/解码到 App 私有目录，生成一条 `kind=audio` 的沟通记录；
- 沿用既有 `decodeAudio → analyzeNow` 管道做转写与复盘；
- 文字保存成功后，在页面「待清理源录音」中提示删除源音频；删除后文字与复盘报告保留。

## 2. 能力边界

| 项目 | 0.12 的实际能力 |
|---|---|
| 通话中录音 | **不支持**，也不申请通话录音能力；只接管已经保存到磁盘的录音文件 |
| 扫描触发 | App 打开后自动扫描一次（受开关控制）+ 页面「重新扫描」手动触发；**无后台常驻服务/定时任务** |
| 扫描范围 | 系统音频库（需要音频读取权限）+ App 私有外部目录 `files/recordings`（无需系统权限） |
| 候选识别 | 文件名/目录含电话、通话、录音、呼入/呼出、call/record 等关键词，或含手机号/座机号，或含可解析日期时间；扩展名限 wav/mp3/m4a/aac/amr/ogg/opus/3gp/flac；单条 ≤ 4 小时、≤ 4 GB；最多 200 条 |
| 接管动作 | 复制应用内录音，或使用 `MediaExtractor/MediaCodec` 将系统音频解码为 PCM16 WAV 到 App 私有目录；接管、复制与解码在本机完成，不写入本应用自有服务器 |
| 转写 | 复用既有模型链路；**必须先配置模型/试用额度**，否则只完成导入并提示「配置模型后可打开记录开始转写」 |
| 删除源录音 | 文字保存后出现「待清理源录音」；二次确认后删除：API 30+ 系统媒体库走 `MediaStore.createDeleteRequest` 系统确认，App 内录音直接删源文件；文字与复盘报告保留，`sourceDeleted=true` 后不再提示 |
| 客户匹配 | 从录音文件名解析手机号/座机号，与客户电话归一化后匹配；匹配不到时显示「未匹配客户」，仍可接管 |
| 去重 | 已接管记录（`sourceUri` / `sourceName`）、已忽略记录、本次扫描已见 key 均会排除；再次扫描不会重复出现 |

## 3. 用户流程

1. 打开 XSalers → 今日页 →「接管通话录音」。
2. 未授权 → 页面提示「需要允许读取音频」→「授权」→ 系统权限弹窗；拒绝则显示受限提示，可稍后重试。
3. 已授权 → 扫描系统音频库 + App `recordings` → 有候选则进入列表；无候选显示空状态与「重新扫描」。
4. 勾选候选 →「接管并转写」→ 复制/解码到私有目录 → 生成 `kind=audio` 记录。
5. 已配置模型 → 后台转写并生成文字/复盘；未配置 → 只导入并提示后续转写。
6. 文字保存后 →「待清理源录音」→ 二次确认删除 → 源音频删除，文字与复盘保留。

对应页面入口：

- 今日页快捷入口：**「接管通话录音」**（`SalesScreen.kt:238`）；
- 我的页设置行：**「通话录音接管」**，副标题「接管系统通话录音 · 转写后清理源文件」（`SalesScreen.kt:395`）；
- 页面标题：**「通话录音接管」**（`SalesCallRecordingsScreen.kt:88`）。

## 4. 页面结构（XSalers）

| 区块 | 内容 | 关键行为 |
|---|---|---|
| 顶部栏 | 返回 + 「通话录音接管」 | 返回 XSalers 主壳 |
| 说明卡 | 麦克风图标 + 「接管已保存的通话录音」+ 两段边界说明 | 说明不是通话中录音、删除后文字保留 |
| 权限 banner | 「需要允许读取音频，才能发现系统通话录音」+「授权」 | 点击发起 `READ_MEDIA_AUDIO`（API 33+）或 `READ_EXTERNAL_STORAGE`（≤32） |
| 可接管录音 | 标题 + 「重新扫描」；空状态或候选列表；全选、单条选择 | 显示标题/日期/时长/大小/来源；右侧匹配客户 chip |
| 接管动作 | 「接管并转写 (n)」+「忽略所选」 | 接管后候选移除；忽略写入 `callSkipped`（最多保留 500 条） |
| 设置 | 「打开 App 时自动扫描」「转写完成后提示删除源录音」两个开关 + 隐私说明 banner | 持久化到 `SalesData`，默认均开启 |
| 待清理源录音 | 标题 + 「文字已保存，源录音可删除」；每行「删除源录音」 | 删除前弹窗「删除源录音？」→「确认删除」/「保留源录音」 |

空状态文案：

- 未授权：「允许读取音频后，这里会列出系统里已保存的通话录音。」
- 已授权：「通话录音通常保存在「录音」相关文件夹；可稍后重新扫描。」

## 5. 权限与隐私

| 项目 | 说明 |
|---|---|
| 清单权限 | `READ_MEDIA_AUDIO`（Android 13+）；`READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`，Android 12 及以下） |
| 运行时申请 | 仅在用户点击「授权」时申请；不预先弹窗 |
| 无权限时 | 只扫描 App 自己的 `recordings` 目录（无需系统权限）；页面显示受限 banner |
| 删除权限 | 系统媒体库删除由 `MediaStore.createDeleteRequest` 让用户在系统弹窗确认；App 不静默删除系统文件 |
| 本机处理 | 接管、复制、解码都在设备上完成；解码产物是 App 私有目录的 WAV，不写入本应用自有服务器 |
| 转写链路（如实说明） | 转写沿用现有模型链路，会把音频发送给用户配置的 ASR 服务；默认 `ModelProfile.asr = mimo-v2.5-asr`、默认端点 `https://api.xiaomimimo.com/v1`。音频是否离开本机取决于模型配置；不写入本应用自有服务器 |
| 删除后 | 删除源音频与应用内副本；文字、原文、复盘报告保留；记录标记 `sourceDeleted=true` |

## 6. 数据与兼容

`Communication` 新增三个字段（`SalesDomain.kt:12`）：

| 字段 | 类型 | 默认 | 作用 |
|---|---|---|---|
| `sourceUri` | String | `""` | 系统媒体库 URI；App 内录音为空 |
| `sourceName` | String | `""` | 原始文件名，用于待清理提示与去重 |
| `sourceDeleted` | Boolean | `false` | 源音频已删除，不再提示清理 |

`SalesData` 新增三个设置字段（`SalesStore.kt:18`）：

| 字段 | 默认 | 作用 |
|---|---|---|
| `callAutoScan` | `true` | 打开 App 时自动扫描 |
| `callDeleteAfterTranscribe` | `true` | 转写完成后提示删除源录音 |
| `callSkipped` | `[]` | 已忽略候选 key，最多保留 500 条 |

向后兼容：`Communication.read()` 与 `SalesData.read()` 使用 `optString/optBoolean` 默认值，旧 JSON 可正常读取，不需要迁移脚本。

## 7. 代码文件

| 文件 | 类型 | 作用 |
|---|---|---|
| `app/src/main/java/com/singu/xfriend/SalesCallRecordings.kt` | 新增 | `CallRecording` / `CallRecordingSettings` / `CallRecordings`；扫描、权限、识别、标题/日期/时长/大小格式化、客户匹配、删除本地源 |
| `app/src/main/java/com/singu/xfriend/SalesCallRecordingsScreen.kt` | 新增 | 通话录音接管页：权限 banner、候选多选、接管并转写、忽略、设置开关、待清理源录音、删除确认 |
| `app/src/main/java/com/singu/xfriend/SalesViewModel.kt` | 修改 | 自动/手动扫描、接管、忽略、设置持久化、待清理列表、删除源录音（API<30 分支）、`confirmSourceDeleted` |
| `app/src/main/java/com/singu/xfriend/SalesDomain.kt` | 修改 | `Communication` 增加 `sourceUri/sourceName/sourceDeleted` |
| `app/src/main/java/com/singu/xfriend/SalesStore.kt` | 修改 | `SalesData` 增加 `callAutoScan/callDeleteAfterTranscribe/callSkipped` |
| `app/src/main/java/com/singu/xfriend/SalesScreen.kt` | 修改 | 今日页入口、我的页设置行、页面路由 |
| `app/src/main/java/com/singu/xfriend/SalesMedia.kt` | 复用 | `decodeAudio`：MediaExtractor/MediaCodec 解码为 PCM16 WAV，限制 4 小时 |
| `app/src/xsalers/AndroidManifest.xml` | 修改 | 新增音频读取权限 |
| `app/src/test/java/com/singu/xfriend/SalesCallRecordingsTest.kt` | 新增 | 12 个单元测试：文件名解析、日期/号码/标题、时长/大小、JSON 往返、旧数据默认值、WAV 时长 |

实现要点：

- 扫描在 `Dispatchers.IO` 执行，主线程只更新状态；
- 系统媒体库查询按 `DATE_ADDED DESC`，最多检查 1000 行、返回 200 条；
- App 内 `recordings` 目录取最新 50 个文件，WAV 从文件头算时长；
- 接管时逐条 `copyTo` 或 `decodeAudio`；任一条失败会清理半成品并继续其余条目；
- 可转写判断沿用 `useTrial` / 模型配置检查；无模型时只导入并给出提示。

## 8. 验证证据

| 项目 | 结果 |
|---|---|
| 单元测试（两 flavor） | Xfriend 300 + Xsalers 300，合计 **600 项，0 failures / 0 errors**（各 38 suites，2026-10-18；`app/build/test-results/testXfriendDebugUnitTest/`、`testXsalersDebugUnitTest/`） |
| 构建 | `build-0.12-release.log`：BUILD SUCCESSFUL in 13m58s；93 actionable tasks（45 executed / 48 up-to-date）；0 compile errors / 16 warnings；正式 debug APK 已产出 |
| QA 环境 | Pixel_9 模拟器，Android 37，1080×2424 / 420dpi；fixture 包 `com.singu.xsalers.mobile.qa` |
| 模拟器 E2E | 未授权提示 +「授权」按钮；`pm grant READ_MEDIA_AUDIO` 后提示消失、候选正常显示；全选→接管并转写(1)→今日「待分析」1→2、新增「通话录音」记录；去重后仅剩 1 条候选；删除源录音→确认→卡片消失、外部文件 `通话录音_20261016_1432.wav` 已删除、文字/报告保留；crash buffer 为空 |
| 截图 | 7 张已回填（callrec-01-home ~ callrec-07-deleted）；`callrec-06-pending-delete.png` 为 `callrec-04-granted.png` 底部「待清理源录音」卡片裁剪（1080×540），其余 1080×2424 |
| 转写后删除 | 删除链路用「已转写」种子记录验证通过；fixture 无法配置模型，真实 ASR 转写→删除未端到端 |
| 单测覆盖 | MP3/高采样率/无权限/外部存储异常路径由 `SalesCallRecordingsTest`（12 项）覆盖；lint 未执行（非阻断） |

## 9. 已知限制
1. **机型/厂商差异**：录音保存位置、命名规则、媒体库是否索引各不相同；当前用关键词/号码/日期/目录名启发式识别，可能漏识别，需要把录音放到常见「录音」目录再扫描。
2. **需要模型**：fixture 无法配置模型；不配置模型/试用额度时只能导入，不能转写，也就不会进入「待清理源录音」。
3. **转写链路依赖现有模型**：ASR 由用户配置的模型服务执行，默认 MiMo 云端端点；离线/无网络时转写不可用。
4. **删除不可恢复**：系统媒体库删除需用户在系统弹窗确认；App 内录音删除不可恢复；删除链路用「已转写」种子记录验证，文字与复盘保留。
5. **无后台能力**：只做「打开 App 扫描一次 + 手动重新扫描」，不做定时扫描、通话监听或后台常驻。
6. **API 26–29 删除分支**：旧系统直接 `contentResolver.delete`，只受 App 内二次确认保护，无法调用系统删除确认 UI。
7. **验收边界**：模拟器合成数据验收，不代表真机；真实 ASR 转写未端到端；未测权限拒绝后重授权、厂商录音机目录、超大文件；`06-pending-delete.png` 是裁剪图。lint 未执行（非阻断）。
8. **文案与隐私**：页面文案已改为「接管、复制与解码都在本机完成；点开始转写后，音频会按当前模型配置发送给对应的 ASR 服务（默认 MiMo 云端），不会写入本应用的服务器。」此前错误表述已删除，不在文档中重复。
9. **后续可做**：厂商录音目录预设、同一通话多段聚合、容量统计/批量清理、转写后自动清理（需用户开启+系统确认）、真机与多机型 QA。
