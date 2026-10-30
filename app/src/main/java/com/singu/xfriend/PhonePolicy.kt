package com.singu.xfriend

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Pure policy shared by Android adapters and JVM tests. */
internal object PhonePolicy {
    const val MAX_FILE_BYTES = 1_048_576
    const val SNAPSHOT_TTL_MS = 20_000L
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun hash(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
    fun scopeKey(scope: String): String {
        require(scope.isNotBlank() && scope.length <= 512) { "请先登录当前账户。" }
        return hash(scope)
    }
    fun fileName(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 120 && raw == raw.trim()) { "文件名不能为空且最多 120 字。" }
        require(raw != "." && raw != ".." && !raw.contains('/') && !raw.contains('\\') &&
            raw.none { it.code < 32 || it.code == 127 } && !raw.contains(':')) { "只支持单个文件名，不能包含路径。" }
        return raw
    }
    fun fingerprint(name: String, args: JSONObject): String = hash(name + "\n" + canonical(args))
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun nextTime(rule: String, triggerAt: String, localTime: String, timezone: String, now: Long): Long {
        require(rule == "once" || rule == "daily") { "目前仅支持 once（一次）或 daily（每天）。" }
        val zone = ZoneId.of(timezone)
        if (rule == "once") {
            require(triggerAt.isNotBlank()) { "一次提醒需要含时区偏移的 trigger_at，例如 2026-11-01T09:00:00+08:00。" }
            val time = OffsetDateTime.parse(triggerAt).toInstant().toEpochMilli()
            require(time > now + 1_000) { "提醒时间已经过去或太接近当前时间，请提供未来时间。" }
            return time
        }
        require(localTime.matches(Regex("\\d{2}:\\d{2}"))) { "每天提醒的 local_time 必须为 HH:mm。" }
        val clock = LocalTime.parse(localTime)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (day in 0L..3L) {
            val local = today.plusDays(day).atTime(clock)
            // A daylight-saving gap has no such wall-clock time: skip that day, never silently shift it.
            val firstOffset = zone.rules.getValidOffsets(local).firstOrNull() ?: continue
            val time = local.toInstant(firstOffset).toEpochMilli()
            if (time > now + 1_000) return time
        }
        error("无法计算下一次提醒时间。")
    }
    /** Keep an already scheduled future alarm, including the last second before delivery. */
    /**
     * Date-only commitments ("remember to ask about the Mac price tomorrow") are valid without
     * inventing a notification clock time. Returns the normalized ISO local date.
     */
    fun dueDate(raw: String, timezone: String, now: Long): String {
        val zone = ZoneId.of(timezone)
        val date = java.time.LocalDate.parse(raw.trim())
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        require(!date.isBefore(today)) { "目标日期不能早于当前时区的今天。" }
        require(!date.isAfter(today.plusYears(10))) { "目标日期超出可接受范围。" }
        return date.toString()
    }

    /** Explicit relative wording from the current user request. Never invents a clock time. */
    fun dueDateFromText(text: String, timezone: String, now: Long): String {
        val zone = ZoneId.of(timezone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val target = when {
            text.contains("大后天") -> today.plusDays(3)
            text.contains("后天") -> today.plusDays(2)
            text.contains("明天") || text.contains("明日") -> today.plusDays(1)
            text.contains("今天") || text.contains("今日") -> today
            else -> Regex("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?").find(text)?.let { match ->
                runCatching { java.time.LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) }.getOrNull()
            } ?: return ""
        }
        return dueDate(target.toString(), timezone, now)
    }
    fun reconciledDailyTime(existingAt: Long, localTime: String, timezone: String, now: Long): Long =
        if (existingAt > now) existingAt else nextTime("daily", "", localTime, timezone, now)

    fun freshSnapshot(expectedId: String, actualId: String, expectedWindow: Int, actualWindow: Int,
                      capturedAt: Long, now: Long, expectedRevision: Long, revision: Long): Boolean =
        expectedId.isNotBlank() && expectedId == actualId && expectedWindow == actualWindow &&
            now >= capturedAt && now - capturedAt <= SNAPSHOT_TTL_MS && expectedRevision == revision

    private val sensitive = Regex("(?i)(password|passcode|pin\\b|one.?time|otp\\b|verification|security.?code|credit.?card|cvv|secret|token|api.?key|密码|口令|验证码|校验码|密钥|银行卡|身份证)")
    private val protected = Regex("(?i)(pay(?:ment)?|purchase|transfer|send|submit|publish|post\\b|delete|remove|confirm|approve|allow|grant|install|sign.?in|log.?in|sign.?out|log.?out|authorize|authenticate|accept|agree|subscribe|checkout|buy|order|支付|付款|转账|汇款|发送|提交|发布|删除|移除|确认|同意|允许|授权|安装|登录|登陆|退出|验证|购买|下单|订阅|接听|拨打)")
    fun sensitiveText(value: String): Boolean = sensitive.containsMatchIn(value)
    fun protectedText(value: String): Boolean = sensitiveText(value) || protected.containsMatchIn(value)
    fun protectedPackage(value: String): Boolean {
        val lower = value.lowercase()
        return lower == "android" || lower.contains("permissioncontroller") || lower.contains("packageinstaller") ||
            lower.contains("settings") || lower.contains("systemui") || lower.contains("keyguard") ||
            lower.contains("authenticator") || lower.contains("bank") || lower.contains("alipay") ||
            lower.contains("wallet") || lower.contains("payment")
    }
    fun redact(value: String, password: Boolean = false, editable: Boolean = false): String {
        if (password || editable || sensitiveText(value)) return "[敏感内容已隐藏]"
        return value.take(240)
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[邮箱已隐藏]")
            .replace(Regex("(?<!\\d)(?:\\+?\\d[ -]?){4,}(?!\\d)"), "[号码已隐藏]")
            .replace(Regex("(?i)(?:sk-|Bearer )[A-Za-z0-9_./+\\-=]{8,}"), "[凭据已隐藏]")
    }
}

/** In-memory consent. Account changes and process restarts revoke the switch and all old leases. */
internal object PhoneControlSession {
    private var activeScope = ""
    private var enabled = false
    private var generation = 0L
    @Synchronized fun activateScope(scope: String) {
        if (activeScope != scope) { activeScope = scope; enabled = false; generation++ }
    }
    @Synchronized fun setEnabled(scope: String, value: Boolean) {
        if (scope.isNotBlank() && scope == activeScope) { enabled = value; generation++ }
    }
    @Synchronized fun isActive(scope: String): Boolean = scope.isNotBlank() && activeScope == scope
    @Synchronized fun isEnabled(scope: String): Boolean = isActive(scope) && enabled
    @Synchronized fun lease(scope: String): Long? = if (isEnabled(scope)) generation else null
    @Synchronized fun valid(scope: String, lease: Long): Boolean = isEnabled(scope) && generation == lease
}
