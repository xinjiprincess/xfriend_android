package com.singu.xfriend

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable internal fun FriendLibraryPage(s: Store, blocked: Boolean, onChoose: (String,Boolean)->Unit, onEdit:(String,String)->Unit, onDelete:(String,String)->Unit, onRoute:(String)->Unit, onImport:()->Unit) {
    val colors = xf
    var tab by rememberSaveable { mutableStateOf("专家") }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    val availability = rememberFriendAvailability(s)
    val team = tab == "团队"
    val createKind = when(tab) { "团队" -> "team"; "智能体" -> "agent"; else -> "expert" }
    val entries = if (team) s.teams.map { Triple(it.id, it.name, "${it.members.size} 位成员 · 顺序协作") }
        else s.experts.filter { if (tab == "智能体") isWorkbenchAgent(it.id) else !isWorkbenchAgent(it.id) }
            .map { Triple(it.id, it.name, it.prompt.lineSequence().firstOrNull { line -> line.isNotBlank() }.orEmpty()) }
    val filtered = entries.filter {
        val state = availability[if (team) "team:${it.first}" else it.first]
        (query.isBlank() || it.second.contains(query, true) || it.third.contains(query, true)) &&
            (filter == "全部" || (filter == "手机可运行" && state?.ready == true) || (filter == "需要电脑" && state?.remote == true))
    }
    val tabs = listOf("智能体", "专家", "团队")
    val filters = listOf("全部", "手机可运行", "需要电脑")
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            XfPageHeader(
                title = "工作台",
                subtitle = "选择、创建并编排你的 AI 团队。每个专家都有独立的角色与工作区。",
                actions = {
                    XfButton(
                        text = "导入",
                        onClick = onImport,
                        enabled = !blocked,
                        kind = XfButtonKind.Secondary,
                        icon = XfIcons.Export,
                        small = true,
                    )
                },
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XfButton(
                    text = "创建${if (team) "团队" else "专家"}",
                    onClick = { onEdit(createKind, "") },
                    enabled = !blocked,
                    icon = XfIcons.Plus,
                )
                XfButton(
                    text = "AI 帮我创建",
                    onClick = { onRoute("designer") },
                    kind = XfButtonKind.Secondary,
                    icon = XfIcons.Sparkles,
                )
            }
        }
        item {
            XfCard(onClick = { onRoute("local_rooms") }) {
                XfSectionHeader("本机专家群聊", subtitle = "选 1–8 位专家，在此手机共享群消息并协作。", action = "打开", onAction = { onRoute("local_rooms") })
            }
        }
        item {
            XfSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索助手、专家或团队",
            )
        }
        item {
            XfSegmented(
                options = tabs,
                selectedIndex = tabs.indexOf(tab).coerceAtLeast(0),
                onSelect = { tab = tabs[it] },
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters, key = { it }) { label ->
                    XfChip(
                        text = label,
                        selected = filter == label,
                        onClick = { filter = label },
                    )
                }
            }
        }
        if (filtered.isEmpty()) item {
            EmptyHint(
                if (query.isBlank() && filter == "全部") "还没有${if (team) "团队" else "专家"}" else "没有符合条件的伙伴",
                "可以调整筛选、创建${if (team) "团队" else "专家"}，或导入电脑配置包。",
            )
        }
        items(filtered, key = { it.first }) { entry ->
            val state = availability[if(team) "team:${entry.first}" else entry.first] ?: FriendAvailability()
            FriendExpertTile(entry, team, blocked, state, s, Modifier.fillMaxWidth(), onChoose, onEdit, onDelete, onRoute)
        }
        item {
            XfCard(onClick = { onRoute("designer") }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    XfIconTile(XfIcons.Sparkles, size = XfDimens.avatarMedium)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("让 AI 帮我创建专家", style = XfType.bodyStrong, color = colors.ink)
                        Text("描述职责，由创建专家生成草稿", style = XfType.meta, color = colors.ink3)
                    }
                    Icon(XfIcons.ChevRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.ink4)
                }
            }
        }
        item { XfSectionHeader(title = "扩展工作能力", subtitle = "为专家关联方法、资料，或从电脑导入配置。") }
        item {
            XfCard(padding = 4.dp) {
                XfListRow(
                    title = "技能与知识",
                    subtitle = "为专家关联方法和资料",
                    leading = { XfIconTile(XfIcons.Book, size = 34.dp) },
                    trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                    onClick = { onRoute("resources") },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Hr(Modifier.padding(horizontal = 14.dp))
                XfListRow(
                    title = "发现",
                    subtitle = "专家商店与技能市场",
                    leading = { XfIconTile(XfIcons.Grid, size = 34.dp) },
                    trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                    onClick = { onRoute("market") },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Hr(Modifier.padding(horizontal = 14.dp))
                XfListRow(
                    title = "导入智能体蓝图",
                    subtitle = "预览专家、团队及模型映射",
                    leading = { XfIconTile(XfIcons.Blocks, size = 34.dp) },
                    trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                    onClick = if (blocked) null else ({ onRoute("blueprint") }),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

private fun isWorkbenchAgent(id: String): Boolean = id == "xfriend" || id.startsWith("agent-")

@Composable private fun FriendExpertTile(entry: Triple<String,String,String>, team: Boolean, blocked: Boolean, availability: FriendAvailability, s: Store, modifier: Modifier, onChoose: (String,Boolean)->Unit, onEdit:(String,String)->Unit, onDelete:(String,String)->Unit, onRoute:(String)->Unit) {
    val colors = xf
    var menu by remember(entry.first) { mutableStateOf(false) }
    var expanded by rememberSaveable(entry.first) { mutableStateOf(false) }
    val tone = when {
        availability.ready -> XfTone.Success
        availability.remote -> XfTone.Accent
        else -> XfTone.Warning
    }
    val skills = if (team) emptyList() else s.experts.firstOrNull { it.id == entry.first }?.skills.orEmpty()
    val skillNames = skills.mapNotNull { id -> s.skills.firstOrNull { it.id == id }?.name }.take(3)
    XfCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XfAvatar(
                icon = if (team) XfIcons.Users else XfIcons.Brand,
                size = XfDimens.avatarMedium,
                tone = if (availability.ready) XfTone.Accent else XfTone.Neutral,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.second, style = XfType.title, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (team) "团队 · 顺序协作" else if (isWorkbenchAgent(entry.first)) "智能体" else "专家",
                    style = XfType.meta,
                    color = colors.ink3,
                )
            }
            Box {
                XfIconButton(XfIcons.More, "管理${entry.second}", { menu = true }, enabled = !blocked)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("查看与编辑") },
                        onClick = { menu = false; onEdit(if(team) "team" else if(isWorkbenchAgent(entry.first)) "agent" else "expert", entry.first) },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = colors.dangerText) },
                        onClick = { menu = false; onDelete(if(team) "team" else "expert", entry.first) },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            entry.third.ifBlank { "尚未填写说明" },
            style = XfType.small,
            color = colors.ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (team) {
            val members = s.teams.firstOrNull { it.id == entry.first }?.members.orEmpty()
            if (members.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                members.take(if (expanded) members.size else 3).forEachIndexed { index, id ->
                    Text(
                        "${index + 1}. ${s.experts.firstOrNull { it.id == id }?.name ?: "成员缺失，请编辑团队"}",
                        style = XfType.meta,
                        color = colors.ink2,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (members.size > 3) {
                    XfButton(
                        text = if (expanded) "收起成员" else "查看全部 ${members.size} 位成员",
                        onClick = { expanded = !expanded },
                        kind = XfButtonKind.Ghost,
                        small = true,
                    )
                }
            }
        } else if (skillNames.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                skillNames.forEach { name -> XfChip(text = name) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Hr()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            XfStatusChip(label = availability.label, tone = tone, icon = if (availability.remote) XfIcons.Laptop else null)
            Spacer(Modifier.weight(1f))
            XfButton(
                text = if (availability.remote) "连接电脑" else if (availability.ready) (if (team) "开始协作" else "开始对话") else "检查配置",
                onClick = {
                    when { availability.remote -> onRoute("remote"); availability.ready -> onChoose(entry.first, team); else -> onRoute("models") }
                },
                enabled = !blocked,
                kind = if (availability.ready) XfButtonKind.Primary else XfButtonKind.Secondary,
                small = true,
            )
        }
    }
}

@Composable internal fun FriendPackagePreview(vm:SalesViewModel,friend:FriendViewModel,uri:Uri?,clear:()->Unit) {
    val colors = xf
    if(uri!=null) {
        var password by remember(uri) {mutableStateOf("")}
        XfDialog(
            title = "读取配置包",
            onDismiss = clear,
            confirmLabel = "读取并预览",
            onConfirm = { vm.importBundle(uri,password);password="";clear() },
            dismissLabel = "取消",
            confirmEnabled = !vm.busy && vm.data != null,
        ) {
            Text("支持 .xfpack、.xpack 和旧版 JSON。加密包请输入导出时设置的密码。", style = XfType.small, color = colors.ink2)
            XfTextField(
                value = password,
                onValueChange = { password = it },
                label = "包密码（可选）",
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            if (vm.data == null) Text("正在加载资源库…", style = XfType.meta, color = colors.ink3)
        }
    }
    vm.portableBundle?.let { pack ->
        var secrets by remember(pack) {mutableStateOf(false)};var skills by remember(pack) {mutableStateOf(false)}
        val entries=remember(pack){pack.manifest().optJSONArray("experts")?.objects().orEmpty()}
        XfDialog(
            title = "导入预览",
            onDismiss = { if(!vm.busy)vm.dismissBundle() },
            confirmLabel = "确认导入",
            onConfirm = { vm.acceptBundle(friend,secrets,skills) },
            dismissLabel = "取消",
            confirmEnabled = !vm.busy && !friend.busy,
        ) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${entries.size} 项专家／团队配置。重复导入会更新同一包资源，其他内容保留。", style = XfType.small, color = colors.ink2)
                entries.forEach { Text(it.optString("name"), style = XfType.bodyStrong, color = colors.ink) }
                if(pack.secrets().isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = secrets,
                            onCheckedChange = { secrets = it },
                            colors = CheckboxDefaults.colors(checkedColor = colors.accentSolid, uncheckedColor = colors.ink4, checkmarkColor = androidx.compose.ui.graphics.Color.White),
                        )
                        Text("保存包内模型密钥", style = XfType.small, color = colors.ink2)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = skills,
                        onCheckedChange = { skills = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.accentSolid, uncheckedColor = colors.ink4, checkmarkColor = androidx.compose.ui.graphics.Color.White),
                    )
                    Text("已核对来源，启用技能说明", style = XfType.small, color = colors.ink2)
                }
                Text("电脑专属引擎与工具保留配置；手机不自动运行附带脚本。导入不会启动任务。", style = XfType.meta, color = colors.ink3)
            }
        }
    }
}
