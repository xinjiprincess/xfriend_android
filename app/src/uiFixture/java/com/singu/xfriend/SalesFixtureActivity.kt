package com.singu.xfriend

import android.os.Bundle
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

/** Only compiled with salesUiFixture=true into the separately installed .qa products. */
class SalesFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scope = "f".repeat(64)
        FriendStoreRegistry.bindAuthorization(scope) { true }
        if (intent.getBooleanExtra("verifyRuntime", false)) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { verifyFriendRuntime(application) }.onFailure { File(filesDir, "qa-friend-runtime-evidence.json").writeText(JSONObject().put("passed", false).put("error_type", it.javaClass.simpleName).toString()) }
        }
        if (intent.getBooleanExtra("verifyLocalRoom", false)) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { verifyLocalRoomRuntime(application) }.onFailure { File(filesDir, "qa-local-room-evidence.json").writeText(JSONObject().put("passed", false).put("error_type", it.javaClass.simpleName).toString()) }
        }
        if (intent.getBooleanExtra("verifyMigration", false)) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { verifySalesMigration(application) }.onFailure { File(filesDir, "qa-migration-evidence.json").writeText(JSONObject().put("passed", false).put("error", it.toString()).toString()) }
        }
        if (ProductBrand.isSales) lifecycleScope.launch(Dispatchers.IO) {
            val repo = SalesRepository(application, scope)
            val data = repo.load()
            if (data.customers.isEmpty()) repo.save(data.copy(
                customers = listOf(Customer("qa-customer", "示例客户 · 陈经理", "13800000000", "云川科技", notes = "演示数据，不是真实客户。关注交付周期与售后。", insight = CustomerInsight(summary = "关注交付确定性，采购预算待确认。", preferences = "喜欢简明方案和明确时间表", needs = "月底前交付", budget = "待采购确认", timing = "月底", decisionRole = "业务对接人", grade = "重点跟进"))),
                records = listOf(Communication("qa-record", "方案沟通复盘", "qa-customer", text = "客户：希望月底前交付，预算需要和采购确认。销售：周三发送方案，安排技术沟通。", analysisJson = """{"profile":{"summary":"客户希望月底交付，预算与决策链待确认","preferences":"清晰交期","needs":"月底前交付","budget":"未知","timing":"月底","decisionRole":"待确认","grade":"重点跟进"},"actions":[{"title":"周三发送方案","dueDate":"2026-10-15","priority":"高","evidenceIds":["qa-e1"]}],"evidence":[{"id":"qa-e1","quote":"客户：希望月底前交付，预算需要和采购确认。"}],"probability":{"low":35,"high":55,"reason":"仍缺预算与决策人确认"}}""", report = "# 本次沟通\n\n客户关注交付周期，预算与采购流程待确认。\n\n## 下一步\n\n周三发送方案，确认决策人及采购流程。")),
                opportunities = listOf(Opportunity("qa-opportunity", "qa-customer", "年度服务方案", "28000", "方案中", "2026-10-30")),
                followUps = listOf(FollowUp("qa-followup", "qa-customer", "发送方案，确认交付周期", "2026-10-15", recordId = "qa-record")),
            ))
        }
        if (intent.getBooleanExtra("seedCallRecordings", false)) lifecycleScope.launch(Dispatchers.IO) {
            val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            listOf("通话录音_20261016_1432.wav" to 2, "call_20261016_1505.wav" to 3).forEachIndexed { index, (name, seconds) ->
                val file = File(dir, name)
                val bytes = seconds * 32000
                RandomAccessFile(file, "rw").use { f ->
                    f.write(wavHeader(bytes, 16000, 1))
                    f.setLength(44L + bytes)
                }
                file.setLastModified(System.currentTimeMillis() - index * 3_600_000L)
            }
        }
        if (intent.getBooleanExtra("seedTranscribedCall", false)) lifecycleScope.launch(Dispatchers.IO) {
            val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val name = "通话录音_20261016_1432.wav"
            val file = File(dir, name)
            if (!file.exists()) {
                val bytes = 2 * 32000
                RandomAccessFile(file, "rw").use { f ->
                    f.write(wavHeader(bytes, 16000, 1))
                    f.setLength(44L + bytes)
                }
            }
            val repo = SalesRepository(application, scope)
            // 初始示例数据的 save 可能晚于本 block 完成并覆盖 records，等它落地后再补写。
            var waited = 0
            while (repo.load().customers.isEmpty() && waited < 100) {
                delay(100)
                waited++
            }
            val id = "qa-call-transcribed"
            repo.update { d ->
                d.copy(records = listOf(
                    Communication(id, "通话录音", kind = "audio",
                        text = "客户：确认交付时间。销售：周三发送方案。",
                        rawTranscript = "客户：确认交付时间。销售：周三发送方案。",
                        sourceName = name),
                ) + d.records.filterNot { it.id == id })
            }
        }
        if (intent.getBooleanExtra("verifyAudio", false)) lifecycleScope.launch(Dispatchers.IO) {
            File(filesDir, "qa-audio-evidence.json").delete()
            val repo = SalesRepository(application, scope)
            val ids = listOf("qa-audio-highrate", "qa-audio-long")
            repo.changeTasks { old -> old.filterNot { it.id in ids } }
            repo.update { d -> d.copy(records = d.records.filterNot { it.id in ids }) }
            for ((i, id) in ids.withIndex()) {
                val size = if (i == 0) 48000 * 4 * 180 else 25 * 1024 * 1024
                val source = File(cacheDir, "$id.wav")
                RandomAccessFile(source, "rw").use { f -> f.write(wavHeader(size, if (i == 0) 48000 else 16000, if (i == 0) 2 else 1)); f.setLength(44L + size) }
                repo.changeTasks { old -> old + SalesTask(id, "import", "QA 大录音测试 $i", JSONObject().put("uri", Uri.fromFile(source).toString()).put("kind", "audio").put("customerId", "qa-customer").toString()) }
            }
            withContext(Dispatchers.Main) { SalesTaskService.kick(application, scope) }
            repeat(240) {
                delay(1000)
                val tasks = repo.tasks().filter { it.id in ids }
                if (tasks.size == 2 && tasks.all { !it.active }) {
                    val evidence = JSONObject().put("synthetic_only", true).put("tasks", JSONArray(tasks.map { it.json().put("payload", "redacted") }))
                    evidence.put("records", JSONArray(repo.load().records.filter { it.id in ids }.map { record ->
                        val info = wavInfo(repo.file(record.file))
                        JSONObject().put("id", record.id).put("rate", info.rate).put("channels", info.channels).put("bytes", info.bytes).put("segments", info.segments)
                    }))
                    File(filesDir, "qa-audio-evidence.json").writeText(evidence.toString(2))
                    return@launch
                }
            }
            File(filesDir, "qa-audio-evidence.json").writeText("{\"timeout\":true}")
        }
        setContent {
            ProductTheme {
                val friend: FriendViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = FriendViewModel(application, scope) as T
                })
                val sales: SalesViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = SalesViewModel(application, scope) { null } as T
                })
                LaunchedEffect(friend.store) {
                    if (ProductBrand.isSales) friend.store?.let { s ->
                        if (s.teams.none { it.id == salesTeam.id }) friend.change { it.teams = it.teams + salesTeam }
                    }
                }
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("界面验收 · 独立 QA 安装 · 示例数据", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelSmall)
                    }
                    Box(Modifier.weight(1f)) {
                        if (ProductBrand.isSales) SalesScreen(sales, friend, {}, {})
                        else AssistantRoot(friend, null, {})
                    }
                }
            }
        }
    }
}
