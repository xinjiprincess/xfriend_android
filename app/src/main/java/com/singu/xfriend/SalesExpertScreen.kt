package com.singu.xfriend

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 默认输出格式：创建新草稿、模板与“让 AI 继续优化”共用，保持既有文案。 */
private const val EXPERT_DEFAULT_FORMAT = "一句话结论、原文证据、待确认问题、下一步行动。使用中文 Markdown。"

/** 标签最多展示数量，避免卡片被 chip 撑高。 */
private const val EXPERT_TAG_LIMIT = 3

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SalesExpertScreen(
    friend: FriendViewModel,
    vm: SalesViewModel,
    onLibrary: () -> Unit,
    initialSection: String = "home",
    onBack: () -> Unit,
) {
    val colors = xf
    val sales = ProductBrand.isSales
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var requirement by rememberSaveable { mutableStateOf("") }
    var revision by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf(EXPERT_DEFAULT_FORMAT) }
    var existingId by rememberSaveable { mutableStateOf("") }
    var sample by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var operation by remember { mutableStateOf("") }
    var testedDraft by remember { mutableStateOf<ExpertDraft?>(null) }
    var testedSample by remember { mutableStateOf("") }
    var testReady by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var leaveWorking by remember { mutableStateOf(false) }
    var confirmSave by remember { mutableStateOf(false) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Expert?>(null) }
    val draft = ExpertDraft(name.trim(), prompt.trim(), format.trim())
    val valid = name.isNotBlank() && name.length <= 100 && prompt.isNotBlank() && prompt.length <= 3200 && format.isNotBlank() && format.length <= 1000
    val blocked = vm.busy || vm.recording || friend.busy
    val tested = testReady && testedDraft == draft && testedSample == sample && vm.designerTest.isNotBlank()
    val store = friend.store
    // 真实运行条件：试用服务可用，或当前模型配置齐全（与专家库一致，不伪造在线状态）。
    val configured = vm.data?.let { data ->
        if (data.useTrial) data.trialUrl.isNotBlank()
        else data.profiles.any { it.id == data.active && it.key.isNotBlank() && it.model.isNotBlank() }
    } == true

    fun openDraft(value: ExpertDraft, id: String = "") {
        name = value.name; prompt = value.prompt; format = value.outputFormat
        existingId = id; sample = ""; testReady = false; step = 0; dirty = false
        section = "editor"
    }

    fun startFreshDraft() {
        existingId = ""; requirement = ""; revision = ""
        openDraft(ExpertDraft("", "", EXPERT_DEFAULT_FORMAT))
    }

    fun startDesigner() {
        existingId = ""; requirement = ""; revision = ""
        vm.designerDraft = null
        dirty = false
        section = "designer"
    }

    fun back() {
        if (blocked && operation == "save") return
        if (blocked && operation in listOf("generate", "test")) { leaveWorking = true; return }
        if (section == "home") onBack()
        else if (section == "editor" && step == 1) step = 0
        else if (dirty && section in listOf("editor", "designer")) discard = true
        else section = "home"
    }

    BackHandler { back() }
    // Only consume results of operations started by this screen; an old VM draft
    // must never replace a fresh manual draft or an expert being edited.
    LaunchedEffect(vm.busy, friend.busy, vm.designerDraft, vm.designerTest) {
        if (!vm.busy && !friend.busy && operation.isNotBlank()) {
            val finished = operation
            operation = ""
            if (vm.error == null && friend.error == null && vm.notice != "已取消") when (finished) {
                "generate" -> vm.designerDraft?.let { openDraft(it, existingId); dirty = true }
                "test" -> testReady = vm.designerTest.isNotBlank()
                "save" -> { dirty = false; section = "saved" }
            }
        }
    }

    val title = when (section) {
        "templates" -> "专家模板"
        "designer" -> "AI 专家设计师"
        "editor" -> if (step == 0) "编辑专家" else "测试与保存"
        "saved" -> "专家已就位"
        else -> if (sales) "销售专家" else "专家"
    }
    val subtitle = when (section) {
        "templates" -> "从成熟的方法开始，调整成你的风格"
        "designer" -> "描述工作目标，让 AI 起草专业职责"
        "editor" -> "明确职责、输出与边界，让判断更可靠"
        "saved" -> if (sales) "现在可以独立分析，也可以加入销售团队" else "现在可以在专家页面使用，也可以加入团队"
        else -> if (sales) "为每个销售场景，配置合适的专业判断" else "为日常工作与学习，配置合适的专家职责"
    }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(XfDimens.pagePadding, 8.dp, XfDimens.pagePadding, 40.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            Column {
                SalesBackButton(
                    label = if (section == "home") {
                        if (sales) "返回分析工作空间" else "返回专家页面"
                    } else if (section == "editor" && step == 1) "返回编辑"
                    else if (sales) "返回销售专家" else "返回专家",
                    onClick = { back() },
                )
                Spacer(Modifier.height(4.dp))
                XfPageHeader(
                    title = title,
                    subtitle = subtitle,
                    actions = {
                        if (section == "home") {
                            Spacer(Modifier.width(8.dp))
                            XfButton(
                                text = "新建",
                                onClick = { startFreshDraft() },
                                icon = XfIcons.Plus,
                                small = true,
                                modifier = Modifier.heightIn(min = XfDimens.touch),
                            )
                        }
                    },
                )
            }
        }
        when (section) {
            "home" -> {
                item {
                    XfCard(onClick = { startDesigner() }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            XfIconTile(XfIcons.Sparkles, size = 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("AI 帮我创建专家", style = XfType.h3, color = colors.ink)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "描述工作目标，让 AI 起草职责、输出与边界",
                                    style = XfType.meta,
                                    color = colors.ink3,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(XfIcons.ChevRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.ink4)
                        }
                    }
                }
                item { XfSectionHeader("创建与配置") }
                item {
                    XfCard(padding = 8.dp) {
                        ExpertActionRow(
                            icon = XfIcons.Edit,
                            title = "自己创建",
                            subtitle = "直接编写专家名称、职责与输出格式",
                            onClick = { startFreshDraft() },
                        )
                        Hr()
                        ExpertActionRow(
                            icon = XfIcons.Book,
                            title = if (sales) "专家模板" else "销售场景模板",
                            subtitle = "需求洞察、谈判复盘、商机与风险",
                            onClick = { section = "templates" },
                        )
                        Hr()
                        ExpertActionRow(
                            icon = XfIcons.Layers,
                            title = "管理团队 / 技能与知识",
                            subtitle = if (sales) "为专家配置协作成员与销售资料" else "为专家配置协作成员与参考资料",
                            onClick = onLibrary,
                        )
                    }
                }
                val experts = store?.experts.orEmpty()
                item { XfSectionHeader("我的专家", subtitle = "${experts.size} 位") }
                if (experts.isEmpty()) {
                    item {
                        XfEmptyState(
                            icon = XfIcons.Sparkles,
                            title = "还没有专家",
                            body = "自己创建、使用模板，或让 AI 起草一位专家。",
                            action = "AI 帮我创建",
                            onAction = { startDesigner() },
                        )
                    }
                }
                items(experts, key = { "home-expert-${it.id}" }) { expert ->
                    SalesExpertCard(
                        expert = expert,
                        tags = skillNames(expert.skills, store),
                        configured = configured,
                        selected = sales && vm.selectedExpert == expert.id && !vm.teamMode,
                        enabled = !blocked,
                        primaryLabel = if (!sales) "前往专家页面" else if (vm.selectedExpert == expert.id && !vm.teamMode) "已选用于分析" else "用于分析",
                        onUse = {
                            if (sales) { vm.selectedExpert = expert.id; vm.teamMode = false } else onLibrary()
                        },
                        onEdit = {
                            val marker = "\n\n输出格式要求：\n"
                            val parts = expert.prompt.split(marker, limit = 2)
                            requirement = ""; revision = ""
                            openDraft(
                                ExpertDraft(
                                    expert.name,
                                    parts[0],
                                    parts.getOrElse(1) { EXPERT_DEFAULT_FORMAT },
                                ),
                                expert.id,
                            )
                        },
                        onDetail = { detail = expert },
                    )
                }
                val teams = store?.teams.orEmpty()
                if (teams.isNotEmpty()) {
                    item { XfSectionHeader("我的团队", subtitle = "${teams.size} 个") }
                    items(teams, key = { "home-team-${it.id}" }) { team ->
                        SalesTeamCard(
                            team = team,
                            memberNames = teamMemberNames(team, store),
                            configured = configured,
                            selected = vm.teamMode && vm.selectedTeam == team.id,
                            enabled = !blocked,
                            primaryLabel = if (vm.teamMode && vm.selectedTeam == team.id) "已选择" else "使用团队分析",
                            onUse = { vm.selectedTeam = team.id; vm.teamMode = true },
                        )
                    }
                }
            }
            "templates" -> {
                items(salesExperts, key = { "template-${it.id}" }) { template ->
                    XfCard(
                        onClick = {
                            requirement = ""; revision = ""
                            openDraft(
                                ExpertDraft(
                                    template.name,
                                    template.prompt,
                                    "一句话结论、关键证据、待确认问题、建议行动。使用中文 Markdown。",
                                ),
                            )
                            dirty = true
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            XfIconTile(XfIcons.Book, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(template.name, style = XfType.h3, color = colors.ink)
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    template.prompt,
                                    style = XfType.small,
                                    color = colors.ink3,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(XfIcons.ChevRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.ink4)
                        }
                    }
                }
            }
            "designer" -> {
                item {
                    XfBanner(
                        text = "01 需求 → 02 编辑 → 03 测试 → 04 保存",
                        tone = XfTone.Accent,
                        icon = XfIcons.Sparkles,
                    )
                }
                item {
                    XfCard {
                        Text("希望专家帮你解决什么？", style = XfType.h3, color = colors.ink)
                        Spacer(Modifier.height(10.dp))
                        XfTextField(
                            value = requirement,
                            onValueChange = { requirement = it.take(4000); dirty = true },
                            label = if (sales) "行业、销售场景与工作目标" else "工作场景与目标",
                            placeholder = if (sales) {
                                "例如：为企业软件销售设计报价复盘专家，识别价格异议，给出有证据的回应建议。"
                            } else {
                                "例如：设计一位学习规划专家，根据目标和可用时间制定计划，并标记需要确认的信息。"
                            },
                            singleLine = false,
                            minLines = 5,
                            supporting = "${requirement.length} / 4000",
                            enabled = !blocked,
                        )
                        Spacer(Modifier.height(12.dp))
                        XfTextField(
                            value = revision,
                            onValueChange = { revision = it.take(2000); dirty = true },
                            label = "补充或修改要求（可选）",
                            singleLine = false,
                            minLines = 2,
                            enabled = !blocked,
                        )
                    }
                }
                item { SalesDesignerUsage(vm, friend) }
                item {
                    XfButton(
                        text = if (vm.busy && operation == "generate") "正在生成草稿…" else "生成专家草稿",
                        onClick = { operation = "generate"; vm.designExpert(requirement.trim(), revision.trim(), friend.store) },
                        enabled = !blocked && requirement.isNotBlank(),
                        icon = XfIcons.Sparkles,
                        loading = vm.busy && operation == "generate",
                        modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
                    )
                }
                item {
                    Text(
                        "草稿生成后可逐项编辑。只有点击保存，专家才会进入你的专家库。",
                        style = XfType.meta,
                        color = colors.ink3,
                    )
                }
            }
            "editor" -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        XfSegmented(
                            options = listOf("编辑职责", "测试与保存"),
                            selectedIndex = step,
                            onSelect = { if (it == 0 || valid) step = it },
                        )
                        if (existingId.isNotBlank()) XfStatusChip("编辑已有专家", tone = XfTone.Neutral)
                    }
                }
                if (step == 0) {
                    item {
                        XfTextField(
                            value = name,
                            onValueChange = { name = it.take(100); dirty = true },
                            label = "专家名称",
                            supporting = "${name.length} / 100",
                            enabled = !blocked,
                        )
                    }
                    item {
                        XfTextField(
                            value = prompt,
                            onValueChange = { prompt = it.take(3200); dirty = true },
                            label = "专家职责与边界",
                            singleLine = false,
                            minLines = 8,
                            supporting = "${prompt.length} / 3200 · 明确依据、方法与不能编造的信息",
                            enabled = !blocked,
                        )
                    }
                    item {
                        XfTextField(
                            value = format,
                            onValueChange = { format = it.take(1000); dirty = true },
                            label = "输出格式",
                            singleLine = false,
                            minLines = 3,
                            supporting = "${format.length} / 1000",
                            enabled = !blocked,
                        )
                    }
                    item {
                        XfButton(
                            text = "让 AI 继续优化",
                            onClick = {
                                vm.designerDraft = draft
                                if (requirement.isBlank()) {
                                    requirement = if (sales) {
                                        "根据现有草稿优化销售专家，保留明确的职责、判断依据与输出格式。"
                                    } else {
                                        "根据现有草稿优化专家，保留明确的职责、判断依据与输出格式。"
                                    }
                                }
                                revision = "基于当前草稿优化，可在此补充修改要求。"
                                section = "designer"
                            },
                            enabled = valid && !blocked,
                            kind = XfButtonKind.Ghost,
                            icon = XfIcons.Sparkles,
                            small = true,
                            modifier = Modifier.heightIn(min = XfDimens.touch),
                        )
                    }
                    item {
                        XfButton(
                            text = "下一步 · 测试与保存",
                            onClick = { step = 1 },
                            enabled = valid && !blocked,
                            modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
                        )
                    }
                } else {
                    item {
                        XfCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                XfIconTile(XfIcons.Sparkles, size = 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = XfType.h3, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        format,
                                        style = XfType.meta,
                                        color = colors.ink3,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        XfTextField(
                            value = sample,
                            onValueChange = { sample = it.take(12000) },
                            label = "测试样例",
                            placeholder = if (sales) {
                                "粘贴一段真实或模拟的客户沟通，检查专家的判断与输出。"
                            } else {
                                "输入一段任务或参考资料，检查专家的回答与输出。"
                            },
                            singleLine = false,
                            minLines = 5,
                            supporting = "${sample.length} / 12000 · 仅发送当前草稿与样例进行测试",
                            enabled = !blocked,
                        )
                    }
                    item { SalesDesignerUsage(vm, friend) }
                    item {
                        XfButton(
                            text = if (vm.busy && operation == "test") "正在测试…" else "运行一次测试",
                            onClick = {
                                testedDraft = draft; testedSample = sample; testReady = false
                                operation = "test"
                                vm.testExpert(draft, sample, friend.store)
                            },
                            enabled = valid && sample.isNotBlank() && !blocked,
                            kind = XfButtonKind.Secondary,
                            icon = XfIcons.Play,
                            loading = vm.busy && operation == "test",
                            modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
                        )
                    }
                    if (tested) {
                        item {
                            XfCard {
                                XfStatusChip("测试预览", tone = XfTone.Accent)
                                Spacer(Modifier.height(10.dp))
                                SelectionContainer {
                                    Text(vm.designerTest, style = XfType.small, color = colors.ink2)
                                }
                            }
                        }
                    } else if (testReady) {
                        item {
                            XfBanner(
                                text = "草稿或样例已修改，请重新测试以预览最新效果。",
                                tone = XfTone.Warning,
                                icon = XfIcons.Refresh,
                            )
                        }
                    }
                    item {
                        Text(
                            if (sales) "测试结果供你评估，不会创建商机或跟进。保存专家不消耗 AI 试用次数。"
                            else "测试仅返回文本供你评估，不调用工具或操作资源。保存专家不会调用模型。",
                            style = XfType.meta,
                            color = colors.ink3,
                        )
                    }
                    item {
                        XfButton(
                            text = if (existingId.isBlank()) "保存到专家库" else "保存专家修改",
                            onClick = { confirmSave = true },
                            enabled = valid && !blocked,
                            modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
                        )
                    }
                }
            }
            "saved" -> {
                item {
                    XfCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            XfIconTile(XfIcons.CheckCircle, size = 44.dp, tone = XfTone.Success)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = XfType.h3, color = colors.ink)
                                Spacer(Modifier.height(2.dp))
                                Text("已保存到当前账号的专家库", style = XfType.meta, color = colors.ink3)
                            }
                        }
                    }
                }
                item {
                    XfButton(
                        text = if (sales) "返回分析，使用这位专家" else "前往专家页面",
                        onClick = { if (sales) { vm.teamMode = false; onBack() } else onLibrary() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
                    )
                }
                item {
                    XfButton(
                        text = "管理团队 / 技能与知识",
                        onClick = onLibrary,
                        kind = XfButtonKind.Secondary,
                        modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
                    )
                }
                item {
                    XfButton(
                        text = if (sales) "继续管理销售专家" else "继续管理专家",
                        onClick = { section = "home" },
                        kind = XfButtonKind.Ghost,
                        modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
                    )
                }
            }
        }
    }

    detail?.let { expert ->
        SalesExpertDetailDialog(
            expert = expert,
            store = store,
            onDismiss = { detail = null },
            onManage = { detail = null; onLibrary() },
        )
    }
    if (discard) {
        XfDialog(
            title = "离开当前草稿？",
            onDismiss = { discard = false },
            confirmLabel = "离开",
            onConfirm = { discard = false; dirty = false; section = "home" },
            dismissLabel = "继续编辑",
        ) {
            Text(
                "尚未保存的编辑不会进入专家库。重新创建或打开其他专家会替换当前草稿。",
                style = XfType.small,
                color = colors.ink2,
            )
        }
    }
    if (leaveWorking) {
        XfDialog(
            title = "取消当前任务并返回？",
            onDismiss = { leaveWorking = false },
            confirmLabel = "取消任务并返回",
            onConfirm = {
                vm.cancel(); operation = ""; leaveWorking = false
                section = "editor"; step = 0
            },
            dismissLabel = "继续等待",
        ) {
            Text(
                if (sales) "生成或测试尚未完成。取消后保留当前专家草稿；已消耗的试用次数以服务端结果为准。"
                else "生成或测试尚未完成。取消后保留当前专家草稿；已产生的模型费用以服务商结果为准。",
                style = XfType.small,
                color = colors.ink2,
            )
        }
    }
    if (confirmSave) {
        XfDialog(
            title = if (existingId.isBlank()) (if (sales) "保存销售专家？" else "保存专家？") else (if (sales) "更新销售专家？" else "更新专家？"),
            onDismiss = { confirmSave = false },
            confirmLabel = "确认保存",
            onConfirm = {
                confirmSave = false
                operation = "save"
                vm.saveDesignedExpert(friend, draft, existingId)
            },
            confirmEnabled = !blocked,
            dismissLabel = "返回检查",
        ) {
            Text(draft.name, style = XfType.bodyStrong, color = colors.ink)
            Spacer(Modifier.height(6.dp))
            Text(
                if (tested) "已预览当前草稿的测试结果。" else "当前草稿尚未完成测试，可先返回测试，也可直接保存。",
                style = XfType.small,
                color = colors.ink2,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (sales) "保存后可用于销售分析与团队协作。" else "保存后可用于对话与团队协作。",
                style = XfType.small,
                color = colors.ink2,
            )
        }
    }
}

/** 旧的“主菜单卡片”行：图标 tile + 标题/说明 + 右箭头，保留 48dp 触控。 */
@Composable
private fun ExpertActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    XfListRow(
        title = title,
        subtitle = subtitle,
        leading = { XfIconTile(icon, size = 38.dp) },
        trailing = {
            Icon(
                XfIcons.ChevRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = xf.ink4,
            )
        },
        onClick = onClick,
    )
}

/** 专家卡：40dp 圆角方块头像、状态 chip、两行描述、标签、底部元信息与溢出菜单。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SalesExpertCard(
    expert: Expert,
    tags: List<String>,
    configured: Boolean,
    selected: Boolean,
    enabled: Boolean,
    primaryLabel: String,
    onUse: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDetail: (() -> Unit)? = null,
    cardOnClick: (() -> Unit)? = null,
) {
    val colors = xf
    var menuOpen by remember { mutableStateOf(false) }
    XfCard(onClick = cardOnClick) {
        Row(verticalAlignment = Alignment.Top) {
            XfAvatar(text = expert.name, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    expert.name,
                    style = XfType.h3,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FamilyStatus(
                        label = if (configured) "此手机 · 模型 API" else "待配置模型",
                        icon = if (configured) Icons.Outlined.PhoneAndroid else null,
                        tone = if (configured) "neutral" else "warning",
                    )
                    if (selected) XfStatusChip("当前用于分析", tone = XfTone.Accent)
                }
            }
            if (onEdit != null || onDetail != null) {
                Box {
                    XfIconButton(
                        icon = XfIcons.More,
                        contentDescription = "更多操作",
                        onClick = { menuOpen = true },
                        size = XfDimens.touch,
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("用于分析", style = XfType.small) },
                            enabled = enabled,
                            onClick = { menuOpen = false; onUse() },
                        )
                        if (onEdit != null) DropdownMenuItem(
                            text = { Text("编辑与测试", style = XfType.small) },
                            enabled = enabled,
                            onClick = { menuOpen = false; onEdit() },
                        )
                        if (onDetail != null) DropdownMenuItem(
                            text = { Text("查看资料", style = XfType.small) },
                            onClick = { menuOpen = false; onDetail() },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            expert.prompt.lineSequence().filter { it.isNotBlank() }.joinToString(" "),
            style = XfType.small,
            color = colors.ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.take(EXPERT_TAG_LIMIT).forEach { tag -> XfChip(tag) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Hr()
        Spacer(Modifier.height(8.dp))
        Text(
            "${expert.skills.size} 项技能 · ${expert.knowledge.size} 份知识",
            style = XfType.meta,
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            if (onEdit != null) {
                XfButton(
                    text = "编辑与测试",
                    onClick = onEdit,
                    enabled = enabled,
                    kind = XfButtonKind.Secondary,
                    small = true,
                    modifier = Modifier.heightIn(min = XfDimens.touch),
                )
            }
            XfButton(
                text = primaryLabel,
                onClick = onUse,
                enabled = enabled,
                kind = if (selected) XfButtonKind.Secondary else XfButtonKind.Primary,
                small = true,
                modifier = Modifier.heightIn(min = XfDimens.touch),
            )
        }
    }
}

/** 团队卡：成员堆叠图标、成员数/顺序协作、成员名单与使用按钮。 */
@Composable
private fun SalesTeamCard(
    team: Team,
    memberNames: List<String>,
    configured: Boolean,
    selected: Boolean,
    enabled: Boolean,
    primaryLabel: String,
    onUse: () -> Unit,
) {
    val colors = xf
    XfCard {
        Row(verticalAlignment = Alignment.Top) {
            XfAvatar(icon = XfIcons.Users, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    team.name,
                    style = XfType.h3,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    XfStatusChip("${team.members.size} 位专家 · 顺序协作", tone = XfTone.Neutral)
                    if (selected) XfStatusChip("当前团队", tone = XfTone.Accent)
                }
            }
        }
        if (memberNames.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                memberNames.joinToString(" · "),
                style = XfType.small,
                color = colors.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        Hr()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FamilyStatus(
                label = if (configured) "此手机 · 模型 API" else "待配置模型",
                icon = if (configured) Icons.Outlined.PhoneAndroid else null,
                tone = if (configured) "neutral" else "warning",
            )
            Spacer(Modifier.weight(1f))
            XfButton(
                text = primaryLabel,
                onClick = onUse,
                enabled = enabled,
                kind = if (selected) XfButtonKind.Secondary else XfButtonKind.Primary,
                small = true,
                modifier = Modifier.heightIn(min = XfDimens.touch),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SalesExpertDetailDialog(
    expert: Expert,
    store: Store?,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    val colors = xf
    val skills = skillNames(expert.skills, store)
    val knowledge = knowledgeNames(expert.knowledge, store)
    XfDialog(
        title = expert.name,
        onDismiss = onDismiss,
        confirmLabel = "管理专家",
        onConfirm = { onDismiss(); onManage() },
        dismissLabel = "关闭",
    ) {
        LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SelectionContainer {
                    Text(expert.prompt, style = XfType.small, color = colors.ink2)
                }
            }
            item {
                Text(
                    "${expert.skills.size} 项技能 · ${expert.knowledge.size} 份知识",
                    style = XfType.meta,
                    color = colors.ink3,
                )
            }
            if (skills.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        skills.forEach { XfChip(it) }
                    }
                }
            }
            if (knowledge.isNotEmpty()) {
                item { XfLabeledText("知识", knowledge.joinToString("、"), style = XfType.small) }
            }
        }
    }
}

/** 生成/测试费用提示（原有文案与判断逻辑不变，只换视觉）。 */
@Composable
private fun SalesDesignerUsage(vm: SalesViewModel, friend: FriendViewModel) {
    val text = if (!ProductBrand.isSales) {
        val store = friend.store
        if (store == null || store.baseUrl.isBlank() || store.model.isBlank() || store.apiKey.isBlank()) {
            "请先在模型设置中配置当前模型；设计师不会自动使用试用服务。"
        } else {
            "使用 Xfriend 当前模型：${store.model}。生成与测试会发送当前需求或样例，并按服务商规则计费。"
        }
    } else if (vm.data?.useTrial == true) {
        "AI 生成与每次测试各消耗一次试用次数。${vm.quota?.let { "当前剩余 $it 次。" }.orEmpty()}请确认后操作。"
    } else {
        "使用你配置的模型。生成与测试会发送当前需求或样例，并按服务商规则计费。"
    }
    XfBanner(text = text, tone = XfTone.Accent, icon = XfIcons.Sparkles)
}

/** 兼容入口：旧的返回文字按钮，保留签名供 CRM/复盘等页面复用。 */
@Composable
internal fun SalesBackButton(label: String, onClick: () -> Unit) {
    val colors = xf
    Row(
        Modifier
            .clip(RoundedCornerShape(XfDimens.smallRadius))
            .clickable(onClick = onClick)
            .heightIn(min = XfDimens.touch)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            XfIcons.ChevRight,
            contentDescription = null,
            modifier = Modifier.size(16.dp).rotate(180f),
            tint = colors.ink3,
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = XfType.label, color = colors.ink2)
    }
}

/** The overview only claims capabilities provided by the native sales runtime. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SalesExpertLibrary(store: Store, vm: SalesViewModel, enabled: Boolean, onUse: () -> Unit, onManage: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var detail by remember { mutableStateOf<Expert?>(null) }
    val filters = listOf("全部", "专家", "团队")
    val configured = vm.data?.let { data ->
        if (data.useTrial) data.trialUrl.isNotBlank()
        else data.profiles.any { it.id == data.active && it.key.isNotBlank() && it.model.isNotBlank() }
    } == true
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        XfSearchField(value = search, onValueChange = { search = it }, placeholder = "找专家或团队")
        XfSegmented(
            options = filters,
            selectedIndex = filters.indexOf(filter).coerceAtLeast(0),
            onSelect = { filter = filters[it] },
        )
        val teams = store.teams.filter { it.name.contains(search, true) }
        val experts = store.experts.filter { (it.name + it.prompt).contains(search, true) }
        if (filter != "专家") teams.forEach { team ->
            SalesTeamCard(
                team = team,
                memberNames = teamMemberNames(team, store),
                configured = configured,
                selected = vm.teamMode && vm.selectedTeam == team.id,
                enabled = enabled,
                primaryLabel = if (vm.teamMode && vm.selectedTeam == team.id) "已选择" else "使用",
                onUse = {
                    vm.selectedTeam = team.id
                    vm.teamMode = true
                    onUse()
                },
            )
        }
        if (filter != "团队") experts.forEach { expert ->
            SalesExpertCard(
                expert = expert,
                tags = skillNames(expert.skills, store),
                configured = configured,
                selected = !vm.teamMode && vm.selectedExpert == expert.id,
                enabled = enabled,
                primaryLabel = if (!vm.teamMode && vm.selectedExpert == expert.id) "已选择" else "使用",
                onUse = {
                    vm.selectedExpert = expert.id
                    vm.teamMode = false
                    onUse()
                },
                cardOnClick = { detail = expert },
            )
        }
        if ((filter == "专家" || teams.isEmpty()) && (filter == "团队" || experts.isEmpty())) {
            XfEmptyState(
                icon = XfIcons.Search,
                title = "暂无匹配资源",
                body = "调整搜索，或创建与导入专家。",
            )
        }
    }
    detail?.let { expert ->
        SalesExpertDetailDialog(
            expert = expert,
            store = store,
            onDismiss = { detail = null },
            onManage = { detail = null; onManage() },
        )
    }
}

private fun skillNames(ids: List<String>, store: Store?): List<String> =
    ids.mapNotNull { id -> store?.skills?.find { it.id == id }?.name }.filter { it.isNotBlank() }

private fun knowledgeNames(ids: List<String>, store: Store?): List<String> =
    ids.mapNotNull { id -> store?.knowledge?.find { it.id == id }?.name }.filter { it.isNotBlank() }

private fun teamMemberNames(team: Team, store: Store?): List<String> =
    team.members.mapNotNull { id -> store?.experts?.find { it.id == id }?.name }.filter { it.isNotBlank() }
