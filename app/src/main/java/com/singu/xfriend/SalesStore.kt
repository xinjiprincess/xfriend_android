package com.singu.xfriend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val DEFAULT_TRIAL_URL = "https://113.219.170.192"
internal data class SalesData(val customers: List<Customer> = emptyList(), val records: List<Communication> = emptyList(), val profiles: List<ModelProfile> = emptyList(), val active: String = "", val trialUrl: String = DEFAULT_TRIAL_URL, val useTrial: Boolean = true, val mode: String = "综合分析", val opportunities: List<Opportunity> = emptyList(), val followUps: List<FollowUp> = emptyList(), val expertVersions: List<ExpertRevision> = emptyList(), val callAutoScan: Boolean = true, val callDeleteAfterTranscribe: Boolean = true, val callSkipped: List<String> = emptyList()) {
    fun json() = JSONObject().put("customers", JSONArray(customers.map { it.json() })).put("records", JSONArray(records.map { it.json() })).put("profiles", JSONArray(profiles.map { it.json() })).put("active", active).put("trialUrl", trialUrl).put("useTrial", useTrial).put("mode", mode).put("opportunities", JSONArray(opportunities.map { it.json() })).put("followUps", JSONArray(followUps.map { it.json() })).put("expertVersions", JSONArray(expertVersions.map { it.json() })).put("callAutoScan", callAutoScan).put("callDeleteAfterTranscribe", callDeleteAfterTranscribe).put("callSkipped", JSONArray(callSkipped))
    fun withoutCustomer(id: String) = copy(
        customers = customers.filterNot { it.id == id },
        records = records.map { if (it.customerId == id) it.copy(customerId = "") else it },
        opportunities = opportunities.map { if (it.customerId == id) it.copy(customerId = "") else it },
        followUps = followUps.map { if (it.customerId == id) it.copy(customerId = "") else it }
    )
    fun withoutRecord(id: String) = copy(records = records.filterNot { it.id == id }, followUps = followUps.map { if (it.recordId == id) it.copy(recordId = "") else it })
    fun withOpportunity(value: Opportunity): SalesData {
        require(value.id.isNotBlank() && value.title.isNotBlank() && value.title.length <= 100 && value.notes.length <= 12000 && value.stage.isNotBlank() && value.stage.length <= 100) { "商机名称、阶段或备注格式不正确" }
        require(value.customerId.isEmpty() || customers.any { it.id == value.customerId }) { "关联客户不存在" }
        require(value.amount.isEmpty() || (value.amount.length <= 40 && value.amount.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")))) { "金额请输入非负数字，最多两位小数" }
        requireSalesDate(value.expectedDate)
        require(validCustomerProbability(value.probabilityLow, value.probabilityHigh)) { "成交估计区间应在 0–100 之间，且下限不大于上限" }
        return copy(opportunities = opportunities.filterNot { it.id == value.id } + value)
    }
    fun withFollowUp(value: FollowUp): SalesData {
        require(value.id.isNotBlank() && value.title.isNotBlank() && value.title.length <= 200) { "跟进标题需在 200 字以内" }
        require(value.customerId.isEmpty() || customers.any { it.id == value.customerId }) { "关联客户不存在" }
        val record = records.find { it.id == value.recordId }
        require(value.recordId.isEmpty() || record != null) { "关联沟通记录不存在" }
        require(record == null || record.customerId.isEmpty() || record.customerId == value.customerId) { "沟通记录与跟进的关联客户不一致" }
        requireSalesDate(value.dueDate)
        require(value.dueTime.isEmpty() || value.dueTime.matches(Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))) { "请选择有效时间" }
        require(value.priority in listOf("低", "普通", "高", "紧急")) { "请选择有效优先级" }
        require(!value.reminder || (value.dueDate.isNotBlank() && value.dueTime.isNotBlank())) { "提醒需设置日期和时间" }
        return copy(followUps = followUps.filterNot { it.id == value.id } + value)
    }
    companion object { fun read(j: JSONObject) = SalesData(j.optJSONArray("customers")?.objects()?.map(Customer::read) ?: emptyList(), j.optJSONArray("records")?.objects()?.map(Communication::read) ?: emptyList(), j.optJSONArray("profiles")?.objects()?.map(ModelProfile::read) ?: emptyList(), j.optString("active"), j.optString("trialUrl").ifBlank { DEFAULT_TRIAL_URL }, j.optBoolean("useTrial", true), j.optString("mode", "综合分析"), j.optJSONArray("opportunities")?.objects()?.map(Opportunity::read) ?: emptyList(), j.optJSONArray("followUps")?.objects()?.map(FollowUp::read) ?: emptyList(), j.optJSONArray("expertVersions")?.objects()?.map(ExpertRevision::read) ?: emptyList(), j.optBoolean("callAutoScan", true), j.optBoolean("callDeleteAfterTranscribe", true), j.optJSONArray("callSkipped")?.strings() ?: emptyList()) }
}
internal class SalesRepository(private val context: Context, private val scope: String) {
    companion object {
        internal val lock = Any()
        /**
         * Per-scope in-memory mirror of the durable rows. Every mutation publishes the committed
         * snapshot here under [lock], so readers (UI, workers, reminders, cloud sync) never need to
         * re-decrypt the database to observe another writer's change. Disk remains the source of
         * truth across process restarts; a failed commit never publishes.
         */
        private class Mirror {
            val data = MutableStateFlow<SalesData?>(null)
            val tasks = MutableStateFlow<List<SalesTask>?>(null)
        }
        private val mirrors = ConcurrentHashMap<String, Mirror>()
        private fun mirror(scope: String) = mirrors.getOrPut(scope) { Mirror() }
    }
    private val mirror = mirror(scope)
    val dataFlow: StateFlow<SalesData?> get() = mirror.data
    val tasksFlow: StateFlow<List<SalesTask>?> get() = mirror.tasks
    init { require(scope.matches(Regex("[a-f0-9]{64}"))) }
    val media = File(context.noBackupFilesDir, "sales/$scope/media").apply { mkdirs() }
    private val file = AtomicFile(File(context.noBackupFilesDir, "sales/$scope/state.bin"))
    private val alias = "${context.packageName}.sales.v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()) }.generateKey()
    }
    private val database by lazy { SalesDatabase.open(context.applicationContext, scope) }
    private val tasksFile = AtomicFile(File(media.parentFile, "tasks.bin"))
    private fun encrypt(value: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return JSONObject().put("iv", Base64.getEncoder().encodeToString(c.iv))
            .put("data", Base64.getEncoder().encodeToString(c.doFinal(value.toByteArray(Charsets.UTF_8))))
            .toString().toByteArray(Charsets.UTF_8)
    }
    private fun decrypt(value: ByteArray): String {
        val j = JSONObject(String(value, Charsets.UTF_8))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.getDecoder().decode(j.getString("iv"))))
        }
        return String(c.doFinal(Base64.getDecoder().decode(j.getString("data"))), Charsets.UTF_8)
    }
    private fun legacy(source: AtomicFile): String? {
        if (!source.baseFile.exists() && !File(source.baseFile.path + ".bak").exists()) return null
        return decrypt(source.openRead().use { it.readBytes() })
    }
    private fun readRows(kind: String) = database.entries().entries(kind).map { JSONObject(decrypt(it.payload)) }
    private fun writeRows(kind: String, rows: List<Pair<String, JSONObject>>) {
        require(rows.map { it.first }.distinct().size == rows.size) { "数据标识重复" }
        val dao = database.entries()
        val current = dao.entries(kind).associateBy { it.id }
        val ids = rows.map { it.first }.toSet()
        current.keys.filterNot { it in ids }.forEach { dao.remove(kind, it) }
        rows.forEachIndexed { index, (id, json) ->
            val text = json.toString()
            val old = current[id]
            if (old == null || old.position != index || decrypt(old.payload) != text) {
                dao.put(SalesEntry(kind, id, index, encrypt(text)))
            }
        }
    }
    private fun writeData(value: SalesData) {
        writeRows("customer", value.customers.map { it.id to it.json() })
        writeRows("communication", value.records.map { it.id to it.json() })
        writeRows("opportunity", value.opportunities.map { it.id to it.json() })
        writeRows("followUp", value.followUps.map { it.id to it.json() })
        writeRows("profile", value.profiles.map { it.id to it.json() })
        val settings = value.copy(customers = emptyList(), records = emptyList(), opportunities = emptyList(), followUps = emptyList(), profiles = emptyList()).json()
        writeRows("settings", listOf("state" to settings))
    }
    /** Marker and every imported row commit together. Legacy encrypted originals are retained. */
    private fun initialize() {
        if (database.entries().entries("settings").isNotEmpty()) return
        val state = legacy(file)?.let { SalesData.read(JSONObject(it)) } ?: SalesData()
        val tasks = legacy(tasksFile)?.let { JSONArray(it).objects().map(SalesTask::read) }.orEmpty()
        writeData(state)
        writeRows("task", tasks.map { it.id to it.json() })
    }
    private fun readData() = SalesData.read(readRows("settings").single()).copy(
        customers = readRows("customer").map(Customer::read), records = readRows("communication").map(Communication::read),
        opportunities = readRows("opportunity").map(Opportunity::read), followUps = readRows("followUp").map(FollowUp::read),
        profiles = readRows("profile").map(ModelProfile::read),
    )
    private fun <T> transaction(action: () -> T): T = synchronized(lock) {
        database.runInTransaction(java.util.concurrent.Callable { initialize(); action() })
    }
    fun load(): SalesData = mirror.data.value ?: transaction { readData().also { mirror.data.value = it } }
    fun save(value: SalesData) { transaction { writeData(value); mirror.data.value = value } }
    fun update(action: (SalesData) -> SalesData): SalesData = transaction {
        action(mirror.data.value ?: readData()).also { next -> writeData(next); mirror.data.value = next }
    }
    fun tasks(): List<SalesTask> = mirror.tasks.value ?: transaction { readRows("task").map(SalesTask::read).also { mirror.tasks.value = it } }
    fun changeTasks(action: (List<SalesTask>) -> List<SalesTask>) {
        transaction {
            val next = action(readRows("task").map(SalesTask::read))
            writeRows("task", next.map { it.id to it.json() })
            mirror.tasks.value = next
        }
    }
    fun file(name: String): File { require(name.matches(Regex("[a-zA-Z0-9-]+\\.(wav|mp3|jpg)"))) { "无效媒体标识" }; return File(media, name) }
}
