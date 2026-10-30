# Android 0.1.0 验证记录

日期：2026-10-13。项目目录：`D:/xfriend-android`。

## 构建与检查

使用 JDK 21 与 Android SDK 34 执行：

```powershell
./gradlew.bat --no-daemon --max-workers=2 :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
```

最终日志：`verification-clean-daemon.log`，结果 `BUILD SUCCESSFUL in 54s`。

运行时测试 11 项、平台地址测试 2 项，共 13 项，失败 0、错误 0、跳过 0。覆盖工具往返、知识与技能绑定、中文检索、工作区路径与符号链接限制、写入回滚、取消、模型失败、响应截断、团队部分结果以及平台源地址限制。

最终 Lint 为 0 个错误、3 个依赖升级提示（Compose BOM、Activity、Lifecycle）；本次固定已编译验证的依赖组合。

首次编译发现 AtomicFile 接口不兼容，已改为检查 baseFile 与旧备份文件。首次 Lint 提醒设备迁移规则不明确，已加入云备份及设备迁移排除规则。最终复验采用新 Gradle 进程，避免本次会话重复构建后的旧进程通信等待。

## 模拟器操作

在 Pixel_9 Android 模拟器上安装 APK 并启动成功。实际操作创建了 AndroidTest 专家、含两位成员的 AndroidTeam、EvidenceSkill 技能和 AndroidManual 知识文档；检查应用私有状态文件确认落盘，重启后专家仍显示，覆盖安装最终 APK 后四类记录仍保留。

已打开模型设置及平台登录表单；未使用真实账号登录平台。测试结束后仅清除本次模拟器测试应用的数据，并重新启动验证出厂中文专家。首页截图 `android-chat.png`，资源页操作截图 `android-resources.png`。

未调用真实模型，运行时协议测试使用受控响应。未完成真实手机、真实供应商模型、真实平台租户及系统后台执行验收。

## 交付

`dist/Xfriend-Android-0.1.0-debug.apk`，16813213 字节，Android 8.0 起安装。APK v2 签名验证通过，属于开发调试签名。

SHA-256：`ab01e83bd2cf0331ae7561c7b9135a5654e7cf66772fdce751be1e15113e144f`。

校验清单位于 `dist/SHA256SUMS.txt`。本次没有修改原桌面项目，也没有提交、推送或发布商店版本。
