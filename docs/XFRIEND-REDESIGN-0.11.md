# XFriend / XSalers 视觉重塑落地（0.11.0）

> 版本：0.11.0（versionCode 11）· 日期：2026-10-17  
> 范围：仅 Android UI / 主题层。业务 ViewModel、运行时、网络协议、账号与同步逻辑未在本次改动。  
> 实施基线：[design/redesign-concept-android-spec.md](design/redesign-concept-android-spec.md)（概念稿逐项提取）；参考截图：[design/screens/](design/screens/)（23 张，概念参考，非真机验收截图）。  
> 结论状态：**代码已落地；最终正式构建已通过（BUILD SUCCESSFUL 9m46s，含 QA 首轮修复）；模拟器 QA 回归通过（report.md §11）；真机未验收。未完成项见 §8。**

## 0. 摘要

0.11.0 把 Singu Xfriend 前端重塑概念稿 v1（"Quiet cockpit"）落地为两个 Android App 的共享设计系统：

- **一套令牌层**：`XfDesign.kt` 提供亮/暗两套 `XfColors`、5 组主色、`XfType`、`XfDimens`、`XfShapes`、`XfElevation`，页面不再直接写颜色/字号。
- **一套组件层**：`XfComponents.kt` 提供 28 个共享组件；XFriend 与 XSalers 的页面、详情、弹窗全部改用 Xf 组件。
- **一套图标语言**：`XfIcons.kt` 手工实现 60 个 24 视窗 / 1.75 stroke / round cap 的线性图标，替代混用的彩色/emoji/Material 图标（少量 Material 图标仍保留，见 §8）。
- **外观可切换**：`XfAppearance.kt` 支持「跟随系统 / 浅色 / 深色」× 5 组主色，保存在本机 SharedPreferences（`xf_appearance`），不进入账号同步；两个 App 的「我的 → 外观」均有入口。
- **壳层统一**：顶栏 `XfTopBar`、底栏 `ProductBottomBar`（Haze 毛玻璃 + 选中胶囊）、页面头 `XfPageHeader`、统一弹窗 `XfDialog`。

两 App 的底栏与信息架构**未改变**：XFriend「对话 / 工作台 / 资源 / 我的」，XSalers「今日 / 客户 / 复盘 / 专家 / 我的」。

## 1. 代码结构与兼容关系

| 文件 | 角色 | 说明 |
|---|---|---|
| `app/src/main/java/com/singu/xfriend/XfDesign.kt`（470 行） | 令牌与主题 | `XfColors`、`XfAccentColors`、`XfAccent`（5 色）、`XfThemeMode`、`XfPalettes`、`XfType`、`XfDimens`、`XfShapes`、`XfElevation`、`XfTheme`、`xf` 访问器；同步状态栏/导航栏颜色 |
| `XfComponents.kt`（853 行） | 组件层 | 28 个 `Xf*` / `Hr` 组件，详见 §5 |
| `XfIcons.kt` | 图标层 | `XfIcons` 对象，60 个 `ImageVector`（`xfVector` 手绘路径） |
| `XfAppearance.kt` | 外观偏好 | `XfAppearance.initialize(context)`、`updateMode`、`updateAccent`、`XfAppearancePanel`；SharedPreferences `xf_appearance`（键 `mode` / `accent`） |
| `ProductBrand.kt` | 品牌兼容层 | 保留 `isSales` / `name` / `icon` / `defaultAccent`；`ProductTheme` 变薄包装转调 `XfTheme` |
| `ProductAppearance.kt` | 尺寸/底栏兼容层 | `pagePadding` / `minimumTouchTarget` / `cardRadius` 转引 `XfDimens`；`ProductBottomBar` 改为 Xf 样式（80dp 内容高 + 顶部 1dp 线 + Haze，选中项为 28dp 高柔和主色胶囊） |
| `FamilyComponents.kt` | 旧组件兼容层 | `FamilyStatus` → `XfStatusChip`，`FamilySectionTitle` → `XfSectionHeader`；旧调用点无需改签名即可继承新视觉 |
| `MainActivity.kt` | 壳层初始化 | 在 `setContent` 前调用 `XfAppearance.initialize(this)`；`ProductTheme` 包裹根内容 |

> **不是替换业务层**：本次没有改 `SalesViewModel`、`AgentRuntime`、`CloudAccount`、`SalesApi`、`FriendRemoteProtocol`、`SalesCloudSyncEngine`、`SalesStore` 等（文件修改时间仍为 0.10 及更早）。UI 文件更新约 32 个（含主题/组件/页面）。

## 2. 设计令牌映射（概念稿 → 落地）

来源：概念稿 CSS `redesign-concept.html:27-78`；落地实现 `XfDesign.kt:120-245`。表中数值为**实际代码值**。

### 2.1 中性色与语义色

| 规范令牌（spec §1.2） | XfColors 字段 | Light | Dark |
|---|---|---|---|
| canvas | `canvas` | `#F6F5F1` | `#0E0F12` |
| canvas-2 | `canvas2` | `#EFEDE7` | `#131418` |
| sidebar | `sidebar` | `#EFEDE7` | `#121317` |
| surface | `surface` | `#FFFFFF` | `#181A1F` |
| surface-2 | `surface2` | `#F7F6F2` | `#1E2026` |
| surface-3 | `surface3` | `#EEECE6` | `#24262D` |
| line | `line` | `#E5E2DA` | `#282A31` |
| line-strong | `lineStrong` | `#D6D2C7` | `#373A43` |
| ink | `ink` | `#1B1C1F` | `#F1F2F4` |
| ink-2 | `ink2` | `#57585F` | `#AAAEB8` |
| ink-3 | `ink3` | `#8C8D94` | `#7B7F89` |
| ink-4 | `ink4` | `#B6B7BD` | `#565A64` |
| green | `green` / `success` | `#1F9D6B` | `#37C08A` |
| amber | `amber` / `warning` | `#C27C0E` | `#E0A33A` |
| red | `red` / `danger` | `#D5443B` | `#F06B62` |
| blue | `blue` / `info` | `#3B82F6` | `#6FA8FF` |
| overlay | `overlay` | `#4714120C`（约 28% 黑棕） | `#80000000`（50% 黑） |
| success 容器 / 文字 | `successContainer` / `successText` | `#E9F5F0` / `#17754F` | `#1C312E` / `#37C08A` |
| warning 容器 / 文字 | `warningContainer` / `warningText` | `#F7EEE0` / `#8A5600` | `#342D23` / `#E0A33A` |
| danger 容器 / 文字 | `dangerContainer` / `dangerText` | `#FBECEB` / `#C0342C` | `#3B272A` / `#F06B62` |
| info 容器 / 文字 | `infoContainer` / `infoText` | `#EBF2FE` / `#2563EB` | `#242E3E` / `#6FA8FF` |

### 2.2 主色：5 组（概念稿 4 组 + XSalers 品牌紫）

| 色板 id | 界面名 | 默认用于 | Light：accent / solid / ink / soft / line | Dark：accent / solid / ink / soft / line |
|---|---|---|---|---|
| `indigo` | 靛蓝 | **XFriend 默认** | `#4F5DF0` / `#4351E8` / `#3B47CC` / `#ECEEFE` / `#D9DEFB` | `#8B95FF` / `#4F5DF0` / `#A6AEFF` / `#218B95FF`(13%) / `#478B95FF`(28%) |
| `teal` | 青绿 | 可选 | `#0E9384` / `#0B7F73` / `#0A7568` / `#E4F6F2` / `#C6EDE5` | `#4FD1C5` / `#0B7F73` / `#7EE3D9` / `#1F4FD1C5`(12%) / `#424FD1C5`(26%) |
| `amber` | 琥珀 | 可选 | `#B26B00` / `#996000` / `#8F5600` / `#FCF1DF` / `#F4DFB9` | `#E8B05A` / `#8F5600` / `#F2C778` / `#1FE8B05A`(12%) / `#42E8B05A`(26%) |
| `rose` | 玫瑰 | 可选 | `#D94F6B` / `#C74059` / `#B33B54` / `#FDECEF` / `#F8D3DB` | `#FF8FA3` / `#C2435C` / `#FFAEBE` / `#1FFF8FA3`(12%) / `#47FF8FA3`(28%) |
| `violet` | 紫罗兰 | **XSalers 默认** | `#7055DE` / `#6247D6` / `#5A3FC9` / `#F0ECFF` / `#DED6FB` | `#C5BCFF` / `#7055DE` / `#D8D0FF` / `#24C5BCFF`(14%) / `#4CC5BCFF`(30%) |

- `XfAccent` 枚举顺序即设置页展示顺序：靛蓝 / 青绿 / 琥珀 / 玫瑰 / 紫罗兰。
- 默认值由 `ProductBrand.defaultAccent` 决定：XFriend = `INDIGO`；XSalers = `VIOLET`。
- 与 0.10 的差异：XFriend 强调色从旧品牌蓝 `#5267E8` 切换为概念稿靛蓝 `#4F5DF0`/`#4351E8`（启动图标与品牌资产未改）；XSalers 保留品牌紫但组件 solid 使用 `#6247D6`（比 `#7055DE` 略深，保证白字与按钮层级）。
- 半透明 soft/line 在代码中直接以 alpha 值参与叠加；深色下由 `MaterialTheme` 的 `surface` 合成显示。

### 2.3 排版

`XfDesign.kt:249-288`，字体族 `FontFamily.Default`（中文走系统字体），数字/命令用 `FontFamily.Monospace`（`XfType.mono`）。

| XfType 字段 | 字号 / 行高 / 字重 | 用途 | 对应规范 §2.2 |
|---|---|---|---|
| `display` | 28 / 34 / Bold | 首页问候 | Display |
| `h1` | 24 / 30 / Bold | 页面标题 | PageTitle |
| `h2` | 17 / 23 / SemiBold | 区块标题 | SectionTitle |
| `h3` | 16 / 22 / SemiBold | 卡片标题 | CardTitle |
| `title` | 16 / 22 / Medium | 列表主文 | ListTitle |
| `body` | 15 / 22 / Normal | 正文 | Body |
| `bodyStrong` | 15 / 22 / Medium | 强调正文 | — |
| `small` | 13 / 19 / Normal | 辅助正文 | BodySmall |
| `meta` | 11.5 / 16 / Normal | 元信息 | Caption |
| `label` | 12 / 16 / SemiBold | 控件标签 | Label |
| `caps` | 10.5 / 14 / SemiBold + 0.06em | 全大写小标 | Overline |
| `mono` | 12.5 / 18 / Normal / Monospace | 数字、命令、计时 | Mono |
| `button` / `buttonSmall` | 14 / 20 / SemiBold；12.5 / 18 / Medium | 按钮 | Button |
| `nav` | 11 / 14 / Medium | 底栏标签 | NavLabel |

`XfTypography` 同时把上述值映射到 Material3 的 `displayLarge/headline*/title*/body*/label*`，旧页面即使继续使用 `MaterialTheme.typography` 也会继承概念稿比例。

### 2.4 尺寸 / 形状 / 阴影

`XfDimens`（实际值，单元 dp）：

| 令牌 | 值 | 说明 |
|---|---|---|
| `pagePadding` / `pagePaddingWide` | 20 / 24 | 手机页边距 / 宽屏 |
| `cardPadding` / `cardPaddingCompact` | 16 / 14 | 卡片内边距 |
| `sectionGap` / `itemGap` / `rowGap` | 24 / 12 / 8 | 区块 / 卡片 / 行间距 |
| `heroRadius` / `cardRadius` / `panelRadius` / `dialogRadius` | 18 / 14 / 14 / 16 | 圆角 |
| `controlRadius` / `buttonRadius` / `smallRadius` / `chipRadius` / `pillRadius` | 10 / 10 / 8 / 8 / 999 | 控件 / 按钮 / 小件 / chip / 胶囊 |
| `touch` | 48 | 最小触控区 |
| `buttonHeight` / `buttonHeightSmall` | 44 / 34 | 主按钮 / 小按钮视觉高 |
| `inputHeight` | 46 | 输入框视觉高 |
| `iconButton` | 40 | 图标按钮 |
| `topBarHeight` / `bottomBarHeight` | 56 / 80 | 顶栏 / 底栏内容高（另有状态栏/导航栏 insets） |
| `avatarSmall/Medium/Large` | 32 / 40 / 48 | 头像 |
| `iconSize/Small/Large` | 20 / 17 / 24 | 图标 |

`XfShapes`：`extraSmall 6` / `small 10` / `medium 14` / `large 18` / `extraLarge 24`。  
`XfElevation`：`none 0` / `s1 0` / `s2 1` / `s3 8` / `modal 12`；Android 上阴影不作为主要层级手段，卡片用 `1px line + surface` 区分。

## 3. 图标语言

- 60 个图标全部在 `XfIcons.kt` 手工构建（`xfVector` + `PathParser`），24 视窗、1.75 stroke、round cap/join，符合规范 §3；默认 20dp，小图标 17dp，大图标 24dp。
- 导航栏使用 20dp；按钮 18dp（小按钮 16dp）；状态/行内 13–16dp。
- 与 0.10 的差异：不再依赖 `material-icons-extended` 作为页面主图标（该依赖仍在，见 §8 的少量残留）。
- 图标清单与 Android 映射建议见规范 §3.2（60 个 symbol 对应表）。

## 4. 组件清单（XfComponents，28 个）

| 组件 | 对应概念稿/规范 | 说明 |
|---|---|---|
| `XfCard` | `.panel` / `.expert-card` | Surface + 1px line + radius 14；可选 `onClick` / `elevated` |
| `XfSurface` | 面板/容器 | 无默认内边距，适合搜索框、媒体行等自管内容 |
| `XfSectionHeader` | `.panel-head` / 区块标题 | 标题 + 副标题 + 右侧 `XfLink` |
| `XfSectionLabel` | `.nav-label` / `hist-label` | 全大写小标 |
| `XfPageHeader` | `.page-head` / 页面标题 | h1 + 副文 + actions |
| `XfTopBar` | `.topbar` / `.chat-head` | 56dp、状态栏 insets、返回/品牌图标/标题/副标题/actions |
| `XfBrandTile` | `.brand-tile` | 品牌图块（30dp，内部按 16% padding） |
| `XfStarMark` | `i-brand` | 星标容器（accent-soft 底 + accent-line 边） |
| `XfButton` | `.btn` / `.btn-primary` / `.btn-secondary` / `.btn-ghost` / `.btn-danger` | Primary / Secondary / Ghost / Danger；`small` 为 34dp；loading |
| `XfIconButton` | `.icon-btn` | 40dp（最小触控 48dp），支持 active/rotation/tint |
| `XfLink` | `.link` | 文字 + 右箭头 |
| `XfDot` | `.dot` | 6dp 状态点 + 14% 柔光环 |
| `XfStatusChip` | `.chip.accent` / `.state-chip` / `.role-tag` | 6 种 tone（Neutral/Accent/Success/Warning/Danger/Info），非仅颜色区分 |
| `XfChip` | `.chip` | 通用标签（可带图标） |
| `XfAvatar` | `.avatar` / `.e-avatar` | 首字/图标头像，尺寸可配 |
| `XfIconTile` | `.e-avatar` / `.quick-item` 图标 | 图标容器（默认 36dp，accent-soft） |
| `XfListRow` | `.task-row` / `.expert-row` / `.member-row` | 标题/副标/meta/leading/trailing/分割线；默认 minHeight 56dp |
| `Hr` | `.line` 分隔线 | 1dp |
| `XfSearchField` | `.tb-search` / `.rail-search` / `.search-mini` | 42dp 高，清空按钮 |
| `XfTextField` | `.input` / `.textarea` | 46dp 高，label/supporting/error/trailing |
| `XfSegmented` | `.seg` / `.entity-switch` | 3dp 内边距，36dp 内钮，选中白底 + 1dp 阴影 |
| `XfProgressBar` | `.progress-card .bar` / `.pf-bar` | 4dp 高，可确定/不确定 |
| `XfBanner` | `create-note` / 行内提示 | 6 种 tone 的信息条（替代旧错误 Surface） |
| `XfEmptyState` | `.empty-panel` / `.dashed-card` | 52dp 图标 + 标题 + 说明 + 可选按钮 |
| `XfDialog` | `.modal` | AlertDialog 包装，统一标题/按钮/危险色 |
| `XfKeyValueRow` | `.status-row` / `.pf-summary` | 左标签 + 右值 |
| `XfSwitch` | `.switch` | 开关，主色 track，48dp 触控 |
| `XfLabeledText` | `.draft-field` | 上方 caps 标签 + 正文 |
| `XfAppearancePanel`（`XfAppearance.kt`） | `.pf-style` / accent dots | 主题模式 + 5 色板选择 + 预览 |

调用点（grep 计数，含组件定义附近的示例调用，仅作落地广度参考）：`XfButton(` 158、`XfCard(` 56、`XfIconTile(` 46、`XfStatusChip(` 42、`XfBanner(` 39、`XfDialog(` 32、`XfTextField(` 31、`XfListRow(` 26、`XfEmptyState(` 25、`XfKeyValueRow(` 25、`XfSegmented(` 15、`XfAvatar(` 14、`XfProgressBar(` 12、`XfChip(` 11、`XfSearchField(` 10、`XfSwitch(` 4。

## 5. 页面改造范围

### 5.1 XFriend（4 Tab + 详情）

| 范围 | 文件 | 0.11 变化 |
|---|---|---|
| 壳层 / 顶栏 / 底栏 | `MainActivity.kt`、`AssistantScreen.kt`、`ProductAppearance.kt` | `XfTheme` 包裹；`XfTopBar` 替换旧 TopAppBar；底栏保持 Haze，改为 Xf 颜色 + 选中胶囊；主题在 `setContent` 前初始化 |
| 对话 | `FriendChatScreen.kt`（约 47KB） | 首页/会话页全面改用 Xf 卡片、气泡、按钮、状态、空态、输入区；业务状态与停止/恢复不变 |
| 工作台 | `FriendLibraryScreen.kt` | 专家/团队/智能体卡片、分段、搜索、创建入口、空态 |
| 资源 | `FriendResourceScreen.kt` | 技能/知识/文件筛选、列表行、预览与导入入口 |
| 我的 | `FriendAccountScreen.kt` | Hero、分组设置列表、外观入口（`route("appearance")` → `XfAppearancePanel` + 通知卡） |
| 外观 | `AssistantScreen.kt` 的 `"appearance"` 分支 | 主题模式 + 5 色板 + 预览 + 系统通知设置入口 |
| 既有详情 | `PlatformScreen.kt`、`SalesMigration.kt`、`SalesMarketScreen.kt`、`FriendRemoteScreen.kt`、`FriendBlueprintScreen.kt`、`FriendEditorScreen.kt`、`SalesExpertScreen.kt`、`SalesCloudSyncScreen.kt`、`SalesRecordPage.kt`、`ScreenshotCustomerReview.kt`、`CrmOverviewScreen.kt`、`SalesCrmScreen.kt`、`SalesTaskScreen.kt`、`AccountScreen.kt`、`CompanionScreen.kt`、`CustomerEditors.kt`、`CustomerAnalysisCard.kt`、`SalesScreenshotSelection.kt` | 统一卡片/按钮/表单/弹窗/错误提示；保留原有路由、权限与数据逻辑 |
| 弹窗 | 32 处 `XfDialog`（invite/confirm/edit/delete 等） | 统一 radius 16、主/次按钮、危险色；保留原有确认语义 |
| 底部弹层 | 6 处 `ModalBottomSheet`（选择专家/执行位置/最近对话等） | 保留 M3 sheet，内部内容改用 Xf 组件（未单独封装 XfSheet） |

### 5.2 XSalers（5 Tab + 详情）

| 范围 | 文件 | 0.11 变化 |
|---|---|---|
| 壳层 / 页面头 / 底栏 | `SalesScreen.kt`（约 69KB）、`ProductAppearance.kt` | 5 Tab 保留；页面头 `XfPageHeader`；底栏 Xf 样式；「我的 → 外观」内嵌 `XfAppearancePanel` |
| 今日 | `SalesScreen.kt` page 0 | 指标卡、快捷入口、接下来要做、最近复盘、搜索、空态 |
| 客户 | `CrmOverviewScreen.kt`、`SalesCrmScreen.kt`、`CustomerEditors.kt` | 客户/待办/商机/联系计划、客户档案 5 tab、Hero/下次跟进/跟进记录 |
| 复盘 | `SalesRecordPage.kt`、`SalesScreen.kt` page 2、`SalesTaskScreen.kt` | 录音卡、摘要/原文/行动/附件、进度与错误、任务中心 |
| 专家 | `SalesExpertScreen.kt`（约 45KB）、`SalesMarketScreen.kt` | 专家/团队、AI 设计、编辑、市场、运行条件 |
| 我的 | `SalesScreen.kt` page 4 | 账户/同步/模型/连接/迁移/导入导出/会员/外观/电话保护/退出 |
| 弹窗 | 32 处 `XfDialog` | 客户编辑、确认、删除、导入导出等 |
| 截图导入 | `ScreenshotCustomerReview.kt`、`SalesScreenshotSelection.kt`、`CustomerAnalysisCard.kt` | 原截图/候选字段/预览变更/确认保存流程保留 |

## 6. 与概念稿的差异（有意为之）

| 概念稿/规范 | 0.11 落地 | 原因 |
|---|---|---|
| 桌面 244px 左侧栏 / 三栏聊天 / 280px 上下文栏 / 390+320 预览栏 | 不实现；改为 TopBar + 底栏 + 详情页 / ModalBottomSheet | 手机屏宽与现有 IA；见规范 §6.5 |
| 底栏（规范派生建议 80dp） | 最终内容高 80dp + 1dp 顶线 + 系统导航 insets，Haze 半透明（`surface` 92%） | 与规范一致；首轮 64dp 已按 QA P2 修复 |
| 4 套 accent（indigo/teal/amber/rose） | 5 套：增加 XSalers 品牌紫 `violet` | 保留 XSalers 品牌识别；概念稿无紫色 |
| XFriend 概念 default indigo | 落地 XFriend 默认 `INDIGO`；XSalers 默认 `VIOLET` | 品牌与概念稿折中 |
| 概念稿 XFriend 旧品牌蓝 #5267E8 | Xf 强调色改为 indigo #4F5DF0 / solid #4351E8；启动图标仍用 `xfriend_icon` | 按概念稿「单一主色」重构；品牌蓝色仅保留在图标资产 |
| 群聊 / 会议 / 决策档案 / 设计系统页 | 不实现（无业务） | 见规范 §6.5；不加入口 |
| 桌面 hover 行尾操作 | 改为常驻图标/更多菜单/点击态 | 触屏无 hover |
| 60 个 SVG symbol | 60 个 Compose `ImageVector` 手绘；少量 Material 图标残留 | 避免新增图标依赖；统一 stroke 风格 |
| 概念稿过渡 130–160ms | Compose 默认 ripple/180ms Crossfade 为主，无新动画库 | 性能与包体约束 |
| 明暗 + accent 实时切换 | `XfAppearance` + `XfTheme`；SharedPreferences 本机持久化 | 不引入 DataStore/账号同步；首帧读本机 |

## 7. 验证证据（真实路径）

| 证据 | 路径 | 状态 |
|---|---|---|
| 0.11 构建日志 | `D:\xfriend-suite\build-0.11-final.log`（最终构建，UTF-16；含 QA 修复）；`build-0.11-b.log`（首轮成功）；`build-0.11-a.log`（首次失败尝试） | 最终 **BUILD SUCCESSFUL in 9m 46s**；77 tasks（15 executed / 62 up-to-date）；0 个 `e:`；仅历史 warning |
| 0.11 交付 APK | `D:\build_apk\XFriend-Android-0.11.0-debug.apk`、`D:\build_apk\XSalers-Android-0.11.0-debug.apk` | 最终：**26,451,921 / 26,451,941 bytes**；SHA-256 `46693C8F2A57BBC1161F43318533235B470234B67B31F38FF2DD97EC2845457E` / `E4C4A9A7C36124F0E55E64B725272476BB2291114CCA230A3F3D2BED323CA0C6`；mtime 2026-10-18 00:18:19 / 00:17:24 |
| 模拟器 QA 环境与准备 | `docs/qa-0.11/prep.md`、`docs/qa-0.11/setup-boot.png` | 环境已就绪（1080×2400，API 35 AVD）；首轮 QA fixture + 修复后 QA fixture（`build-0.11-qa-final.log`，`.artifacts/0.11-qa/`，SHA 见 verification） |
| 模拟器 QA 报告 | `docs/qa-0.11/report.md` | 首轮 P0=1/P1=1/P2=1；§11 最终回归**通过**（5 tab、分段细边框、底栏 80dp、0 FATAL/ANR） |
| 模拟器 QA 截图 | `docs/qa-0.11/*.png` | 首轮 + 9 张回归截图（`*-reg.png`、`xs-appearance-reg-light/dark.png`）；交接文档按实际文件扫描嵌入 |
| 0.11 单元测试 | `test-0.11.log`、`app/build/test-results/` | **通过**：两个 flavor 合计 574 项，0 failures / 0 errors；BUILD SUCCESSFUL in 5m 11s |
| 0.11 lint | 未执行 | 可用 `build.ps1` 跑完整 test+lint 链 |
| 设计规范 | `docs/design/redesign-concept-android-spec.md` | 已完成 |
| 概念参考图 | `docs/design/screens/concept-*.png`（23 张） | 已完成（桌面 Chromium 渲染，非真机） |

> 证据原则：未执行的项写“未执行/未验证”，不把设计稿或旧版本截图当作 0.11 验收证据。

## 8. 未做项与已知限制

1. **未做真机验收**：0.11 只做编译与模拟器 QA（首轮 + 回归均通过），未在真实手机/平板验收；字体缩放、深色、Haze 在不同 API 上的表现以模拟器与后续真机为准。
2. **lint 未执行**；单元测试已通过（两个 flavor 合计 574 项、0 failures/errors），但未执行 `build.ps1` 的完整 test+lint 链。
3. **首轮 QA 问题已闭环**：P0=1 XSalers 底栏（IME 判断）、P1=1 分段控件灰边、P2=1 底栏高度；最终构建修复并由 report.md §11 回归验证通过。
3. **少量 Material 残留**：部分组件仍用 `MaterialTheme.typography`（如「电脑与平台」「云端执行」的分支），Material 图标仍有约 17 处（`Icons.Outlined.Visibility/CloudOff/Smartphone` 等）；不影响主路径，但未 100% 统一到 Xf。
4. **对话框之外的部分 BottomSheet 仍为 M3 原生**（6 处），仅内容使用 Xf 组件；未封装 `XfSheet`。
5. **外观偏好本机保存**：不进入账号同步；换机/重装恢复默认（XFriend 靛蓝 / XSalers 紫罗兰，跟随系统）。
6. **群聊/会议/决策档案/专家市场桌面结构未实现**（仅保留真实业务入口）；与概念稿的差异见 §6。
7. **大字号、TalkBack、对比度**：设计规范给出了比值与要求，但 0.11 未在设备上逐项测量；不能声称无障碍验收通过。
8. **概念稿 4 色 vs 落地 5 色**：设置页会显示 5 个色板，属于有意的品牌扩展。
9. **XFriend 旧品牌蓝 `#5267E8` 不再作为 UI 强调色**：若产品要求保留旧蓝作为默认，需要将 `ProductBrand.defaultAccent` 改回/新增色板并同步更新本文与规范。

## 9. 后续建议（沿用规范 §6.4）

- P1：真机验收（浅/深、5 色、字号 1.0/1.3/1.5/2.0、底栏/Haze、键盘避让）；补齐 0.11 单元测试与 lint；把残留 Material 图标/typography 清零。
- P1：平板/横屏双栏（工作台列表+详情、客户列表+档案），不实现桌面三栏。
- P2：登录/注册壳层换肤、市场/技能/知识页深化、报告导出、无障碍键盘/TV 焦点。
- 真机验收、lint、无障碍与 QA 最终回归完成后，把本文 §7 的状态与截图更新为最终证据，并同步 `README.md`、`XFAMILY-0.11-交付说明.txt`、`DELIVERY-0.11.html`、`verification-0.11.json` 与交接文档。
