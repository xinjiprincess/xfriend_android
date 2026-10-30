# XFriend Android 0.18：启动权限、小窗、提示音与协议/预算修复

版本 0.18.0-debug / versionCode 18（2026-10-30）。同包名、同调试签名可覆盖 0.17。构建、测试、lint 与 APK 证据见 `dist/2026-10-30-launch-pip-sound-final3/verification.json`。

## 1. 启动权限

- 应用启动时检查麦克风、通知、准确闹钟和无障碍状态。
- 麦克风与通知（Android 13+）使用系统运行时权限对话框；准确闹钟与无障碍属于特殊访问，只经用户点击后打开系统设置，不伪造授权、不把打开设置页当成已授权。
- 权限被拒绝不阻止文字聊天；相关功能会返回实际缺失项。启动时会再次提示仍未授予的必需权限，用户可以稍后手动开启。
- 无障碍仍是可选能力；只有用户在系统设置开启服务并在手机能力页开启“本次手机控制”后，agent 才能读取/操作当前界面（本应用自身配置页面始终禁止 agent 操作）。

## 2. 小窗（画中画）

- `MainActivity` 声明 `supportsPictureInPicture`，支持 Android 8.0+ 的画中画小窗，使用 9:16 比例。
- 开始录音并成功启动录音器时，自动请求进入小窗；用户也可以发送“小窗”“打开小窗模式”“进入画中画”等明确命令进入。
- 小窗请求只由当前用户操作或录音启动触发；引用、网页和工具文本不会触发。设备/系统不支持时会直接说明。
- 小窗不改变任务队列；录音仍在当前前台/小窗 Activity 内完成，不新增后台录音服务。

## 3. 消息提示音

- 新增本机发送提示音和任务完成提示音，使用系统通知音频流短音，不依赖网络或通知权限。
- 默认关闭；用户可在“星助理设置 → 消息提示音”手动开启。设置随当前账户 Store 持久化。
- 启动时已有的历史完成记录不会一次性补响；只在本次进程中新完成任务时播放回复音。

## 4. 模型协议兼容修复

针对“帮我比较成都和重庆两个地区的 GDP 对比”返回“模型返回格式不兼容或者响应不完整”的问题：

- `[DONE]` 不再是 OpenAI-compatible SSE 的唯一终止条件；合法的 `finish_reason`（stop/tool_calls/length）即可结束，兼容省略 `[DONE]` 的网关。
- Content-Type 只作提示：即使服务端把 SSE 标成 `text/plain`，或把完整 JSON 标成 `text/event-stream`，也会按实际内容解析。
- 忽略只有 `usage`、`choices: []` 的尾包；容忍重复的 `finish_reason`；兼容 `max_tokens`/`end_turn`/`tool_use` 等终止名。
- 非流式 JSON 支持 `message.content` 为文本片段数组，并在缺少 `finish_reason` 时根据文本/工具调用推断；不会凭空生成答案或工具。
- `finish_reason=length` 的工具参数不再作为可执行调用暴露，保留可见文本并报告截断。
- 流式内容仍要求真实终止信号；只有 EOF 和半截文本不会变成成功。

## 5. 预算误报修复

“当前代理的模型请求或预算已用尽，进度已保留”原先把普通根任务按子代理的 8 次 / 64,000 token 预算限制，长对话/工具查询很容易提前耗尽。

- 普通根任务预算改为 24 次模型请求 / 512,000 预留单位；子代理仍为 8 次 / 64,000，单个任务受 64k 上限约束。
- 全局树预算提高到 64 次 / 1,024,000，并新增检查：任何子代理请求都必须同时遵守根任务预算，不能绕过根预算。
- 失败任务的“重新发起”会创建新的根任务并使用新预算；旧检查点仍保留自己的预算快照，不静默提权或重放。
- 预算耗尽仍是真实失败，不会伪装成功；只是普通根任务不再被过小的子代理预算误伤。

## 6. 验证

- 完整构建：`bash scripts/build-linux.sh`，日志 `.artifacts/build-0.18-final3-2026-10-30.log`，`BUILD SUCCESSFUL in 10m 47s`。
- 单测：XFriend **542**、XSalers **542**，共 1084，0 failures / 0 errors / 0 skipped；较 0.17 的每变体 536 项新增 6 项。
- lint：XFriend 0 errors / 25 warnings；XSalers 0 errors / 26 warnings，未关闭检查。
- 新增回归覆盖：无 `[DONE]` 的 finish、text/plain SSE、usage-only chunk、content 数组/缺失 finish、截断工具参数、根任务预算与子代理预算边界、小窗命令白名单。
- 真机、真实供应商和真实通知/无障碍仍未验证；详见 `dist/2026-10-30-launch-pip-sound-final3/verification.json`。

## 7. 交付 APK

- XFriend：`dist/2026-10-30-launch-pip-sound-final3/XFriend-0.18.0-debug-20261030.apk`，SHA-256 `fcd628b5de6079e1b594d69f7a38839323d2d4d5bcd90e0930519a6e94a5e44f`
- XSalers：`dist/2026-10-30-launch-pip-sound-final3/XSalers-0.18.0-debug-20261030.apk`，SHA-256 `4132a607c70baaaaf328b5596e6348d5d7653b362e71c4965dcaa88d98d0d3bc`
- 包名：`com.singu.xfriend.mobile.debug` / `com.singu.xsalers.mobile.debug`；versionCode 18；versionName `0.18.0-debug`。
- 签名证书 SHA-256：`01c610fa028d203f1e37248b0e812a10c5109cf20139c0e814e683d90827e7ec`。
- 同签名 `.debug` 包可覆盖 0.17；0.17 的检查点、账户数据和事项保留。小窗是否可用取决于设备/系统是否支持画中画。
