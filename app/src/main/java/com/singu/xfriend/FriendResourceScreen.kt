package com.singu.xfriend

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.io.File

private data class ResourceFile(val path: String, val name: String, val owner: String, val size: Long, val updated: Long)

/** Reads only visible content in known expert workspaces, never credential/system trees. */
private fun resourceFiles(store: Store): List<ResourceFile> {
    val files = mutableListOf<ResourceFile>()
    for (expert in store.experts) {
        val root = workspacePath(store.workspaceRoot, expert.id)
        if (!root.isDirectory) continue
        root.walkTopDown().maxDepth(8).onEnter { dir ->
            !dir.name.startsWith(".") && !java.nio.file.Files.isSymbolicLink(dir.toPath())
        }.filter { it.isFile && !it.name.startsWith(".") && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .take((500 - files.size).coerceAtLeast(0)).forEach { file ->
                val relative = file.relativeTo(store.workspaceRoot).invariantSeparatorsPath
                val safe = workspacePath(store.workspaceRoot, relative)
                files += ResourceFile(relative, file.name, expert.name, safe.length(), safe.lastModified())
            }
        if (files.size >= 500) break
    }
    return files.sortedByDescending { it.updated }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FriendResourcePage(
    store: Store, revision: Int, blocked: Boolean,
    onEdit: (String, String) -> Unit, onDelete: (String, String) -> Unit,
    onDesktopImport: () -> Unit, onSkillImport: () -> Unit, onKnowledgeImport: () -> Unit,
    onMarket: () -> Unit,
) {
    val colors = xf
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var menu by remember { mutableStateOf<String?>(null) }
    var files by remember(store) { mutableStateOf<List<ResourceFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedFile by remember { mutableStateOf<ResourceFile?>(null) }
    var preview by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf<ResourceFile?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = exporting
        exporting = null
        if (uri != null && file != null) scope.launch {
            try { withContext(Dispatchers.IO) {
                val source = workspacePath(store.workspaceRoot, file.path)
                context.contentResolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                    ?: error("无法打开保存位置")
            } } catch (cancel: CancellationException) { throw cancel } catch (failure: Exception) { error = "文件导出失败：${failure.message}" }
        }
    }
    LaunchedEffect(store, revision) {
        loading = true
        try { files = withContext(Dispatchers.IO) { resourceFiles(store) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = "文件列表读取失败：${failure.message}" }
        finally { loading = false }
    }
    LaunchedEffect(selectedFile) {
        val file = selectedFile ?: return@LaunchedEffect
        preview = "正在读取…"
        try {
            preview = withContext(Dispatchers.IO) {
                if (File(file.name).extension.lowercase() !in setOf("txt", "md", "csv", "json", "log", "yaml", "yml"))
                    "此格式请保存到手机后，用对应应用打开。"
                else workspacePath(store.workspaceRoot, file.path).inputStream().bufferedReader().use { reader ->
                    val buffer = CharArray(32_000)
                    val count = reader.read(buffer)
                    if (count < 0) "空文件" else String(buffer, 0, count) + if (reader.read() != -1) "\n\n预览已省略后续内容，请保存完整文件查看。" else ""
                }
            }
        } catch (cancel: CancellationException) { throw cancel } catch (failure: Exception) { preview = "无法读取文件：${failure.message}" }
    }
    val filters = listOf("全部", "技能", "知识", "文件")
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = XfDimens.pagePadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        item {
            XfPageHeader(
                title = "资源",
                subtitle = "技能、知识与工作区文件；先预览，再导入或保存。",
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XfButton("新建", { menu = "new" }, enabled = !blocked, icon = XfIcons.Plus)
                XfButton("导入", { menu = "import" }, enabled = !blocked, kind = XfButtonKind.Secondary, icon = XfIcons.Export)
                Spacer(Modifier.weight(1f))
                XfIconButton(XfIcons.Folder, "资源市场", onMarket)
            }
        }
        item {
            XfSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索技能、知识和文件",
            )
        }
        item {
            XfSegmented(
                options = filters,
                selectedIndex = filters.indexOf(filter).coerceAtLeast(0),
                onSelect = { filter = filters[it] },
            )
        }
        item {
            XfCard(onClick = if (!blocked) onDesktopImport else null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    XfIconTile(XfIcons.Laptop, size = XfDimens.avatarMedium, tone = XfTone.Accent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("从电脑导入资源", style = XfType.bodyStrong, color = colors.ink)
                        Text("先预览，再选择要导入的内容", style = XfType.meta, color = colors.ink3)
                    }
                    Icon(XfIcons.ChevRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.ink4)
                }
            }
        }
        error?.let { text -> item { XfBanner(text = text, tone = XfTone.Danger, icon = XfIcons.Bell) } }
        if (filter in listOf("全部", "技能", "知识")) {
            val skills = store.skills.filter { filter != "知识" && (it.name.contains(query, true) || it.content.contains(query, true)) }
            val knowledge = store.knowledge.filter { filter != "技能" && (it.name.contains(query, true) || it.content.contains(query, true)) }
            item {
                XfSectionHeader(
                    title = "技能与知识",
                    subtitle = "${store.skills.size} 项技能 · ${store.knowledge.size} 份知识",
                )
            }
            items(skills, key = { "s:${it.id}" }) { skill ->
                ResourceRow(
                    name = skill.name,
                    subtitle = "技能 · ${store.experts.count { skill.id in it.skills }} 位专家在用",
                    skill = true,
                    enabled = !blocked,
                    open = { onEdit("skill", skill.id) },
                    remove = { onDelete("skill", skill.id) },
                )
            }
            items(knowledge, key = { "k:${it.id}" }) { knowledgeItem ->
                ResourceRow(
                    name = knowledgeItem.name,
                    subtitle = "知识 · ${store.experts.count { knowledgeItem.id in it.knowledge }} 位专家在用",
                    skill = false,
                    enabled = !blocked,
                    open = { onEdit("knowledge", knowledgeItem.id) },
                    remove = { onDelete("knowledge", knowledgeItem.id) },
                )
            }
            if (skills.isEmpty() && knowledge.isEmpty()) item {
                EmptyHint("没有匹配资源", "新建或导入工作方法、资料，再关联给专家。")
            }
        }
        if (filter in listOf("全部", "文件")) {
            val visible = files.filter { it.name.contains(query, true) || it.owner.contains(query, true) }
            item {
                XfSectionHeader(
                    title = "工作区文件",
                    subtitle = if (files.size >= 500) "显示最多 500 个工作区文件" else "${files.size} 个文件",
                )
            }
            if (loading) item { XfProgressBar(progress = null) }
            else if (visible.isEmpty()) item { EmptyHint("暂无文件", "智能体生成的工作区文件会显示在这里。") }
            items(visible, key = { "f:${it.path}" }) { file ->
                FileRow(file = file) { selectedFile = file }
            }
        }
    }
    if (menu != null) ModalBottomSheet(onDismissRequest = { menu = null }, containerColor = colors.surface) {
        Column(Modifier.padding(XfDimens.pagePadding).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (menu == "new") "新建资源" else "导入资源", style = XfType.h2, color = colors.ink)
            XfCard(padding = 4.dp) {
                XfListRow(
                    title = "技能",
                    subtitle = "可复用的方法与提示词",
                    leading = { XfIconTile(XfIcons.Sparkles, size = 34.dp) },
                    trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                    onClick = { if (menu == "new") onEdit("skill", "") else onSkillImport(); menu = null },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Hr(Modifier.padding(horizontal = 14.dp))
                XfListRow(
                    title = "知识",
                    subtitle = "供专家检索的文字资料",
                    leading = { XfIconTile(XfIcons.Book, size = 34.dp) },
                    trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                    onClick = { if (menu == "new") onEdit("knowledge", "") else onKnowledgeImport(); menu = null },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                if (menu == "import") {
                    Hr(Modifier.padding(horizontal = 14.dp))
                    XfListRow(
                        title = "电脑配置包",
                        subtitle = "专家、团队、技能与模型 · 先预览",
                        leading = { XfIconTile(XfIcons.Laptop, size = 34.dp) },
                        trailing = { Icon(XfIcons.ChevRight, null, Modifier.size(16.dp), tint = colors.ink4) },
                        onClick = { menu = null; onDesktopImport() },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
    selectedFile?.let { file ->
        XfDialog(
            title = file.name,
            onDismiss = { selectedFile = null },
            confirmLabel = "保存到手机",
            onConfirm = { exporting = file; export.launch(file.name) },
            dismissLabel = "关闭",
        ) {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(preview, style = XfType.mono, color = colors.ink2)
            }
        }
    }
}

@Composable
private fun ResourceRow(name: String, subtitle: String, skill: Boolean, enabled: Boolean, open: () -> Unit, remove: () -> Unit) {
    val colors = xf
    var menu by remember { mutableStateOf(false) }
    XfCard(padding = 6.dp) {
        XfListRow(
            title = name,
            subtitle = subtitle,
            leading = { XfIconTile(if (skill) XfIcons.Sparkles else XfIcons.Book, size = 36.dp) },
            trailing = {
                Box {
                    XfIconButton(XfIcons.More, "管理$name", { menu = true }, enabled = enabled)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; open() })
                        DropdownMenuItem(
                            text = { Text("删除", color = colors.dangerText) },
                            onClick = { menu = false; remove() },
                        )
                    }
                }
            },
            onClick = if (enabled) open else null,
            minHeight = 56.dp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun FileRow(file: ResourceFile, open: () -> Unit) {
    val colors = xf
    XfCard(padding = 6.dp) {
        XfListRow(
            title = file.name,
            subtitle = "${file.owner} · ${(file.size / 1024).coerceAtLeast(1)} KB",
            leading = { XfIconTile(XfIcons.File, size = 36.dp, tone = XfTone.Neutral) },
            trailing = {
                Icon(XfIcons.ChevRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.ink4)
            },
            onClick = open,
            minHeight = 56.dp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
