package com.singu.xfriend

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId

/** Caller must recheck account identity, fresh authentication and CRAFT mode in [authorized]. */
internal class PhoneToolRuntime(context: Context, private val scope: String, private val authorized: () -> Boolean) : RuntimePhoneAdapter {
    private val context = context.applicationContext
    private fun allowed(): Boolean = PhoneControlSession.isActive(scope) && authorized()
    override suspend fun execute(name: String, args: JSONObject, requestId: String): String {
        return try {
            when (name) {
                "phone_status" -> status().toString()
                "phone_read_ui", "phone_ui_action" -> ui(name, args, requestId).toString()
                else -> withContext(Dispatchers.IO) { local(name, args, requestId).toString() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: SecurityException) { result("needs_permission", "系统权限不足，请在手机能力页检查授权。").put("settings_path", "星助理设置 → 手机能力").toString() }
        catch (_: java.time.DateTimeException) { result("invalid_argument", "时间或时区无效：一次提醒需要含偏移的未来时间，每天提醒需要 HH:mm 与有效 IANA 时区。").toString() }
        catch (_: IllegalArgumentException) { result("invalid_argument", "参数无效，请检查文件名、未来时间、IANA 时区和 once/daily 计划类型。").toString() }
        catch (_: Exception) { result("error", "操作未确认完成，请查看手机能力页中的实际记录；原有数据不会被覆盖。").toString() }
    }
    private fun status(): JSONObject = result("ok", "手机能力按账户隔离；普通请求默认可以执行。定时任务当前只发本机通知，不会在后台运行模型或联网。")
        .put("notifications", PhonePermissions.notifications(context)).put("exact_alarms", PhonePermissions.exactAlarms(context))
        .put("accessibility_enabled", PhonePermissions.accessibility(context)).put("accessibility_connected", PhoneAccessibilityService.connected != null)
        .put("control_enabled", PhoneControlSession.isEnabled(scope)).put("execution_authorized", allowed())
        .put("schedule_rules", JSONArray(listOf("once", "daily"))).put("scheduled_task_type", "notification")
        .put("timezone", ZoneId.systemDefault().id).put("now", java.time.OffsetDateTime.now().toString())
        .put("settings_path", "星助理设置 → 手机能力")

    private fun local(name: String, args: JSONObject, requestId: String): JSONObject {
        if (name !in LOCAL_TOOLS) return result("unsupported", "未知手机工具。")
        if (!allowed()) return denied()
        val repo = PhoneRepository(context, scope)
        synchronized(repo.lock) {
            if (!allowed()) return denied()
            PhoneAlarms.reconcile(context, repo)
            if (name == "phone_list_items") {
                val kind = args.optString("kind").trim()
                val status = args.optString("status").trim()
                val query = args.optString("query").trim().take(200)
                val items = repo.list().filter { item ->
                    (kind.isBlank() || item.optString("kind") == kind) &&
                        (status.isBlank() || item.optString("status") == status) &&
                        (query.isBlank() || item.optString("title").contains(query, ignoreCase = true) ||
                            item.optString("details").contains(query, ignoreCase = true))
                }
                val offset = args.optInt("offset", 0).coerceAtLeast(0)
                val limit = args.optInt("limit", 20).coerceIn(1, 50)
                val page = JSONArray()
                var used = 0
                for (item in items.drop(offset).take(limit)) {
                    val public = PhoneRepository.publicItem(item)
                    if (page.length() > 0 && used + public.toString().length > 6200) break
                    page.put(public); used += public.toString().length
                }
                return result("ok", "已读取当前账户的本机记录。").put("items", page).put("total", items.size)
                    .put("next_offset", if (offset + page.length() < items.size) offset + page.length() else JSONObject.NULL)
            }
            require(requestId.isNotBlank() && requestId.length <= 1024) { "需要稳定请求 ID。" }
            val key = PhonePolicy.hash(requestId)
            val fingerprint = PhonePolicy.fingerprint(name, args)
            val data = repo.read()
            val requests = data.getJSONObject("requests")
            requests.optJSONObject(key)?.let { prior ->
                if (prior.optString("fingerprint") != fingerprint) return result("idempotency_conflict", "同一请求 ID 的参数发生变化，未执行。")
                val saved = prior.optJSONObject("result") ?: return result("requires_review", "此请求的完成状态不确定，请先查看记录。")
                // Return the actual current item, never a historical promise that an alarm is still active.
                val id = saved.optJSONObject("item")?.optString("id").orEmpty()
                if (id.isNotBlank()) data.getJSONObject("items").optJSONObject(id)?.let {
                    saved.put("item", PhoneRepository.publicItem(it))
                }
                return saved.put("replayed", true).put("message", "该请求已处理，以下为当前实际记录；请以事项状态为准。")
            }
            val reply = when (name) {
                "phone_save_file" -> saveFile(repo, data, args, key, fingerprint)
                "phone_create_item" -> createItem(repo, data, args, key, fingerprint, "item")
                "phone_create_alarm" -> createItem(repo, data, args, key, fingerprint, "alarm")
                "phone_schedule_task" -> {
                    if (args.optString("task_type", "notification") != "notification" || args.has("tool") || args.has("script") || args.has("model"))
                        return result("unsupported", "定时任务目前仅支持 notification 通知提醒，不会自动执行模型、脚本或外部操作。")
                    createItem(repo, data, args, key, fingerprint, "reminder")
                }
                "phone_cancel_item" -> {
                    val id = args.optString("id")
                    val item = data.getJSONObject("items").optJSONObject(id) ?: return result("not_found", "当前账户中没有该事项。")
                    if (item.optString("kind") == "file") return result("unsupported", "此工具仅取消事项和提醒，不能删除文件。")
                    if (!allowed()) return denied()
                    // Persist first so a concurrently delivered old broadcast becomes a no-op.
                    item.put("status", "cancelled")
                    repo.write(data)
                    PhoneAlarms.cancel(context, repo.scopeKey, id)
                    result("ok", "已取消该本机事项或提醒。").put("item", PhoneRepository.publicItem(item))
                }
                else -> result("unsupported", "未知手机工具。")
            }
            if (reply.optString("status") == "ok") {
                requests.put(key, JSONObject().put("fingerprint", fingerprint).put("result", reply))
                repo.write(data)
            }
            return reply
        }
    }
    private fun saveFile(repo: PhoneRepository, data: JSONObject, args: JSONObject, key: String, fingerprint: String): JSONObject {
        val name = PhonePolicy.fileName(args.getString("name"))
        val bytes = args.getString("content").toByteArray(Charsets.UTF_8)
        require(bytes.size <= PhonePolicy.MAX_FILE_BYTES)
        val mime = args.optString("mime", "text/plain")
        require(mime.matches(Regex("[a-zA-Z0-9.+_-]+/[a-zA-Z0-9.+_-]+")) && mime.length <= 100)
        val id = "p-${key.take(32)}"
        val items = data.getJSONObject("items")
        val existing = items.optJSONObject(id)
        if (existing != null && existing.optString("fingerprint") != fingerprint) return result("idempotency_conflict", "请求参数发生变化，原文件未覆盖。")
        val item = existing ?: JSONObject().put("id", id).put("kind", "file").put("name", name).put("title", name)
            .put("mime", mime).put("bytes", bytes.size).put("sha256", PhonePolicy.sha256(bytes)).put("created_at", System.currentTimeMillis())
            .put("stored_name", "$id--$name").put("file_ref", "phone-file:$id").put("fingerprint", fingerprint).put("status", "pending")
        if (!allowed()) return denied()
        // Reserve immutable metadata before creating the file; a crash can recover using its exact hash.
        items.put(id, item); repo.write(data)
        if (!allowed()) return denied()
        repo.saveContent(item, bytes)
        item.put("status", "saved"); repo.write(data)
        return result("ok", "文件已保存到当前账户的本机工作空间，可在手机能力页导出。").put("item", PhoneRepository.publicItem(item))
    }
    private fun createItem(repo: PhoneRepository, data: JSONObject, args: JSONObject, key: String, fingerprint: String, kind: String): JSONObject {
        val title = args.optString("title").trim()
        val details = args.optString("details")
        require(title.isNotBlank() && title.length <= 200 && details.length <= 8000)
        val zone = args.optString("timezone", ZoneId.systemDefault().id)
        val hasTime = args.optString("trigger_at").isNotBlank() || args.optString("local_time").isNotBlank()
        // A date-only commitment is a real persistent item. It must not be turned into a fake
        // 00:00/09:00 reminder when the user only said "tomorrow".
        val dueDateRaw = args.optString("due_date").trim()
        val dueDate = if (dueDateRaw.isBlank()) {
            if (kind == "item" || !hasTime) PhonePolicy.dueDateFromText("$title $details", zone, System.currentTimeMillis()) else ""
        } else {
            if (!hasTime) PhonePolicy.dueDate(dueDateRaw, zone, System.currentTimeMillis())
            else {
                require(kind == "item") { "只有普通事项支持日期；闹钟和定时通知需要明确时刻。" }
                PhonePolicy.dueDate(dueDateRaw, zone, System.currentTimeMillis())
            }
        }
        val scheduled = kind != "item" || hasTime
        val id = "p-${key.take(32)}"
        val items = data.getJSONObject("items")
        val existing = items.optJSONObject(id)
        if (existing != null && existing.optString("fingerprint") != fingerprint) return result("idempotency_conflict", "请求参数发生变化，原事项未修改。")
        val permissionMissing = if (scheduled && hasTime) PhonePermissions.missing(context) else null
        val item = existing ?: JSONObject().put("id", id).put("kind", kind).put("title", title).put("details", details)
            .put("created_at", System.currentTimeMillis()).put("fingerprint", fingerprint).put("status", "pending")
        if (dueDate.isNotBlank() && !item.has("due_date")) item.put("due_date", dueDate).put("timezone", zone)
        if (scheduled && !hasTime && !item.has("next_at")) {
            item.put("status", "needs_time").put("requested_kind", kind)
            if (!allowed()) return denied()
            items.put(id, item); repo.write(data)
            return result("needs_time", "事项已保存${if (dueDate.isNotBlank()) "（目标日期 $dueDate）" else ""}，但通知缺少具体时刻；请提供 HH:mm 或含时区偏移的时间，不会自动编造时间。")
                .put("item", PhoneRepository.publicItem(item))
        }
        if (scheduled && !item.has("next_at")) {
            val rule = args.optString("rule", "once")
            if (rule !in setOf("once", "daily")) return result("unsupported", "目前仅支持 once（一次）或 daily（每天）提醒。")
            val at = PhonePolicy.nextTime(rule, args.optString("trigger_at"), args.optString("local_time"), zone, System.currentTimeMillis())
            item.put("rule", rule).put("timezone", zone).put("trigger_at", args.optString("trigger_at"))
                .put("local_time", args.optString("local_time")).put("next_at", at)
        }
        if (!allowed()) return denied()
        items.put(id, item); repo.write(data)
        if (permissionMissing != null) {
            item.put("status", "needs_permission").put("needs_permission", permissionMissing)
            repo.write(data)
            return permission(permissionMissing).put("message", "事项已保存，但提醒未安排；请手动开启对应权限后再设置提醒。")
                .put("item", PhoneRepository.publicItem(item))
        }
        if (scheduled && item.optString("status") !in listOf("delivered", "cancelled", "delivery_blocked", "missed", "needs_time", "needs_permission")) {
            if (!allowed()) return denied()
            if (item.getLong("next_at") <= System.currentTimeMillis()) {
                item.put("status", "missed"); repo.write(data)
                return result("missed", "先前请求的提醒时间已过，请使用新的未来时间重新创建。").put("item", PhoneRepository.publicItem(item))
            }
            PhoneAlarms.schedule(context, repo.scopeKey, item)
            item.put("status", "scheduled")
        } else if (!scheduled) item.put("status", "open")
        repo.write(data)
        return result("ok", when (kind) {
            "alarm" -> "已设置本机闹钟通知，触发时由系统通知提示。"
            "reminder" -> "已安排本机定时通知，届时不会自动运行模型或执行外部操作。"
            else -> when {
                scheduled -> "已保存本机事项并安排提醒。"
                dueDate.isNotBlank() -> "已保存带目标日期的本机事项（$dueDate，$zone）；没有安排通知时刻。"
                else -> "已保存本机事项。"
            }
        }).put("item", PhoneRepository.publicItem(item))
    }
    private suspend fun ui(name: String, args: JSONObject, requestId: String): JSONObject {
        if (!allowed()) return denied()
        val lease = PhoneControlSession.lease(scope) ?: return permission("phone_control")
        val service = PhoneAccessibilityService.connected ?: return permission("accessibility")
        if (name == "phone_read_ui") return withContext(Dispatchers.Main.immediate) { service.readUi(scope, lease, ::allowed) }
        require(requestId.isNotBlank() && requestId.length <= 1024)
        // Unknown UI effects never replay, even after a process crash or a tool response being lost.
        val claimed = withContext(Dispatchers.IO) {
            val repo = PhoneRepository(context, scope)
            synchronized(repo.lock) {
                if (!allowed() || !PhoneControlSession.valid(scope, lease)) return@synchronized false
                val data = repo.read(); val requests = data.getJSONObject("requests"); val key = PhonePolicy.hash(requestId)
                if (requests.has(key)) return@synchronized false
                requests.put(key, JSONObject().put("fingerprint", PhonePolicy.fingerprint(name, args)).put("result",
                    result("requires_review", "该界面操作可能已经执行，禁止自动重放；请重新读取界面并由用户确认。")))
                repo.write(data); true
            }
        }
        if (!claimed) return result("requires_review", "此界面操作已提交或授权失效；请重新读取界面，不可自动重放。")
        return withContext(Dispatchers.Main.immediate) { service.action(scope, lease, args, ::allowed) }
    }
    private fun denied() = result("needs_permission", "当前账户或执行模式未授权；请回到当前账户并切换执行模式。")
        .put("permission", "execution").put("settings_path", "星助理 → 执行模式")
    private fun permission(kind: String) = result("needs_permission", when (kind) {
        "notifications" -> "请允许应用通知，并开启个人闹钟与提醒通知渠道。"
        "exact_alarms" -> "请在系统设置中允许闹钟和提醒。"
        "accessibility" -> "请在系统无障碍设置中手动开启星助理手机控制服务。"
        else -> "请在手机能力页开启本次手机控制。"
    }).put("permission", kind).put("settings_path", "星助理设置 → 手机能力")

    companion object {
        private val LOCAL_TOOLS = setOf("phone_save_file", "phone_create_item", "phone_create_alarm", "phone_schedule_task", "phone_list_items", "phone_cancel_item")
        fun isReplaySafe(name: String): Boolean = name != "phone_ui_action"
        private fun result(status: String, message: String) = JSONObject().put("status", status).put("message", message)
        fun definitions(): List<JSONObject> {
            fun string(description: String) = JSONObject().put("type", "string").put("description", description)
            fun tool(name: String, description: String, props: JSONObject = JSONObject(), required: List<String> = emptyList()) =
                JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
                    .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)).put("additionalProperties", false)))
            fun schedule() = JSONObject().put("title", string("事项标题，最多200字")).put("details", string("事项内容，最多8000字"))
                .put("rule", string("once 或 daily，默认 once").put("enum", JSONArray(listOf("once", "daily"))))
                .put("trigger_at", string("一次提醒：含时区偏移的未来 ISO 8601 时间，例如 2026-11-01T09:00:00+08:00"))
                .put("local_time", string("每天提醒：HH:mm，例如 09:00"))
                .put("timezone", string("IANA 时区，例如 Asia/Shanghai；省略时使用当前手机时区"))
            fun itemProperties() = schedule().put("due_date", string("仅有目标日期的事项：YYYY-MM-DD。只保存日期，不虚构通知时刻；省略 trigger_at/local_time 时不安排通知。"))
            return listOf(
                tool("phone_status", "检查手机权限、控制状态、时区和当前时间。权限不足不能声称已完成。"),
                tool("phone_create_alarm", "真实安排当前账户的系统闹钟通知；一次或每天；需要通知和准确闹钟权限。返回 scheduled 才代表已安排，提示方式为通知。", schedule(), listOf("title", "rule")),
                tool("phone_create_item", "在当前账户本机持久保存个人事项；可用 due_date 保存只有目标日期、没有通知时刻的事项，也可附 once/daily 提醒。返回真实事项 ID。", itemProperties(), listOf("title")),
                tool("phone_schedule_task", "安排一次或每日的本机通知提醒。只支持 notification；不自动运行模型、脚本或外部副作用。", schedule().put("task_type", string("仅支持 notification").put("enum", JSONArray(listOf("notification")))), listOf("title", "rule")),
                tool("phone_save_file", "将 UTF-8 文本真实保存到当前账户本机工作空间；最多1 MiB；不覆盖旧文件。返回可查回的 file_ref 和 sha256；用户可从手机能力页导出。", JSONObject().put("name", string("单个文件名，不接受路径"))
                    .put("content", string("要写入文件的完整文本")) .put("mime", string("MIME类型，默认 text/plain")), listOf("name", "content")),
                tool("phone_list_items", "分页读取当前账户已保存文件、事项、提醒及实际状态；可按来源/状态/关键词筛选。", JSONObject()
                    .put("offset", JSONObject().put("type", "integer").put("minimum", 0).put("description", "起始位置，默认0"))
                    .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 50).put("description", "每页条数，默认20；按返回的next_offset继续"))
                    .put("kind", string("可选：item、alarm、reminder、file"))
                    .put("status", string("可选：open、scheduled、saved、cancelled 等实际状态"))
                    .put("query", string("可选：在标题和详情中按关键词查询，用于跨会话找回旧事项"))),
                tool("phone_cancel_item", "按真实 ID 取消当前账户的本机事项或闹钟；不能删除文件。", JSONObject().put("id", string("phone_list_items 返回的事项ID")), listOf("id")),
                tool("phone_read_ui", "仅在用户开启本次手机控制且处于执行模式时读取当前可见 UI；敏感内容隐藏。返回短时 snapshot_id 和节点ID，界面变化需重读。"),
                tool("phone_ui_action", "操作用户明确要求控制的当前界面；仅点击、滚动、返回、主页、打开用户指定应用。不自动执行授权、登录、发送、支付等流程。非幂等，禁止重放；action_dispatched 仅表示发出操作，必须重读验证。", JSONObject()
                    .put("snapshot_id", string("最新 phone_read_ui 返回的 snapshot_id"))
                    .put("action", string("操作").put("enum", JSONArray(listOf("click", "scroll_forward", "scroll_backward", "back", "home", "launch_app"))))
                    .put("node_id", string("点击或滚动时为当前快照的节点ID"))
                    .put("package", string("launch_app 时为用户明确指定应用的包名")), listOf("snapshot_id", "action"))
            )
        }
    }
}
