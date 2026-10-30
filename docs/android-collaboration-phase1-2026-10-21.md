# Android 协作第一阶段

状态：实现已落，最终双 flavor 全单元测试与 lint 正在运行，尚不代表手机实机验收完成。2026-10-21。

范围为 `app/src/main`、`app/src/test` 及本文档。没有修改 Octop、云服务、个人凭据、版本号或安装包，没有执行 APK assemble、安装、发布或提交。Android 工程没有 `.git`；总控在改动前已保留完整源文件基线。

## 当前用户、模型和群配置

- 手机请求继续只携带原配对 Origin/Cookie，不注入手机 API Key，也不更改远端可信身份。
- `GET /mobile/v2/models` 只读取当前调用者授权的 `ref/label/provider/model` 目录，不修改个人默认，也不发模型探测。
- 新建群显式提交 `coordination_mode=leader`，不挑选第一位成员作为负责人。没有 AI 成员时仍允许纯真人消息，预检的 `ready=true, coordinator=null, participants=[]` 合法。
- 群协调方式支持明确负责人、独立模型、手动 @，旧群 `legacy` 原样保留。Settings PATCH 的 nullable 字段显式提交 null 以清除旧配置。
- 逐席位支持继承专家配置或本群覆盖，旧记录缺 `model_mode` 时显示为原有固定覆盖；纯模型席位只允许显式模型绑定。
- Settings 和席位 PATCH 都携带共享 `settings_revision` 的 `expected_revision`；保存成功后 GET 完整 Room 读回。版本冲突保留表单，不自动覆盖其他人的修改。
- 发送前读取宿主 preflight，展示角色、模型来源和不可用原因，仅以 `ready` 决定能否发送。不因为缺少 coordinator 对象拦截纯真人群，不因目录为空切换到手机或其他账号模型。

核心文件：`RemoteCollaborationProtocol.kt`、`RemoteRoomSettings.kt`、`RemoteRooms.kt`、`FriendRemoteClient.kt`。

## 群资料与发送

用户选择文件后上传到现有 staged conversation-materials 接口。此时只有原件上传，不绑定 Room；明确发送消息时才携带 `material_ids`，由宿主重新授权并建立 scope。

- 最多 20 份资料，文件读取使用有界流式复制，限额 100 MiB。上传未结束时不允许发送群消息或生成负责人方案。
- 原件状态与内容可读性分开；UI 消费 canonical `content_status`。ZIP 的旧 preview 状态即使为 unsupported，也能显示 partial，不能据此声称整包不可读或已完整阅读。
- 消息允许最多 32000 字，也支持仅发送资料；空文本且无资料仍被拒绝。
- 草稿保存文本、资料公开元数据和 @ 目标。预检失败保留草稿和附件；重试沿用已经保存的原请求内容与 idempotency key。
- 成功后在同次本地提交中清理待提交标记和匹配的草稿，不删除用户后来另写的不同草稿。

核心文件：`RemoteStagedMaterials.kt`、`RemoteMaterials.kt`、`RemoteRooms.kt`。

## 负责人任务

新增入口调用现有：

- `GET /mobile/v2/trees/responsible/capabilities`
- `POST /mobile/v2/trees/responsible`，响应 202，创建可审阅方案

请求使用原 `ResponsibleCreate` 字段：`request_id/goal/leader_expert_id/leader_model/allowed_expert_ids/material_ids`。不新增调度器，不由手机生成或执行任务树，不调用 start 来伪装方案确认。

提交内容在网络请求前持久保存；不确定结果的重试使用同一 body 和 request ID。创建后进入原 `RemoteTaskTrees`，继续使用原 proposal digest、generation、revision 和精确审批选项确认机制。

核心文件：`RemoteResponsibleCreator.kt`、`RemoteTaskTrees.kt`。

## 会议模型

新建会议可选择主持人模型、所选专家的逐成员模型覆盖，以及纯模型席位。只发送模型引用，不发送凭据。默认主持人不提交虚构的 host_id；可用的显式 host_model 不被“默认模型尚未配置”提示错误拦住。创建成功后 GET 会议读回，然后仍由用户明确开始会议。

代表性请求如下，所有标识仅为示例：

```json
{
  "request_id": "stable-request-id",
  "title": "规划会议",
  "topic": "明确目标与验收标准",
  "expert_ids": ["expert-a"],
  "team_ids": [],
  "host_model": "native-owned/model-one",
  "member_models": {"expert-a": "native-owned/model-two"},
  "model_participants": [{"model": "native-owned/model-three", "name": "模型席位"}],
  "material_ids": ["material-id"]
}
```

`host_id` 仅在明确选定其他主持专家时发送。纯模型会议可使 expert_ids/team_ids 为空、model_participants 非空。重试保留整个原请求，不按当前界面重新拼写旧 body。

## 能力、错误和文件导出

Features 完整解析 `rooms/meetings/conversation_materials/models/room_model_settings/responsible_tasks/task_trees/memory/diagrams/document_exports`；未声明的新能力按 false 处理，界面只开放真实能力。保留 `conversation_materials` canonical 字段，没有新增 materials 线缆别名。

安全错误保留 domain code，显示固定安全文案，不回显任意上游 message、headers 或 trace。原 APPROVAL_STALE 等 409 reason 兼容保留。连续 SSE 游标、审批摘要哈希、幂等决定和账号 generation 保留。

报告、transcript、资料、diagram 和普通成果文件复用 100 MiB 流式下载入口。数据先写同目录临时文件，完整成功后才替换目标；异常或取消清理临时文件，不把截断结果标为成功。报告不再先使用 body.bytes() 分配整包内存后才检查大小。

## 待提交数据的宿主作用域

群消息、控制、floor、会议草稿/申请/补充说明/追加成员、负责人申请及报告 pending/active 键绑定手机账号、规范化配对 Origin 和 cookie 的 SHA-256 指纹。键和日志不保存明文 cookie；请求仍只使用原宿主 Cookie/Origin 鉴权，不添加新的后端身份字段。同一 cookie 下恢复保留原请求 ID、附件、generation 和 digest。

来源未知的旧账号键及旧账号加 Origin 键原位隔离保留，不通过“确认原电脑”迁入当前配对。新配对不会自动提交旧请求。核心 run 本地 JSON 增加兼容的 `session_scope`；同 Origin 换 cookie 时，旧 run（含原请求编号和 partial）归档并清除可执行指针。旧版 scope 为空且没有 run ID 的记录只读；已知 run ID 的旧记录必须前台 GET 通过宿主当前 owner 校验、并匹配 run ID 后才绑定，后台不自动探测 legacy。隔离历史可只读查看，不带提交、取消或审批入口。

网络提交、快照、SSE、审批的写回检查发起时的 session，Vault 在同一账号 generation 临界区执行 expected-session CAS；后台仅观察当前绑定记录，拒绝旧 session 晚到写回和通知。旧 worker 只移除自己的 job。协作子树按配对指纹重建，文件选择与相机返回同样检查原配对。此阶段没有重建全局历史数据库或声称完成全端云同步。

## 验证记录

- 首轮双 flavor 定向单元测试：每个 flavor 51 passed，共 102 passed，0 failure、0 skip；包含新增 11 个协议合同用例。该记录对应第一版源码，不替代最后补丁后的验证。
- 最后补丁增加新建群、不虚构负责人、纯真人预检与文件单独发送等用例。
- 配对 scope 补丁前首次完整运行：两个 flavor 各 325 passed，共 650 passed，0 failure、0 skip，双 lint 成功，耗时 31 分钟；证据 `.artifacts/android-collaboration-phase1-full-6f21c4108cd74c5a8feca319bbac8f18/`，不会作为 scope 补丁后验证。
- scope 补丁新增 12 个本地协议回归，包括有界 latch 控制的旧 POST/快照晚到、同 cookie 原 ID 恢复、同 Origin 换 cookie、legacy 隔离与后台过滤。
- 最终命令：JDK 20，`./gradlew.bat --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process :app:testXfriendDebugUnitTest :app:testXsalersDebugUnitTest :app:lintXfriendDebug :app:lintXsalersDebug --console=plain`。
- scope 补丁后的完整新运行已成功：Xfriend 337 passed、Xsalers 337 passed，共 674 passed，0 failure、0 error、0 skip；双 lint 均成功，耗时 32 分 4 秒，exit 0。新增 scope 模块每个 flavor 的 12 项均通过；没有 `--tests` 过滤。
- 最终证据目录为 `.artifacts/android-collaboration-phase1-session-final-c7e82c5ac3b64327a331ee5d99e87a7e/`，包含原始 `gradle.log`、两个完整 JUnit 目录、双 lint XML/HTML、`verification-summary.json`、全 main/test 的 before/after 哈希和 `scope-patch.diff`。报告时间戳均属于本次运行，`source_changes_during_run=[]`；应用与测试源码相对基线共有 21 个新增/修改文件。
- 历史 10 月 17 日 lint XML 已独立保存：Xfriend 0 errors/17 warnings；Xsalers 0 errors/18 warnings。首轮完整当前源码及最终 scope 修补后 lint 均为 Xfriend 22 warnings、Xsalers 23 warnings；最终 warning 的规则与文件均在首轮报告中出现。相关源码/资源已与本轮 source-before.zip 核对；源码基线没有执行 lint，不将旧报告当作当前基线运行结果。不为本轮检查修改无关 CRM 代码。

所有模型和网络合同测试使用本地模拟响应，没有访问个人账号或真实上游模型。尚未 assemble/安装 APK，实际手机布局、Activity 重建、系统后台限制、真实配对与资料往返仍需总控组织设备验收。本文件不把旧版测试、静态编译或本地 HTTP mocks 当作实机已通过证据。
