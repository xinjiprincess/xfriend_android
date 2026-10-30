# Xfriend Android 统一协作改造交接

日期：2026-10-19；2026-10-20 补充群聊多选、内置主持、加载状态与实际后端合同。接收方：Trae。工作目录：`D:\xfriend-suite`。手机侧仍为待实施任务；第 7 节区分本轮后端源码已实现的合同与提案，不能据此宣称 Android 已接入或安装包已发布。本文件未向 Trae 自动发送。

## 可直接交给 Trae 的任务说明

请先阅读 `D:\octop\docs\design\xfriend-unified-runtime\architecture-proposal-2026-10-19.md`，再审计本机当前源码、已有修改和实际构建结果。完成 Android 对统一协作运行时的接入，保留用户会话、配对、账号、权限、任务与审批，不在手机端重新实现一套专家调度器。先给出你确认的实施范围和真实基线，再按已冻结接口实施；后端尚未实现的接口明确标记，不用假数据或静态页面当交付。

## 1. 现场与版本

- 当前读取到 `app/build.gradle.kts` 为 versionName 0.13.0、versionCode 13。README 仍含 0.12 交付快照，不能据此降级源码或覆盖已有改动。
- 0.13 已有已有电脑 thread_id 续聊、任务/审批、文件/相机/语音、成果分享、云房间和会议控制等增量。先追踪实际入口，避免重复造同名功能。
- Xfriend 与 Xsalers 共用工程。共享文件变更需要检查两个 flavor，保留销售侧通话录音接管、CRM 和用户数据。
- 不结束其他工具运行中的 Gradle/模拟器，不重置、清理或覆盖他人工作。不要提交、推送、上传 APK 或部署服务，除非收到明确指令。

## 2. 统一接口边界

统一目标由后端主导 room/presence/run/job/event/waitset/speech-grant。手机是有权限的命令提交与观察端，不直接执行桌面 DSH 子代理，不复制订阅凭据。现有可接入合同见第 7 节；下面的完整统一调度目标仍按提案处理。

下一版接口至少包含：

- 读取房间成员、专家在线/可用状态与订阅偏好。
- 创建一次带 request_id 的用户消息/明确指派，保留 thread_id/session 绑定。
- 按 event cursor 拉取或订阅有序事件；序号去重，离线重连只恢复观察。
- 读取统一任务树和 engine-owned 内部观察节点，显示执行所有者。
- 用户插话、暂停本轮、下一议题、结束整项任务，携带 room_epoch 和目标 run/job。
- 读取/回答真实审批，绑定当前权限、参数/文件基线和审批记录。
- 读取成果草稿、预览、另存/批准合并/丢弃和冲突状态。

这些是拟定合同，具体路由、字段、错误码与事件枚举由后端冻结后生成/同步。不得把这里的字段示例当成已经上线的 API。老服务仍用当前可用接口，依据明确 capability 响应禁用未支持的新功能。

## 3. UI 工作包

### A. 长期驻群

在现有房间内展示专家成员、模型席位、可自动参与状态和等待原因。成员长期存在但空闲不持续推理。房间设置可关闭某位专家的自动参与；不能把普通 AI 回帖自动变成手机侧新任务。

添加 AI 参与者使用可搜索的头像与名称名单，点击头像或整行多选，显示勾选状态、已选人数和“添加 N 位”。复用真实头像，已加入对象显示已加入；专家、团队代表、直接模型入口保留，团队不自动展开全部成员。“邀请真人”保留独立的邀请接受流程。

本轮后端已实现批量事务添加，按第 7 节使用，保留房主权限、专家可访问性和现有 20 位 AI 参与者上限。连接旧服务且只有单项接口时展示逐项结果，失败项留在选择区，不把部分成功显示成全部完成。确认添加不触发推理或自动欢迎长文。

### B. 父子任务与唤醒

复用任务页，展示 running、waiting_children、waiting_user、waiting_approval、paused、cancelling 与终态。孩子结果合并后父专家恢复一次，标明“根据子任务结果继续”。DSH 内部节点只观察，不能点击后再次提交成新任务。

### C. 会议与用户优先

支持专家、纯模型和混合席位。主讨论默认一个发言流，并行讨论最多同时展示两张带说话者的卡片；其他结果折叠。抢麦显示为发言请求/队列，不能把所有流拼入同一气泡。

新会议默认选中内置会议主持人，展示其真实名称、头像和职责；选择首位专家或单个团队不自动替换主持。默认主持是会议内置角色，采用 meeting-host 模板规范和已配置模型，不要求先创建永久专家。用户可以明确改选已有主持专家/团队；已有会议保持原绑定。没有有效主持模型时显示配置入口并阻止开始，不伪装成已就绪。

会议与群聊复用一致的消息列表和输入体验。会议开始前即可写开场内容、添加多个文件、查看上传/失败状态；开始后首条用户消息和材料引用保留在同一时间线。进行中也能补充文字与文件，默认提供给后续发言者，显式打断仍使用独立控制命令。文件跟随会议/房间授权，纯模型会议不以虚拟 agent_id 调用专家私有上传接口。上传/开始失败保留草稿，不因手机重连重复上传或重复开会。

输入与“打断、下一步、结束”始终可访问。点击控制后立即更新 UI；旧 room_epoch 的 token 不再抢占当前画面。取消未确认显示停止中，不显示虚假的已结束。普通观察者不能取得超出房间角色的控制权。

### D. 组件与小星动画

组件未加载时依据真实 phase 显示小星状态：骑车加载、拿工具准备技能、找房间唤醒专家、送信同步、举手等发言、停车等审批、检修显示错误。显示文字、实际进度、取消和重试；短任务不闪动画，离屏停止，减少动态效果偏好生效。

素材与桌面共用角色规范，但用现有 Compose/图集能力实现，不嵌入桌面安装器，不为动画引入新的手机端 agent runtime。

区分账号已登录、电脑可连接、共享运行时、基础专家、目标房间和所需工具的实际准备状态。手机显示名单或轮询状态不启动所有专家；真正选择目标后由后端复用准备任务。桌面登录预热设计见 `D:\octop\docs\design\xfriend-unified-runtime\startup-loading-design-2026-10-20.md`。手机不得把已登录直接显示为电脑专家服务全部 ready，也不因重连重复创建房间/主持人。

### E. 办公与成果审阅

调用后端转换任务，展示实际可用格式和转换阶段。先查看草稿与差异，再明确另存/合并/丢弃；不能把“打开预览”当成批准。原件被改动时显示冲突，不能复用旧审批。

Univer WebView 若接入，必须使用后端授权的短期访问与每请求空间校验；不能把桌面 DSH 的 cookie/token 或任意本机 localhost 地址直接交给手机。

## 4. 状态与 Android 生命周期

- 本机只持有必要的 UI 状态、事件游标和幂等请求记录；后端是执行状态来源。
- 旋转屏幕、导航和进程恢复不得重新提交同一任务。旧审批或控制命令与当前 epoch 不符时重新读取状态。
- 沿用已有合法通知/前台观察机制，适配 Android 后台限制；不要承诺手机进程能无条件永久存活。
- 电脑离线时显示设备不可达和已知最后状态，不重复发动同一工作。云托管与电脑执行的可用条件分开展示。
- 退出账号/切空间后立即停止观察旧作用域，不显示旧成员或旧文件；保留原有密钥本地存储边界。

## 5. 验收证据

先针对修改范围执行必要的单测、类型/编译和 lint，再构建相关 flavor。共享代码要同时验证 Xfriend/Xsalers，不把旧版本 600 项或 0.13 的 25 项历史结果当作本次通过数。

真机覆盖：头像名称多选与部分失败；重复添加及满 20 位；内置主持默认与手动改选；纯模型会议与无有效模型；已有 thread 续聊；子结果到达/父汇总；重复事件/重连；两人流与打断；下一议题屏蔽旧流；权限撤回；审批与文件冲突；后台/前台通知；动画减弱模式；电脑离线、准备较慢与账号切换。

交付真实 APK 路径、versionName/versionCode、签名类型、SHA256、实际执行命令及通过/失败/UNVERIFIED。Debug 包不得称正式发布签名；fixture 不得混入交付包。没有真实后端/设备联调的项目单列未验证，不用模拟数据通过代替。

## 6. 与后端协作的停止点

对于第 7 节仍属提案的统一调度协议，可以先完成可复用组件、状态映射与现有接口兼容层；不得猜测发起业务操作的路由或伪造父恢复逻辑。已实现部分按实际合同接入，保持独立小范围差异，便于与当前桌面改造三方合并。

## 7. 2026-10-20 后端实际合同与 Trae 接入步骤

以下以本轮冻结的 `D:\octop` 源码为准，表示后端路由/领域行为已实现，不表示已部署到用户设备。Android 本轮没有修改、构建或真机联调。请求继续通过现有受信任设备连接和当前账号认证发送；所有 id 都来自该后端返回值，不把跨部署的同名专家、资料或房间视为同一对象。

### 7.1 群聊头像名称多选

| 已实现路由 | 请求与返回 |
|---|---|
| `GET /api/rooms/{room_id}/participants` | 当前可见参与者数组。|
| `POST /api/rooms/{room_id}/participants/batch` | `{ "participants": [...] }`，1–20 项；返回 `ParticipantView[]`，添加动作不触发推理。|
| `POST /api/rooms/{room_id}/participants` | 原单项接口保留。|
| `POST /api/rooms/{room_id}/messages` | 发送真实用户任务，字段与幂等规则见下文。|

每项为 `{kind, agent_id?, model?, name?}`。`kind` 只能是 `expert | team | model`；专家/团队必须给实际 `agent_id`，纯模型不得给 `agent_id`。模型标识使用已有配置的 `provider/model`；省略时由后端按现有绑定/default model 解析，客户端不发凭据。

返回字段：`participant_id, room_id, added_by, kind, agent_id, model, name, enabled, created_at`。`enabled` 当前为整数。该返回没有头像字段；复用现有已授权专家/团队资源中的真实头像元数据，不能假设 `/mobile/v2/resources` 已增加头像字段（其资源仍为 `id, name, kind, revision`）。头像无法取得时使用现有回退图标。

仅房主可添加；先验证整批对象、访问权与有效模型，再事务写入，活动 AI 席位总数不超过 20。相同 `agent_id` 已存在且 kind/model 匹配时返回原席位；请求内重复专家或不匹配配置会拒绝整批。纯模型席位没有此专家去重承诺，网络超时后先刷新名单核对，不能盲目重复提交。团队仍为一个代表席位。真人邀请继续用既有单独流程。

群聊消息体实际使用 `idempotency_key`（1–100 字符），不是会议的 `request_id`：

```json
{"text":"请分析这份资料","idempotency_key":"stable-room-message-id","mentions":["participant_id"],"mode":"ask","dispatch":"auto","allow_handoff":true,"material_ids":["material_id"]}
```

`text` 最长 32000，文字和资料不能同时为空；`mentions` 是席位 `participant_id`，最多 20 个；`mode=ask|plan|craft`，`dispatch=auto|parallel|sequential`。返回为真实 run，含 `run_id, room_id, state, mode, revision, text, tasks, events`，以及资料扩展字段 `materials`、`material_messages`（消息 id → 资料 id 数组）。重试保持同一 key 与同一载荷，改变载荷必须换 key。

### 7.2 与专家无关的会话资料上传和授权下载

- `POST /api/conversation-materials`：multipart 字段名为 `file`，每请求一个文件，201 返回 `{id, filename, media_type, size, status, truncated}`；`status=ready|empty|unsupported|unreadable`。上传不需要专家已运行，不创建模型会话；原文件即使无法提取文本也保留可下载。
- `GET /api/conversation-materials/{material_id}`：二进制 `application/octet-stream`，带附件文件名、`nosniff` 和 `private, no-store`。暂存资料只有上传者能读；显式发到会议/群聊后，至少要保留一个已绑定会话的当前访问权才能读；失去全部相关访问权后，原上传者也不能靠旧 id 继续下载。不是任意本机路径下载接口。
- 分享通过群聊发送、会议创建或会议补充消息的 `material_ids` 完成；没有单独公开的 `/bind` 路由。每次最多 20 个 id，会议累计最多 20 份资料。单文件上限取后端 `max_upload_bytes`；超限返回 413，不在手机写死另一上限。
- Android 保存文字、已上传资料 id 和未完成上传状态；失败项保留原文件引用并可重试/移除。上传进度来自真实传输回调，`truncated/unsupported/unreadable` 必须展示，不能显示为模型已完整阅读。
- 新资料接口与旧 native mobile run 的 `inbound/` 附件合同不同，不能把 material id 填入 `Attachment.path`，也不能为了纯模型会议伪造 agent id。

### 7.3 默认主持、开场资料与会议幂等

`GET /api/meetings/default-host` 返回 `{host, model_available}`。默认 host 的实际字段含 `id="builtin-meeting-host"`、`kind="model"`、`role="moderator"`、`template_id="meeting-host"`、`template_version`、`name`、`model`、`icon_name` 和 `icon_url`。它是内置会议角色，不是新建的永久专家记录。

`POST /api/meetings` 实际创建体：

```json
{"title":"项目评审","topic":"先评审预算和排期","request_id":"stable-meeting-create-id","material_ids":["material_id"],"expert_ids":[],"team_ids":[],"model_participants":[{"model":"provider/model","name":"评审席","role":"检查风险"}],"discussion_mode":"roundtable","max_rounds":3}
```

`title` 1–100 字符，`topic` 最长 16000；只有文件时 topic 可为空，后端生成资料讨论主题，但不能文字和资料都为空。`request_id` 可选、1–100 字符；手机应为一次创建保存稳定值，相同账号、同 key、同体重试返回原会议，改变配置却复用 key 会拒绝。`host_id` 省略/为空才使用内置主持；可显式传已有主持专家/团队，`host_model` 可指定已配置主持模型。`member_models` 为专家 id 到模型的显式覆盖。

`expert_ids` 与 `model_participants` 合计 1–20 位，纯模型讨论可以不选专家。`team_ids` 仍是现有团队模型候选来源，不表示自动展开成员。当前会议专家席位只接受 native 绑定；不能因 DSH capability 声明就把 DSH 专家放入会议。`discussion_mode=roundtable|free`，`max_rounds=1..20`；无有效模型时后端拒绝，不自动创建供应商或探测模型。

创建只生成 `draft`，显式 `POST /api/meetings/{id}/start`（体为 `{ "skip": false }`）才讨论。开始失败保留已创建的 draft id，重试开始，不重新创建。`skip=true` 只用于已有暂停成员恢复规则，不能当通用“下一议题”。

`POST /api/meetings/{id}/input` 体为 `{text, material_ids, request_id}`；`text` 最长 4000，文件和文字可二选一，request id 1–100 字符。同会议、同 request id、同文字和资料 id 列表重放不新增消息，修改载荷须换 key。仅 `draft/running/paused` 接受新补充；资料提供给后续发言者，补充消息不会自动取消当前发言。

`GET /api/meetings/{id}` 返回现有 `MeetingView`（`id,title,topic,state,host,participants,max_rounds,round,stage,revision,messages`）及 `materials, material_ids, user_messages` 等扩展字段。`user_messages` 保存开场与补充的 `{text, materials, created_at, request_id?}`，`messages` 是发言输出；手机合并呈现时保留这两个来源，不因文件上传成功就插入一条虚假的模型回复。旧会议可能还没有 `user_messages`，需兼容回退。

`GET /api/meetings/{id}/events` 是当前 SSE snapshot，`id` 对应 revision，重连用于恢复观察；`POST /api/meetings/{id}/stop` 明确停止当前及后续发言。当前是有界、只读会议；P3 新增有限发言、最多双流和显式发言请求，准确合同见 7.8，不宣称支持无限接话。

### 7.4 已实现的 mobile 转发范围

`/api/mobile/v2/meetings` 已转发到同一个桌面 `MeetingService`，返回 `MeetingView` 并设置 `Cache-Control: no-store`：

| 路由 | 实际行为 |
|---|---|
| `GET /api/mobile/v2/meetings` | 当前账号会议列表。|
| `GET /api/mobile/v2/meetings/{id}` | 读取同一会议快照，不触发推理。|
| `POST .../{id}/start` | 体为 `{skip:false}`，显式开始/恢复同一桌面会议。|
| `POST .../{id}/stop` | 停止同一会议。|
| `POST .../{id}/input` | 原样传递 `text, material_ids, request_id`，复用权限与幂等。|

当前没有 mobile 命名空间的会议 create、default-host、events 或独立材料上传/下载别名；这些功能使用上文已认证的 `/api/meetings` 与 `/api/conversation-materials`。不要猜造 mobile 子路由。既有 `/api/mobile/v2/capabilities` 的 `features` 仍只有 `runs,event_replay,cancel,approvals`，不代表已经声明会议资料能力；接入旧设备时读取该设备 OpenAPI 的实际路由与请求字段，不能只凭 v2 或一个成功 GET 就发送会被旧服务忽略的资料字段。

### 7.5 DSH 只读 capabilities

`GET /api/engines/dsh/capabilities` 已实现，只读当前账号 runner 配置、本地组件文件与匹配运行时签名的历史 ACP initialize 记录；不启动进程、访问模型/网络、安装插件或授予权限。

返回 `contract_version=1, engine="dsh", configured, runtime_present, engine_version, component_version?, upstream_commit?, runtime_signature, session_id?, handshake?, capabilities[]`。每项为 `id, profile(acp|web|unknown), declared_by[], present?, negotiated?, status(declared|present|negotiated|unverified|unsupported)`；`handshake` 含 `protocol_version, agent_info?, agent_capabilities, runtime_signature, observed_at`，时间是历史 Unix 秒，不是在线探测结果。

Android 应分别显示“适配器/包声明”“本地文件存在”“历史协商支持”“尚未验证”。`present=true` 不证明插件运行成功，`handshake=null` 不表示应该自动启动一次会话。`native-subagents.read` 现为 adapter `declared`；实际数据从下方 Host 快照读取，不将声明当在线支持。原生 Room 父子恢复已在桌面实现，旧 Team/私聊仍在分阶段迁移。不要通过 `/engines/dsh/web` 或 `/plugins/workspace` 做无副作用探测，这些现有入口可能启动 web profile；不把桌面 loopback URL、订阅凭据或 cookie 直接转给手机。

### 7.6 Trae 的最短接入顺序与剩余提案

1. 保留现有工程差异，先核对目标设备 OpenAPI；建立兼容的资料 DTO、默认主持 DTO 与会议扩展字段，保留未知字段/未知状态的回退。对不支持的新路由/字段禁用相应提交入口并保留草稿，不使用模拟成功。
2. 头像名称多选接批量路由；名单与加入动作不触发专家运行，明确区分真人邀请。提交成功后以服务返回 `participant_id` 更新选择与 mentions。
3. 为房间、会议创建草稿和已有会议分别保存上传列表/文字。后台完成回调按原 scope functional 更新，旋转屏幕、切房间或恢复进程不能把 A 文件写到 B，也不能重发已完成上传。
4. 会议创建、补充消息各保存独立 request id；网络超时重用同体，编辑后生成新值；群聊沿用 `idempotency_key`。先保存配置再显式开始，停止使用控制路由，不能把补充资料等同打断。
5. 读取 mobile 快照与桌面 SSE 时只观察已存在执行；按 revision 去重。使用只读 DSH capabilities 解释设备能力，不在手机实现 DSH 调度、插件安装或订阅导入。
6. 接入后再按第 5 节执行两个 flavor 的针对性检查和设备联调，交付真实命令/结果；本次文档更新没有执行 Trae、修改 Android 源码或生成 APK。

已经落地的桌面原生 Room 父子 WaitSet、统一 Room/会议持久核与 DSH 只读原生子树见下节。有限发言和最多双流见 7.8，DSH 纯模型来源见 7.9。尚在实现或提案的能力不可虚构路由：旧 Team/私聊全部迁移、任意跨引擎唤醒及累计预算、手机端 Univer 文档审阅代理。现有本地 room/run/会议/mobile 接口与云账号房间分别按当前实现接入，不能把本轮本地合同宣称为跨所有 Xfriend 部署的云同步已完成。

源码核对入口：`src/octop/api/routers/{rooms,conversation_materials,meetings,mobile_meetings,engines}.py`、`src/octop/infra/collaboration_models.py`、`src/octop/infra/agents/teams/meetings.py`、`src/octop/infra/engines/capabilities.py`。最终全仓检查结果由桌面集成记录提供，本节不替代构建、安装或真机验收。


### 7.7 2026-10-20 桌面阶段更新：持久执行与 DSH Host 快照

桌面已新增 026 SQLite/PostgreSQL 迁移。Room 和会议现在共用同一进程内 `RuntimeScheduler`、持久 `runtime_runs` 和三个执行槽；source id、旧 thread、会议 slot 和 revision 保留。不要让 Android 生成第二套执行队列。多进程/云 server_tasks 租约不是本轮本机 scheduler 的合同。

Room 新扩展字段保留未知兼容：run 含 `generation, scheduler_owner, waitsets, safe_recovery?`；task 含 `caller_task_id?, depth, scheduler_owner, error_code?`。`caller_task_id` 才是真父子关系，旧 `parent_task_id` 仍用于转派关系。task 新状态有 `waiting_children/resume_queued/resuming`。WaitSet 为 `{wait_id,parent_task_id,child_task_ids,generation,state,checkpointed,resume_count}`，其 state 为 waiting/ready/resuming/consumed/cancelled；机器 continuation 不弹成人工工具批准。

已确认 checkpoint 的安全重启 Room 可以显示“继续任务”，调用原 run input 并由服务复核。不要仅看 waiting_user 就承诺全部孩子已经结束，也不要自行重发旧 dispatch。旧活动会议重启变 paused；显式 retry 创建新 attempt，已完成 slot 不重跑。

`GET /api/engines/dsh/host-facts?profile=web|acp` 已提供只读、按当前账号隔离的快照；不传 profile 读两者，不会启动 Host、模型或网络。响应为 `{contract_version:1,engine:'dsh',hosts:[...]}`。每个 Host 包含 `profile,boot_id,runtime_signature,observed_at,online,inventory_available,model_catalog_available,model_catalog_observed_at,plugins,bundles,providers,models,native_subagents?`。时间均为 Unix 秒，用服务器时区展示。

`online` 必须由桌面已跟踪的同一进程、boot ID、runtime signature 与最近快照共同确认；历史数据仍保留但 offline。插件 bundle 的 `installed_version` 不证明内存版本；`loaded_version/restart_required` 当前为 null，不把 null 显示成否。bundle.loaded=true 仅表示至少一个声明 Host 入口 active，详细入口状态在 plugins。模型目录不代表可以通过 Xfriend 调用，当前 snapshot 中 `execution_available=false`；Claude preset 限制必须保留。

`native_subagents` 的 owner 为 dsh。`children` 给出 root/child/parent session、depth、mode、activity 和 has_children；activity=inactive 只表示未驻留，不能当完成。`runs` 仅覆盖当前 Host 已观测 start/end，真正结束由 state=ended/stop_reason 给出。`catalog_complete=false` 或 diagnostics 表示记录不完整。`admission` 明确 native-policy、scope=host-root-forests，`xfriend_global_budget_enforced=false`、实时容量和深度为 null。手机只展示关系与事实，不能把这些节点重新提交到本体 scheduler 或绕过 DSH 父关系直接续接。

暂停/下一议题/结束和有限发言合同已在桌面 P3 实施，见 7.8；本节只读信息本身不是控制 API。此交接仍没有执行 Android 编译或真机操作。

### 7.8 P3：用户优先控制、有限发言与点名

桌面原 source 前缀新增 `POST /api/rooms/{room_id}/runs/{run_id}/control` 和 `POST /api/meetings/{id}/control`。体为 `{action:pause|resume|next|finish,request_id,generation,text?}`。同 ID 同体幂等，改体或旧代拒绝；request_id 必须随一次明确操作保存。手机不猜造 mobile 别名，先按目标设备 OpenAPI 核对这些路由。

控制响应可能为 `state=cancelling`，表示已撤销旧流展示、引擎尚在清理。必须继续观察，直到取消确认；不能立即显示已空闲或主动重发模型请求。旧代消息保留正文，但不再拼接为当前流。网络/5xx 结果不明时使用原 ID 同体重试；确定的 4xx 校验/权限拒绝应刷新快照并允许用户重新操作，不把旧代请求无限重试。

创建/输入中的 `discussion_preset=user_led|concise|active` 分别给一轮主动可见发言 1/2/4 条，`speech_parallelism=1|2`。主持总结另行受控，后台子研究不变成多条公开发言；旧客户端省略预设时保留有界旧语义。按 `task_id` 区分同 slot 的重试 attempt，`active_speakers` 的 generation/许可才表示当前发言者，不覆盖旧代历史。

`POST /api/rooms/{room_id}/runs/{run_id}/floor` 与 `POST /api/meetings/{id}/floor` 接受 `{request_id,generation,participant_ids:[1..2个真实ID],text?}`。Room 使用返回的 `floor_participant_ids` 与当前 enabled 成员求交；会议只在 `discussion_mode=free` 支持，目标来自 participants，不含主持。请求只排队，不自动开始 draft/paused、不重置预算、不批准工具。用户显式继续仍走 control。

`floor_requests` 状态为 pending/granted/superseded/cancelled，分别展示排队中/已获许可/被新议题或点名替代/已撤销。free 主持依据真实完成发言与用户意见动态选择下一批；同一成员最多连续两次、最多双流及根任务预算继续约束。用户点名优先于下一次主持选择，不暗中打断当前调用。非法主持输出会暂停并说明原因，不伪造发言。

以上桌面定向验证已通过，未做 Android 接线、真机跨端打断或完整生产迁移。

### 7.9 P4：DSH 文本模型来源与纯模型会议

`GET /api/engines/dsh/model-sources` 返回 `{contract_version:1,choices:[...]}`，只读当前账号已跟踪的 Host，不启动运行时、不进行登录或模型测试。choice 含 identity、host_boot_id、provider_id、model_id、name、eligible、reason、model_reference。只有 eligible 且存在 model_reference 的项可用于文本模型席位；候选可选不等于真实订阅推理已验证。

将返回的 `model_reference` 原样放入创建会议的 `model_participants[].model`，内置主持可用 `host_model`。不要自行编码/拆解引用、同步凭据、把 boot_id 当长期模型 ID，也不要把引用送进原生专家/团队 `member_models`。稳定引用绑定精确 runtime/plugin/provider/model 身份；版本变化后重新选来源，不静默回退普通供应商或收费 API。

创建和每次执行都会按当前 User 重新核验来源。未填席位 name 时后端用候选名称，不在标题展示整串编码；用户自定义名称保留。圆桌/free、主持选人、单人/双人输出均使用同一个持久调度池。输出达到上限会保留部分正文并暂停，不能当完整结果。

当前 WorkBuddy 0.7.8 要求 rc.2，不能在 alpha.2 Host 上宣称已兼容；dsh-claude 的 preset 不是普通文本模型。真实订阅、插件组合及 Android 实机仍为 UNVERIFIED，凭据继续由 DSH 插件持有。手机不得把桌面私有 loopback 桥直接暴露给远端。


### 7.10 普通对话、Team 与任务树续办

桌面 `/api/trees` 现在保存普通对话 external root，`source_thread_id` 和根节点 `thread_id` 仍是原聊天线程。根节点的 title/text 可空，不能据此丢弃会话；原 prompt、连接器运行配置和凭据不从这个 View 返回。TaskNode 扩展 `caller_task_id` 和 `external_root`，状态包含 `waiting_children/resume_queued/waiting_user`。`waiting_user` 是实际人工审批，机器等待不能弹成批准按钮。

`TreeView.can_resume_parent=true` 才可显示“恢复父任务”，调用既有 `POST /api/trees/{id}/start`；`recovery_blocked` 是禁止恢复的原因，不是要手机自行批准的信号。普通 interrupted 或未知结果不自动重放孩子。原对话的人类审批继续用原聊天审批入口和 pending_id；TaskTree 独立孩子的人工审批才用 `/api/trees/{id}/nodes/{node_id}/permission`。手机不能将这两种批准互换。

新 Team 的真实 ask_agent 从服务端生成成员 thread/session、冻结模型和模式，孩子完成后在主持原线程汇总一次；旧在途 inbox 保留原 owner。手机只观察同一个执行，不根据每条成员结果再发“请汇总”。当前 User 在实际获槽、派工和审批恢复时复核，界面切主题/布局不创建另一轮执行。

### 7.11 DSH 双向调用、审批身份与插件状态

Room 原生父任务与 DSH 专家现已完成双向工具桥。DSH 父等待孩子时保留同一个 ACP prompt / Node promise，让出本体执行槽；恢复后只注入真实结果，不重发原 prompt。native 使用实际 LangGraph checkpoint；DSH 的这一 live 续办仅在同进程、同 boot/grant/generation 存活时有效，进程丢失是未知结果，不能冒充持久检查点恢复。

Room 的 DSH 原生子代理通过固定 alpha.2 admission contract 在 start/send/cold-resume 执行前核对同根累计预算；这些引擎内部节点由 DSH 执行，本体不重新入队。Host 快照中的全局预算声明不自动变成每个独立 Web/ACP 会话都受本体调度。TaskTree 现已接同一私有桥，实际节点与 DSH 内部准入合计受总任务 12、深度 2 的限制；普通对话与 Team 使用原线程共享这一调度。

TaskTree 的 DSH 活等待始终 `checkpointed=false`；只有实际原句柄仍在本进程时可以续办。重启、句柄丢失或排队后不再存活时，View 返回 `interrupted`、`DSH_OUTCOME_UNKNOWN`，`can_resume_parent=false`、`recovery_blocked=dsh_outcome_unknown`。手机应展示结果未知与禁止恢复的原因，不能自动重发、回退 Native 或增加第三种恢复按钮。最终冻结源码和新安装包的结果以桌面实施记录为准。

DSH Room 审批须保留服务返回的原 `approval_id`、`expected_generation` 和 opaque `option_id`；只能展示当前真实 options，不能从 approve/reject 或名称推测 option_id。网络结果不明的同请求重试不得产生第二次投递。真实订阅登录与远端账号操作并未在本轮验收。

桌面 DSH 专家已有六插件推荐与真实状态区；推荐版本不是已安装版本，Web 与 ACP 的 installed/enabled/loaded/compatibility 分开。缺快照显示未知，历史快照不能当在线。按钮只进入已有 Web 原生插件管理器/工作区；没有新的手机插件安装接口。WorkBuddy 的 rc.2 版本要求、Claude preset、Office Pro 发行授权及文件/WS 边界仍须保留。不得将桌面私有 loopback 地址、DSH 凭据或事实库复制到手机。

本次仍仅更新交接文档，没有执行 Trae、修改 Android 业务源码、生成 APK 或进行真机验收。
