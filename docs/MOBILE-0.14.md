# Xfriend Android 0.14：本机 Agent 与专家群聊

日期：2026-10-27。版本：0.14.0 / code14。交付类型：Android Debug 预览版。

## 本轮能力

- 本机 Agent 延续既有 Kotlin 运行时，可组织模型调用、工具循环、技能与知识上下文，并在手机的专家工作区执行已允许的文件工具。
- 新增本机专家群：1–8 位不同专家、持久共享消息、发言者姓名和每群草稿。
- 每次发送选择全体或指定一位专家，各选中专家回复一次；下一轮由用户继续发送触发。
- 群只读取本群公开消息，不读取／写入专家私聊或其他群历史；成员按原专家的模型、技能、知识与工作区权限运行。
- 群任务接入原有加密队列和前台执行服务，支持停止、任务查看、失败保留、检查点续接及未知写入核查。
- 群记录支持系统文件选择器导出 Markdown。
- 对话工具栏、执行位置菜单和工作台加入本机群入口，电脑配对与既有云协作保持原协议。

“手机本机执行”指手机组织 Agent 与工具流程；模型推理仍访问已配置的模型服务。此版沿用产品账号登录，不新增免登录入口。桌面专属引擎／工具继续通过电脑连接使用。

## 使用

1. 安装 Xfriend 0.14 预览包，按现有流程登录账号，进入手机工作空间。
2. 在“我的 → 模型配置”填写 HTTPS 模型接口、模型名称和本人 API Key，可先使用连接测试。密钥沿用 Android 受保护存储。
3. 在工作台创建或导入手机可执行的专家；默认 Xfriend 助手也可以加入群。
4. 从对话工具栏的群聊图标，或工作台“本机专家群聊”卡片进入，选择“新建群聊”，填写名称和成员。
5. 在群中选择全体／指定专家，按需要选择问答、计划或执行模式后发送。写文件受原模式与工具限制。
6. 运行时可在群页或任务中心停止；失败时查看已完成发言，存在安全检查点时继续。取回原任务输入会保留原发言对象。
7. “导出记录”将当前群的公开文字保存为 Markdown。

## 验证与证据

证据根目录：`D:/xfriend-suite/.artifacts/mobile-edition-20261027-7c09a1`。

- `source-before.zip`、`source-before-sha256.json`：修改前源码快照与文件摘要。
- `baseline.log`：既有 AgentRuntime 与检查点集成测试基线通过。
- `red-queue.log`：新增群功能存在之前的失败记录。
- `focused.log`：28 项聚焦测试通过，0 失败／错误／跳过。
- `final.log`：最终稳定源码的双 flavor 单测、assemble 和 lint 全部通过（7 分 56 秒）；此前完整链也通过（`full.log`，19 分 33 秒）。
- 单测：Xfriend 356、Xsalers 356，共 712 项，0 failures／errors／skips；新增群运行时、队列、检查点集成和选择恢复共 19 项／flavor。
- lint：两个 flavor 均为 0 errors；保留原有 22／23 warnings。
- `final-before-sha256.json`、`final-after-sha256.json`：最终验证期间源码变化为 0。
- `apk-artifacts.json`、`*-apk-badging.txt`、`*-apk-signature.txt`：正常包身份、版本、签名及完整 SHA256；已核对不含 QA Activity。
- `emulator-ui-status.json`：三次隔离冷启动后 QEMU 挂起、ADB 离线；未形成可用模拟器，安装／交互／截图验收未通过，也未连接真机。
- `qa-build.log`：独立 `.qa` 测试包编译通过（3 分 58 秒）。夹具的首次 JSON 类型兼容错误已修，失败记录保留于 `qa-build-attempt1.log`；设备夹具未执行。
- `verification.json`：最终验证汇总；正常 APK 已复制到独立交付目录，QA 构建没有覆盖交付包。

正常预览包位于 `D:/build_apk/Xfriend-Android-0.14.0-preview-20261027-7c09a1/`：

| 产品 | 包名 | 文件 | 字节数 |
| --- | --- | --- | --- |
| Xfriend | com.singu.xfriend.mobile | Xfriend-0.14.0-debug.apk | 26,976,451 |
| Xsalers | com.singu.xsalers.mobile | Xsalers-0.14.0-debug.apk | 26,976,595 |

Xfriend SHA256：`27F2CEE658C4AD469CA60DA2FD0DED6B44B612BB0D0578970EF2DCD7043294E7`。

Xsalers SHA256：`5A46BD7B03BAE52B29C8A38E47117283620E0C8C388D25CCD1D3EC637860380F`。

最终完整验证命令（在 `D:/xfriend-suite` 执行）：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-20'
./gradlew.bat --no-daemon --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process -PsalesUiFixture=false :app:testXfriendDebugUnitTest :app:testXsalersDebugUnitTest :app:assembleXfriendDebug :app:assembleXsalersDebug :app:lintXfriendDebug :app:lintXsalersDebug --console=plain
```

本轮未运行后端 `make all`：后端产品代码未改，`D:/octop` 只更新设计与实施记录；Android 使用上述完整双 flavor 验证链。

独立审阅发现并修复：取回单成员群任务后对象被扩大为全体；恢复较早任务时群页显示较新已结束任务；发送受理与配置重建交错时编辑器旧草稿残留。目标恢复与活动任务选择另有纯 Kotlin 回归测试。

## 首版边界

本轮提供本机专家协作群；多人跨手机聊天继续使用已有云群。新的自建服务器登录适配、完整移动项目／待办门面、后台可靠系统推送、完整 Office／流程图编辑、iOS 原生版仍按原设计后续阶段推进。

自动化模型夹具不访问真实模型服务。模拟器与真机结果分别记录；本轮未连接真机，也未使用用户模型凭据进行真实推理。

QA 验收使用既有 `salesUiFixture=true` 独立 `.qa` 应用；其示例群、合成回答及测试入口不编入正常预览包。正常包保持原产品认证。
