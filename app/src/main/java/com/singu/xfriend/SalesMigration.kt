package com.singu.xfriend

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.UUID

private fun resourceJson(store: Store) = JSONObject()
    .put("experts", JSONArray(store.experts.map { JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt).put("skills", JSONArray(it.skills)).put("knowledge", JSONArray(it.knowledge)) }))
    .put("teams", JSONArray(store.teams.map { JSONObject().put("id", it.id).put("name", it.name).put("members", JSONArray(it.members)) }))
    .put("skills", JSONArray(store.skills.map { JSONObject().put("id", it.id).put("name", it.name).put("content", it.content) }))
    .put("knowledge", JSONArray(store.knowledge.map { JSONObject().put("id", it.id).put("name", it.name).put("content", it.content) }))

private fun readMigrationResources(j: JSONObject): ResourceSnapshot {
    fun rows(key: String) = (j.optJSONArray(key)?.objects() ?: emptyList()).also { require(it.size <= 500) { "资源数量过多" } }
    fun field(row: JSONObject, key: String, max: Int) = row.getString(key).also { require(it.isNotBlank() && it.length <= max) { "资源字段不合法" } }
    fun id(row: JSONObject) = field(row, "id", 128).also { require(it.matches(Regex("[A-Za-z0-9_-]+"))) { "资源标识不合法" } }
    val experts = rows("experts").map { Expert(id(it), field(it,"name",100), field(it,"prompt",64000), it.optJSONArray("skills").strings(), it.optJSONArray("knowledge").strings()) }
    val teams = rows("teams").map { Team(id(it), field(it,"name",100), it.getJSONArray("members").strings()) }
    val skills = rows("skills").map { Skill(id(it), field(it,"name",100), field(it,"content",100000)) }
    val knowledge = rows("knowledge").map { Knowledge(id(it), field(it,"name",100), field(it,"content",100000)) }
    require(listOf(experts.map { it.id }, teams.map { it.id }, skills.map { it.id }, knowledge.map { it.id }).all { it.distinct().size == it.size }) { "资源标识重复" }
    require(teams.all { it.members.size <= 8 && it.members.all { ref -> experts.any { e -> e.id == ref } } }) { "团队成员缺失" }
    require(experts.all { it.skills.all { ref -> skills.any { s -> s.id == ref } } && it.knowledge.all { ref -> knowledge.any { k -> k.id == ref } } }) { "专家资料引用缺失" }
    return ResourceSnapshot(experts, teams, skills, knowledge)
}

@Composable internal fun SalesMigrationScreen(friend: FriendViewModel, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var secrets by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && !busy && !friend.busy) {
            busy = true; friend.busy = true; message = "正在加密导出…"
            val pass = password; val includeKeys = secrets
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val store = friend.store ?: error("资源库尚未加载")
                        val repository = SalesRepository(context, friend.accountScope)
                        val original = repository.load()
                        val data = original.copy(profiles = original.profiles.map { if (includeKeys) it else it.copy(key = "") })
                        val manifest = synchronized(store) {
                            JSONObject().put("format","xsalers-migration").put("version",1).put("sourceScope",friend.accountScope)
                                .put("data",data.json()).put("resources",resourceJson(store))
                        }
                        val media = data.records.filter { it.file.isNotBlank() }.associate { it.file to repository.file(it.file) }
                        context.contentResolver.openOutputStream(uri)?.use { SalesMigrationCodec.write(it,pass,manifest,media,context.cacheDir) } ?: error("无法创建迁移文件")
                    }
                    message = "加密迁移包已导出。请在 Xsalers 导入并核对，原数据保留。"; password = ""; confirm = ""
                } catch (cancel: CancellationException) { message = "已取消，未改变原数据"; throw cancel }
                catch (failure: Exception) { message = "导出失败：${failure.message}" }
                finally { busy = false; friend.busy = false }
            }
        }
    }
    BackHandler(enabled = busy) { }
    MigrationLayout(
        title = "迁移销售数据",
        subtitle = "将旧版客户、沟通、录音与专家资料移入独立的 Xsalers。",
        message = message,
        backLabel = "返回",
        backEnabled = !busy,
        onBack = onBack,
    ) {
        XfSectionLabel("步骤")
        XfCard(padding = 12.dp) {
            MigrationStepRow(1, "设置迁移口令", "至少 10 位，导出与导入必须使用同一口令。")
            Hr()
            MigrationStepRow(2, "导出并保存加密文件", "选择保存位置后生成 .xsmigrate 加密包。")
            Hr()
            MigrationStepRow(3, "在 Xsalers 登录并导入", "使用同一账号与空间，核对预览后确认导入。")
        }
        XfTextField(
            value = password,
            onValueChange = { password = it.take(256) },
            label = "迁移口令（至少 10 位）",
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
        )
        XfTextField(
            value = confirm,
            onValueChange = { confirm = it.take(256) },
            label = "再次输入迁移口令",
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
        )
        Row(Modifier.fillMaxWidth().heightIn(min = XfDimens.touch), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = secrets, onCheckedChange = { secrets = it }, enabled = !busy)
            Spacer(Modifier.width(6.dp))
            Text("包含本人配置的模型密钥（默认关闭）", style = XfType.small, color = xf.ink2)
        }
        XfButton(
            text = if (busy) "正在导出…" else "导出加密迁移包",
            onClick = { launcher.launch("Xsalers-销售迁移.xsmigrate") },
            enabled = !busy && !friend.busy && friend.store != null && password.length >= 10 && password == confirm,
            loading = busy,
            icon = XfIcons.Export,
            modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
        )
        Text(
            "单包最多 32 MB；导出包含所有专家、团队、技能与知识。登录令牌不导出。请自行保管口令；原始数据不会删除。",
            style = XfType.meta,
            color = xf.ink3,
        )
    }
}

@Composable internal fun SalesMigrationImportScreen(friend: FriendViewModel, vm: SalesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }; var preview by remember { mutableStateOf<MigrationArchive?>(null) }
    var stage by remember { mutableStateOf<File?>(null) }
    DisposableEffect(Unit) { onDispose { stage?.deleteRecursively() } }
    fun clear() { stage?.deleteRecursively(); stage = null; preview = null }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !busy && !vm.busy && !vm.recording && !friend.busy && vm.tasks.none { it.active }) {
            busy = true; message = "正在验证口令与迁移文件…"; clear()
            val pass = password
            scope.launch {
                var temp: File? = null
                try {
                    val archive = withContext(Dispatchers.IO) {
                        temp = Files.createTempDirectory(context.cacheDir.toPath(),"sales-migration-").toFile()
                        val unpacked = context.contentResolver.openInputStream(uri)?.use { SalesMigrationCodec.read(it,pass,temp!!) } ?: error("无法读取迁移文件")
                        require(unpacked.manifest.getString("sourceScope") == friend.accountScope) { "请使用导出时的同一账号与工作空间" }
                        readMigrationResources(unpacked.manifest.getJSONObject("resources"))
                        val data = SalesData.read(unpacked.manifest.getJSONObject("data"))
                        require(data.records.all { it.file.isBlank() || unpacked.files.containsKey(it.file) }) { "录音或图片附件缺失" }
                        require(data.profiles.all { runCatching { requireSafeApiUrl(it.url) }.isSuccess }) { "模型地址不合法" }
                        mergeMigrationData(vm.data ?: error("销售数据尚未加载"), data)
                        unpacked
                    }
                    stage = temp; preview = archive; message = "文件已验证，请核对后确认导入。"; password = ""
                } catch (cancel: CancellationException) { temp?.deleteRecursively(); throw cancel }
                catch (failure: Exception) { temp?.deleteRecursively(); message = "导入预览失败：${failure.message}" }
                finally { busy = false }
            }
        }
    }
    BackHandler { if (!busy) { clear(); onBack() } }
    MigrationLayout(
        title = "导入旧版销售数据",
        subtitle = "使用 Xfriend 导出的口令加密文件。电脑版 JSON 请使用资源导入入口。",
        message = message,
        backLabel = "返回我的",
        backEnabled = !busy,
        onBack = { clear(); onBack() },
    ) {
        XfTextField(
            value = password,
            onValueChange = { password = it.take(256) },
            label = "迁移口令",
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
        )
        XfButton(
            text = "选择加密迁移包并预览",
            onClick = { launcher.launch(arrayOf("*/*")) },
            enabled = !busy && !vm.busy && !vm.recording && !friend.busy && vm.tasks.none { it.active } && password.length >= 10,
            kind = XfButtonKind.Secondary,
            icon = XfIcons.Folder,
            loading = busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
        )
        preview?.let { archive ->
            val incoming = SalesData.read(archive.manifest.getJSONObject("data"))
            XfSectionLabel("导入预览")
            XfCard {
                XfKeyValueRow("客户", "${incoming.customers.size} 位")
                XfKeyValueRow("沟通", "${incoming.records.size} 条")
                XfKeyValueRow("商机", "${incoming.opportunities.size} 项")
                XfKeyValueRow("跟进", "${incoming.followUps.size} 项")
                XfKeyValueRow("媒体附件", "${archive.files.size} 个")
                XfKeyValueRow("模型配置", "${incoming.profiles.size} 项")
                Spacer(Modifier.height(8.dp))
                Hr()
                Spacer(Modifier.height(6.dp))
                Text(
                    "已有同标识的客户、沟通和跟进保留本机版本；同电话客户合并。专家资源按标识更新。导入不会删除原应用数据。",
                    style = XfType.meta,
                    color = xf.ink3,
                )
                Spacer(Modifier.height(12.dp))
                XfButton(
                    text = "确认导入",
                    onClick = {
                        if (!busy && !vm.busy && !vm.recording && !friend.busy && vm.tasks.none { it.active }) {
                            busy = true; friend.busy = true; message = "正在保存资料…"
                            scope.launch {
                                try {
                                    withContext(NonCancellable + Dispatchers.IO) {
                                        val repository = SalesRepository(context,friend.accountScope)
                                        val old = repository.load(); val store = friend.store ?: error("专家库尚未加载")
                                        val added = mutableListOf<File>()
                                        try {
                                            val records = incoming.records.map { record ->
                                                if (record.file.isBlank() || old.records.any { it.id == record.id }) record
                                                else {
                                                    val name = UUID.randomUUID().toString()+"."+record.file.substringAfterLast('.')
                                                    val file = repository.file(name); added.add(file)
                                                    archive.files.getValue(record.file).copyTo(file)
                                                    record.copy(file=name)
                                                }
                                            }
                                            val next = mergeMigrationData(old,incoming.copy(records=records))
                                            val resource = readMigrationResources(archive.manifest.getJSONObject("resources"))
                                            synchronized(store) {
                                                val snapshot = ResourceSnapshot(store.experts,store.teams,store.skills,store.knowledge)
                                                val bundle = DesktopBundle(resource.experts,resource.teams,resource.skills,resource.knowledge,emptyList())
                                                fun apply(value:ResourceSnapshot) { store.experts=value.experts;store.teams=value.teams;store.skills=value.skills;store.knowledge=value.knowledge }
                                                repository.save(next)
                                                try {
                                                    apply(ResourceSnapshot(snapshot.experts.filterNot{e->bundle.experts.any{it.id==e.id}}+bundle.experts,snapshot.teams.filterNot{e->bundle.teams.any{it.id==e.id}}+bundle.teams,snapshot.skills.filterNot{e->bundle.skills.any{it.id==e.id}}+bundle.skills,snapshot.knowledge.filterNot{e->bundle.knowledge.any{it.id==e.id}}+bundle.knowledge))
                                                    store.save()
                                                } catch(failure:Exception) { apply(snapshot);try{repository.save(old)}catch(rollback:Exception){failure.addSuppressed(rollback)};throw failure }
                                            }
                                        } catch(failure:Exception) { added.forEach{it.delete()};throw failure }
                                    }
                                    friend.revision++; vm.reloadAfterMigration(); clear(); message="销售资料已导入。请核对客户与录音，并在模型配置中选择使用的模型。"
                                } catch(failure:Exception) { message="导入未完成：${failure.message}" }
                                finally {busy=false;friend.busy=false}
                            }
                        }
                    },
                    enabled = !busy && !vm.busy && !vm.recording && !friend.busy && vm.tasks.none { it.active },
                    loading = busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = XfDimens.touch),
                )
            }
        }
    }
}

@Composable
private fun MigrationLayout(
    title: String,
    subtitle: String,
    message: String,
    backLabel: String,
    backEnabled: Boolean,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = xf
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = XfDimens.pagePadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(XfDimens.itemGap),
    ) {
        if (backEnabled) {
            SalesBackButton(backLabel, onBack)
        } else {
            Box(Modifier.alpha(.55f)) { SalesBackButton(backLabel, {}) }
        }
        XfPageHeader(title = title, subtitle = subtitle)
        if (message.isNotBlank()) {
            XfBanner(
                text = message,
                tone = salesMigrationTone(message),
                icon = when (salesMigrationTone(message)) {
                    XfTone.Danger -> XfIcons.Bell
                    XfTone.Success -> XfIcons.CheckCircle
                    else -> XfIcons.Refresh
                },
            )
        }
        content()
    }
}

private fun salesMigrationTone(message: String): XfTone = when {
    message.contains("失败") || message.contains("未完成") || message.contains("取消") -> XfTone.Danger
    message.contains("已导出") || message.contains("已导入") || message.contains("已验证") -> XfTone.Success
    else -> XfTone.Info
}

/** 迁移步骤：编号方块 + 标题/说明，保留原有步骤文案含义。 */
@Composable
private fun MigrationStepRow(index: Int, title: String, subtitle: String) {
    val colors = xf
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(XfDimens.smallRadius)).background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text("$index", style = XfType.label, color = colors.accentInk)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = XfType.bodyStrong, color = colors.ink)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = XfType.meta, color = colors.ink3)
        }
    }
}
