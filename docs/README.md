# XFAMILY 文档索引（当前 0.12）

> 当前版本：**0.12.0**（2026-10-18，versionCode 12）  
> 当前状态：XSalers「通话录音接管」功能代码与页面已完成；**两 flavor 单元测试合计 600 项通过**；模拟器授权/接管/去重/删除链路验证通过（7 张截图已回填）；**正式 APK 已构建**（Xfriend 26,499,349 bytes / Xsalers 26,499,509 bytes，SHA 见交付说明）。  
> 本索引只负责“读什么、按什么顺序读、哪些是历史”。各文档内容以文件内标注的日期与证据为准。

## 1. 当前 0.12 必读（按顺序）

| # | 文档 | 说明 |
|---|---|---|
| 1 | [../README.md](../README.md) | 仓库总览、两个 App 能力、0.12 构建状态、验收边界 |
| 2 | [CALL-RECORDING-0.12.md](CALL-RECORDING-0.12.md) | 0.12 功能说明：能力边界、用户流程、页面结构、权限隐私、数据兼容、代码与验证 |
| 3 | [XFAMILY-0.12-交付说明.txt](XFAMILY-0.12-交付说明.txt) | 0.12 交付快照：版本、新增功能、证据、APK 实际值、未验证边界 |
| 4 | [DELIVERY-0.12.html](DELIVERY-0.12.html) | 0.12 交付报告（面向评审；含 7 张截图、APK 值与 SHA） |
| 5 | [verification-0.12.json](verification-0.12.json) | 0.12 构建 / 测试 / QA 证据的结构化记录（含截图 SHA 与裁剪说明） |
| 6 | [qa-0.12/](qa-0.12/) | 0.12 模拟器截图 7 张：`callrec-01-home` ~ `callrec-07-deleted`；`callrec-06-pending-delete` 为 04 底部卡片裁剪 |
| 7 | [XFRIEND-REDESIGN-0.11.md](XFRIEND-REDESIGN-0.11.md) | 0.11 视觉系统落地说明（0.12 沿用，未新增 token/组件） |
| 8 | [design/redesign-concept-android-spec.md](design/redesign-concept-android-spec.md) | 概念稿逐项提取的 Android 设计规范（实施基线） |
| 9 | [design/screens/](design/screens/) | 概念稿 23 张参考截图（非真机验收截图） |
| 10 | [XFAMILY-项目设计与开发交接文档-2026-10-17.html](XFAMILY-项目设计与开发交接文档-2026-10-17.html) / [.txt](XFAMILY-项目设计与开发交接文档-2026-10-17.txt) | 项目交接主文档（0.11 时点；0.12 功能以 CALL-RECORDING-0.12 为准） |

## 2. 0.11（历史，视觉系统仍沿用）

> 以下文档描述 0.11 时点，不能覆盖 0.12 的功能与源码事实；0.11 的视觉/组件体系仍是 0.12 的基础。

| 文档 | 版本/主题 |
|---|---|
| [XFAMILY-0.11-交付说明.txt](XFAMILY-0.11-交付说明.txt) | 0.11 视觉重塑交付：APK、构建证据、QA 回归 |
| [XFRIEND-REDESIGN-0.11.md](XFRIEND-REDESIGN-0.11.md) | 0.11 令牌映射、组件清单、页面范围、与概念稿差异 |
| [DELIVERY-0.11.html](DELIVERY-0.11.html) | 0.11 交付报告（历史） |
| [verification-0.11.json](verification-0.11.json) | 0.11 构建 / QA 证据 |
| [qa-0.11/report.md](qa-0.11/report.md) | 0.11 模拟器 QA：首轮 P0/P1/P2 修复与 §11 回归通过 |
| [design/redesign-concept-android-spec.md](design/redesign-concept-android-spec.md) | 0.11 视觉规范来源（0.12 继续引用） |
| [design/screens/](design/screens/) | 概念稿参考截图（23 张） |

## 3. 0.10 及更早（历史）

> 仅用于追溯设计与决策，不能覆盖当前源码与验收事实。

| 文档 | 版本/主题 |
|---|---|
| [XFAMILY-0.10-交付说明.txt](XFAMILY-0.10-交付说明.txt) | 0.10 四组设计改造交付 |
| [xfamily-0.10/](xfamily-0.10/) | 0.10 四组设计参考图（历史设计） |
| [DELIVERY-0.9.html](DELIVERY-0.9.html) | 0.9 交付报告 |
| [qa-ui-0.9.json](qa-ui-0.9.json)、[qa-ui-0.9-emulator.log](qa-ui-0.9-emulator.log) | 0.9 模拟器 QA（历史） |
| [verification-0.7.json](verification-0.7.json)、[DELIVERY-0.7.html](DELIVERY-0.7.html) | 0.7 验证与交付 |
| [verification-0.6.json](verification-0.6.json)、[DELIVERY-0.6.html](DELIVERY-0.6.html) | 0.6 |
| [verification-0.5.json](verification-0.5.json)、[DELIVERY-0.5.html](DELIVERY-0.5.html) | 0.5 |
| [MOBILE-DESIGN.md](MOBILE-DESIGN.md) | 0.2 移植方案（旧主色 `#E85D75`、抽屉侧栏；已被 0.10/0.11 取代） |
| [PORTING.md](PORTING.md) | 早期移植说明（历史） |

## 4. 专题 / 接口 / QA

| 文档 | 用途 |
|---|---|
| [DESKTOP-HANDOFF-0.9.txt](DESKTOP-HANDOFF-0.9.txt) | 桌面连接桥交接（0.9） |
| [account-sync-api-0.7.md](account-sync-api-0.7.md) | 账号与同步接口 |
| [account-deployment-0.7.md](account-deployment-0.7.md) | 账号服务部署 |
| [desktop-sync-handoff-0.7.md](desktop-sync-handoff-0.7.md) | 桌面同步交接 |
| [XFRIEND-RUNTIME-DESIGN-0.8.html](XFRIEND-RUNTIME-DESIGN-0.8.html) | 运行时设计（0.8） |
| [XFRIEND-MOBILE-SIMPLIFIED-DESIGN-2026-10-17.html](XFRIEND-MOBILE-SIMPLIFIED-DESIGN-2026-10-17.html) | 0.9 简洁移动设计说明 |
| [qa-0.11/prep.md](qa-0.11/prep.md) | 0.11 模拟器 QA 环境与准备 |
| [qa-0.11/report.md](qa-0.11/report.md) | 0.11 模拟器 QA 报告（历史） |
| [qa-0.12/](qa-0.12/) | 0.12 截图 7 张（06 为 04 底部卡片裁剪）；不放早期损坏截图 |

## 5. 阅读顺序建议

1. **新成员**：`README.md` → `XFAMILY-0.12-交付说明.txt` → `CALL-RECORDING-0.12.md` → `verification-0.12.json` → `XFRIEND-REDESIGN-0.11.md` → 交接文档。
2. **产品/设计评审**：`CALL-RECORDING-0.12.md` §1–§5 → `DELIVERY-0.12.html` → `XFRIEND-REDESIGN-0.11.md` → `design/redesign-concept-android-spec.md`。
3. **客户端开发**：`CALL-RECORDING-0.12.md` §6–§7 → `app/src/main/java/com/singu/xfriend/SalesCallRecordings.kt` / `SalesCallRecordingsScreen.kt` / `SalesViewModel.kt`（通话录音段）。
4. **测试/验收**：`verification-0.12.json` → `docs/qa-0.12/`（7 张截图，06 为裁剪图）→ `DELIVERY-0.12.html` 的已知限制。
5. **电脑版联调**：`DESKTOP-HANDOFF-0.9.txt` → `desktop-bridge/README.md` → `D:\octop\docs\mobile-runtime-v2-api.md` → `D:\xfriend-activation\SYNC-API.md`。
