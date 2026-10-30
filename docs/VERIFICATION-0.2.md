# Xfriend Android 0.2 验证记录

验证日期：2026-10-13。工程位于 D:/xfriend-android，基于 D:/octop 当前 Xfriend 1.4.7 的界面和账号协议。本轮未修改原桌面源码，未提交或推送。

## 构建与自动检查

使用 JDK 20、Android SDK 34、Gradle 8.7。最终构建日志为项目根目录 verify-0.2-delivery.log，结果 BUILD SUCCESSFUL（4 分 4 秒）。执行：

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-20'
./gradlew.bat --offline --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :app:testLocalDebugUnitTest :app:testCompanionDebugUnitTest :app:assembleLocalDebug :app:assembleCompanionDebug :app:lintLocalDebug :app:lintCompanionDebug --console=plain
```

| 检查 | 结果 |
| --- | --- |
| 本地版单元测试 | 25 项，0 失败，0 错误 |
| 同步版单元测试 | 25 项，0 失败，0 错误；同一套共享逻辑分别在两个构建变体中执行 |
| 两个变体 Android Lint | 各 0 错误、6 警告 |
| 桌面连接桥测试 | 14 项通过，日志 desktop-bridge/bridge-tests-handoff.log |
| 连接桥 Ruff F/I 检查 | 通过 |
| 两个最终 APK 签名 | apksigner verify 通过，v2 签名，单个开发签名者 |

Lint 警告涉及依赖更新、授权设备标识、资源版本目录及单色启动图标。当前保留原版彩色图标。原桌面代码没有改动，未运行其 make all；本报告不宣称桌面全量测试通过。

## 界面与连接验证

- Android 模拟器已安装两个不同应用 ID 的变体，可同时存在。
- 本地版登录和注册页面显示原版星星图标、玫瑰色按钮、原色系卡片与输入框。注册包含昵称、邮箱、手机号、密码和确认密码。
- 空注册表单提交显示“请填写昵称、手机号和有效邮箱”，未向云服务创建账号。
- 同步版连接独立合成测试服务，完成配对、HTTP 页面加载、WebSocket 消息往返。该服务不读取或操作真实电脑数据；页面明确标注测试数据。
- 该端到端连接检查在最终键盘选项与重试提示微调前完成；桥接协议未在微调中改变。最终 APK 另行重新构建、签名校验和安装。

界面证据：[登录截图](android-login-0.2.png)、[注册截图](android-register-0.2.png)、[隔离连接成功截图](android-sync-confirm.png)。

原图标与 Android 复制件 SHA-256 均为：

```text
0e61343f2df6f9610f22c67d4078756d5484f5675e5bf5a651c88b2fa73f9d3d
```

Android 37 预览模拟器在 2GB 配置下曾因 LOW_MEMORY 杀死 WebView/应用；临时以 4GB 内存启动后通过连接测试，未改写 AVD 持久内存配置。代码增加 WebView 渲染进程退出处理，但未另行注入崩溃验证恢复。此结果不代表真机性能与所有 Android 版本兼容性验收。

## 未完成的实机验收与功能边界

1. 未使用真实云账号进行登录、注册或收费授权操作；同服务接口及签名逻辑已实现，实际服务兼容性仍需真实账号验收。
2. 未调用真实模型或 PCMATE 平台账号，未在真机测试。
3. 只读探测确认当时运行的电脑版处于已登录状态。自动审批拒绝了启动真实桌面连接桥及 ADB 转发的命令，原因仅为“blocked by policy”。没有绕过此阻止；因此真实桌面页面、远程桌面、终端和浏览器控制尚未端到端验证。
4. 本地版在 Android 执行 Kotlin 智能体工具循环，调用远端模型；不是离线大模型，也不是整个桌面 Python/Harness 的等价移植。团队按顺序运行，知识检索为文本词项匹配，技能为 Markdown 指令。
5. 同步版设计为实时访问电脑同一份数据与原版 React 页面，需要电脑和连接桥运行；不提供离线双向库合并。设备本地主题等浏览器偏好不保证同步。
6. 当前交付开发签名调试 APK，正式发布还需发行签名与真机验收。

## 交付资料

- [移植设计方案](MOBILE-DESIGN.md)
- [电脑连接操作手册](../desktop-bridge/README.md)
- [本地版 APK](../dist/Xfriend-Local-0.2.0-debug.apk)
- [同步版 APK](../dist/Xfriend-Companion-0.2.0-debug.apk)
- [连接桥源码包](../dist/Xfriend-Desktop-Bridge-0.2.0.zip)
- [最终文件 SHA-256](../dist/SHA256SUMS-0.2.0.txt)
