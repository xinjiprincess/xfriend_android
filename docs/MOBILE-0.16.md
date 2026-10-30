# XFriend Android 0.16：供应商账号与手机能力

版本 0.16.0-debug，versionCode 16，2026-10-29。沿用上一版 `.debug` 包名与本机调试签名，覆盖安装保留该调试应用的数据。P4 子代理和登录授权时间校验修复一并保留。

## 使用入口

1. 星助理设置 → 供应商与账号管理 → 添加供应商或账号。API 填写 HTTPS 地址、密钥和模型；订阅渠道按设备码授权或选择本人显式导出的 JSON。保存后“设为当前”。可先“测试连接”；测试会向服务商发起真实模型请求。
2. 对话输入框上方选择“执行”。在供应商页开启“工具调用”，并使用实际支持工具的模型。“问答”和“计划”不执行手机写入。
3. 星助理设置 → 手机能力与权限。按需允许通知、闹钟和提醒。需要控制当前屏幕时，在 Android 无障碍设置手动开启星助理服务，再开启“本次手机控制”。退出账户、切换账户或进程重启会关闭本次控制。
4. 可以要求“明天上午九点提醒我开会”“每天十点提醒我喝水”“创建买牛奶的事项”“把这段内容保存为 note.txt”。时间以 phone_status 返回的当前时间和时区为准，工具会拒绝过去时间。
5. 手机能力页查看当前账户的事项、提醒和文件；文件可通过系统文件选择器导出，事项可手动取消。

## 供应商范围

依据用户指定的桌面源码快照 `/home/lyon/Xfriend-desktop-source-20261029-033725/octop`。目录包含 49 个模板：桌面锁定版 40 个 API 模板、Codex、ONNX，以及 7 个原生账号来源。模板是配置起点，所列模型是否可用取决于上游账户；支持手填模型和获取账号模型清单。

| 渠道 | 手机端行为 |
| --- | --- |
| API 与自定义 HTTPS | OpenAI Chat Completions、OpenAI Responses、Anthropic Messages、Gemini GenerateContent；流式文字和工具循环。包含桌面 DeepSeek、MiMo、腾讯、阿里、火山、智谱、Moonshot、MiniMax、OpenRouter 等模板。 |
| OpenAI / ChatGPT 订阅 Codex | 设备码授权、刷新令牌、账号模型、额度、官方 Codex Responses 推理。与 OpenAI API 密钥独立。 |
| GitHub Copilot | GitHub 设备码授权、短期 Copilot 令牌交换、账号模型、额度；可选 Chat/Responses，模型列表按协议过滤。 |
| xAI OAuth | 设备授权、刷新和官方 Responses 推理。 |
| WorkBuddy 国内/海外 | 显式导入本人明文凭据 JSON，支持刷新、模型、额度和原生请求封装。Electron 加密凭据会明确拒绝，Android 不解密原桌面密钥库。 |
| Gemini / Antigravity 账号池 | 显式导入 accounts JSON；刷新、重新解析项目归属、模型/额度、CloudCode 原生封装；manual / auto / primarybackup 选择。不会在已开始的流中自动重放或切账号。 |
| Claude / Gemini 普通订阅 | 与该桌面原生实现一致，仅查询额度；不能作为聊天推理连接。需要聊天时选择对应 API，Gemini 也可选择上述账号池。过期凭据需在原客户端重新授权并显式导入。 |
| 火山 Agent Plan / Coding Plan | Chat、Responses、Anthropic 的套餐专用路径；不匹配直接报错，不回退按量计费路径。 |
| Ollama | 可配置手机可访问的 HTTPS 远程服务；不开放明文 HTTP，不默认连接手机 localhost。 |
| ONNX | 保留目录标识并显示不支持手机聊天；没有捆绑桌面嵌入模型运行时。 |

仅导入用户通过系统文件选择器选中的 JSON，不扫描其他客户端、环境变量或磁盘密钥。设备授权网页仅允许各服务官方验证域。账号导入/授权完成只保存连接，用户选择模型后再设为当前。

连接公开配置与凭据分开保存，凭据进入现有 AES/GCM 存储；配置代次和绑定摘要防止两文件部分提交将旧密钥挂到新地址。令牌刷新跨主代理、子代理与设置页串行，先保存旋转令牌再请求；撤销授权或配置更改会中断旧操作。删除当前连接会提示重新选择，不自动使用旧密钥发送。

官方 OpenAI Chat 请求将输出预算映射为 `max_completion_tokens`，兼容服务保持各自参数；依据 [Chat API 参数文档](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)。

OpenAI Responses 工具结构参考 [官方 function calling 文档](https://developers.openai.com/api/docs/guides/function-calling)，Codex 授权参考 [官方认证说明](https://developers.openai.com/codex/auth/)。订阅服务协议实现基于指定桌面快照，变动后可能需要适配。第三方协议来源与许可随 APK assets 一并包含，见 [第三方声明](MODEL-PROVIDER-THIRD-PARTY-NOTICES.txt)。

## 手机能力的实际效果

- **闹钟/提醒**：AlarmManager.setAlarmClock 调度本应用通知。需要通知和准确闹钟权限。当前不是系统时钟应用里的条目，也不保证持续响铃。系统重启、时间/时区变化后重新调度，每天规则按创建时选择的 IANA 时区计算；夏令时不存在的本地时刻跳过该日。一次任务错过时间标记 missed。打开能力页刷新时保留尚未到点的每日提醒，包括触发前最后一秒。
- **文件**：私有目录保存 UTF-8 内容，单文件上限 1 MiB，只接受单个文件名，保留同名已有文件，不覆盖外部文件；页面通过 SAF 显式导出。写入返回文件记录与 SHA-256。
- **事项**：当前账户的本机待办，可附一次/每天提醒，支持分页查询和取消。
- **定时任务**：当前执行本机通知提醒，不会到点自动调用模型、运行脚本或执行外部操作。
- **无障碍**：本次控制开关开启、账户仍有效且任务处于执行模式时，按请求读取前台 UI，并使用短时快照进行点击、滚动、返回、主页和打开指定应用。密码、输入框和敏感屏内容隐藏；系统权限、登录、验证码、付款、发送等流程需要用户手动接管。当前不支持自动输入文字。
- UI 工具返回 `action_dispatched` 仅表示系统接受操作，模型必须重新读取验证。未知结果保留待核查状态；不会自动重复点击。屏幕原始树仅供内存中的当前工具循环使用，检查点保存 stale_snapshot 标记，恢复后需重新读取。
- 本机写入采用稳定请求 ID，恢复不重复创建；不同请求 ID 表示用户新请求。没有把本机工具结果伪装成外部平台已同步成功。

## 验证与交付

完整构建：`bash scripts/build-linux.sh > .artifacts/build-0.16-verified-2026-10-29.log 2>&1`。结果、APK 哈希和签名见 `dist/2026-10-29-providers-phone/verification.json` 与交接文档第 13 节。

协议测试使用合成响应与无网络拦截器，覆盖 SSE 完整终态、工具参数、签名原样回传、协议/账号绑定、授权取消和模式限制。它们不能证明真实供应商账户已通过推理。没有使用用户真实账号、API 密钥或订阅令牌进行外部请求。

当前没有可用的真机验收；此前软件模拟器系统启动未完成，因此无障碍点击、厂商后台限制、准确闹钟实际送达、用户激活账号实登与真实模型调用仍需安装后验证。未清除任何真实设备的数据。
