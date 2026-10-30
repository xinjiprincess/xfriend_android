package com.singu.xfriend

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** On-demand visible UI access only. Events invalidate snapshots; no event text is collected. */
class PhoneAccessibilityService : AccessibilityService() {
    private data class Target(val path: List<Int>, val signature: String, val blocked: Boolean, val scrollBlocked: Boolean)
    private data class Snapshot(val id: String, val scope: String, val lease: Long, val window: Int,
        val packageName: String, val capturedAt: Long, val revision: Long, val targets: Map<String, Target>, val protectedScreen: Boolean)
    private var revision = 0L
    private var snapshot: Snapshot? = null

    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { revision++; snapshot = null }
    override fun onInterrupt() { revision++; snapshot = null }
    override fun onDestroy() { if (connected === this) connected = null; snapshot = null; super.onDestroy() }

    private fun permitted(scope: String, lease: Long, authorized: () -> Boolean): Boolean =
        connected === this && PhonePermissions.accessibility(this) && PhoneControlSession.valid(scope, lease) && authorized() &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    internal fun readUi(scope: String, lease: Long, authorized: () -> Boolean): JSONObject = synchronized(this) {
        if (!permitted(scope, lease, authorized)) return response("needs_permission", "手机控制未授权、授权已失效或手机仍在锁屏。")
        val root = rootInActiveWindow ?: return response("unavailable", "当前没有可读取的前台界面。")
        try {
            val packageName = root.packageName?.toString().orEmpty()
            if (AppConfigProtection.isProtectedScreen(applicationContext.packageName, packageName)) {
                return if (AppConfigProtection.isSelfPackage(applicationContext.packageName, packageName))
                    response(AppConfigProtection.RESULT_CODE, AppConfigProtection.rejection())
                else response("requires_user", "当前为系统授权、身份验证或支付界面，请手动操作。")
            }
            val nodes = JSONArray()
            val targets = linkedMapOf<String, Target>()
            var screenProtected = false
            var sensitiveScreen = false
            var visited = 0
            // Return protected-content and usable-label flags for this branch. A protected
            // button blocks its own ancestors/descendants, not unrelated sibling navigation.
            fun walk(node: AccessibilityNodeInfo, path: List<Int>, depth: Int, ancestorProtected: Boolean): Pair<Boolean, Boolean> {
                if (visited++ >= 120 || depth > 12) return true to false
                val raw = listOf(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.viewIdResourceName.orEmpty()).joinToString(" ")
                val isSensitive = node.isPassword || PhonePolicy.sensitiveText(raw)
                val ownProtected = node.isPassword || node.isEditable || PhonePolicy.protectedText(raw)
                if (isSensitive) { sensitiveScreen = true; screenProtected = true }
                var hasLabel = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
                var branchProtected = ownProtected
                val id = if (node.isVisibleToUser) "n${targets.size + 1}" else null
                val nodeJson = if (id != null) JSONObject().put("id", id).put("role", node.className?.toString()?.substringAfterLast('.').orEmpty())
                    .put("text", PhonePolicy.redact(node.text?.toString().orEmpty(), node.isPassword, node.isEditable))
                    .put("description", PhonePolicy.redact(node.contentDescription?.toString().orEmpty(), node.isPassword, node.isEditable))
                    .put("enabled", node.isEnabled) else null
                if (id != null) {
                    // Reserve IDs before descendants are visited.
                    targets[id] = Target(path, signature(node), true, true)
                    nodes.put(nodeJson)
                }
                if (!node.isPassword && !node.isEditable && !isSensitive) {
                    for (i in 0 until node.childCount.coerceAtMost(60)) {
                        val child = node.getChild(i) ?: continue
                        val inspected = try { walk(child, path + i, depth + 1, ancestorProtected || ownProtected) } finally { child.recycle() }
                        branchProtected = branchProtected || inspected.first
                        hasLabel = hasLabel || inspected.second
                        if (visited >= 120) { branchProtected = true; break }
                    }
                    if (node.childCount > 60) branchProtected = true
                }
                if (id != null) {
                    val blocked = ancestorProtected || branchProtected || (node.isClickable && !hasLabel)
                    val scrollBlocked = ancestorProtected || ownProtected
                    targets[id] = Target(path, signature(node), blocked, scrollBlocked)
                    nodeJson!!.put("clickable", node.isClickable && !blocked).put("scrollable", node.isScrollable && !scrollBlocked).put("requires_user", blocked)
                }
                return branchProtected to hasLabel
            }
            walk(root, emptyList(), 0, false)
            if (!permitted(scope, lease, authorized)) return response("needs_permission", "读取过程中授权已失效。")
            val returnedNodes = JSONArray()
            var used = 0
            if (!sensitiveScreen) for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i)
                if (used + node.toString().length > 6000) break
                returnedNodes.put(node); used += node.toString().length
            }
            val id = UUID.randomUUID().toString()
            snapshot = Snapshot(id, scope, lease, root.windowId, packageName, SystemClock.elapsedRealtime(), revision, targets, screenProtected)
            return response("ok", "已读取当前可见界面；快照短时有效，敏感字段已隐藏。")
                .put("snapshot_id", id).put("package", packageName).put("nodes", returnedNodes)
                .put("truncated", !sensitiveScreen && returnedNodes.length() < nodes.length())
                .put("expires_in_ms", PhonePolicy.SNAPSHOT_TTL_MS).put("requires_user", screenProtected)
        } finally { root.recycle() }
    }

    internal fun action(scope: String, lease: Long, args: JSONObject, authorized: () -> Boolean): JSONObject = synchronized(this) {
        if (!permitted(scope, lease, authorized)) return response("needs_permission", "手机控制未授权、授权已失效或手机仍在锁屏。")
        // The whole device-action channel is serialized: a second caller can never act on a
        // snapshot observed before the first caller's click. It must re-read the screen.
        val prior = snapshot ?: return response("stale_snapshot", "界面已变化，请重新读取当前界面。")
        val root = rootInActiveWindow ?: return response("unavailable", "当前没有可操作的前台界面。")
        try {
            if (prior.scope != scope || prior.lease != lease || !PhonePolicy.freshSnapshot(args.optString("snapshot_id"), prior.id,
                    prior.window, root.windowId, prior.capturedAt, SystemClock.elapsedRealtime(), prior.revision, revision) ||
                root.packageName?.toString() != prior.packageName) return response("stale_snapshot", "界面或账户授权已变化，请重新读取当前界面。")
            val operation = args.optString("action")
            if (operation !in setOf("click", "scroll_forward", "scroll_backward", "back", "home", "launch_app")) return response("unsupported", "支持点击、滚动、返回、主页和打开指定应用；输入和敏感流程请手动完成。")
            if (AppConfigProtection.isSelfPackage(applicationContext.packageName, prior.packageName))
                return response(AppConfigProtection.RESULT_CODE, AppConfigProtection.rejection())
            if (PhonePolicy.protectedPackage(prior.packageName) || (prior.protectedScreen && operation !in setOf("back", "home")))
                return response("requires_user", "当前界面涉及敏感信息，请手动完成后重新读取。")
            val dispatched = when (operation) {
                "home", "back" -> {
                    if (!permitted(scope, lease, authorized)) return response("needs_permission", "授权已失效。")
                    performGlobalAction(if (operation == "home") GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
                }
                "launch_app" -> {
                    val targetPackage = args.optString("package")
                    if (!targetPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) ||
                        AppConfigProtection.isProtectedScreen(applicationContext.packageName, targetPackage))
                        return response(if (AppConfigProtection.isSelfPackage(applicationContext.packageName, targetPackage))
                            AppConfigProtection.RESULT_CODE else "requires_user",
                            if (AppConfigProtection.isSelfPackage(applicationContext.packageName, targetPackage))
                                AppConfigProtection.rejection() else "该应用需要由你手动打开。")
                    val launch = packageManager.getLaunchIntentForPackage(targetPackage)
                        ?: return response("unavailable", "没有找到该应用的可启动入口。")
                    if (!permitted(scope, lease, authorized)) return response("needs_permission", "授权已失效。")
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                }
                else -> {
                    val target = prior.targets[args.optString("node_id")] ?: return response("invalid_argument", "节点不属于当前快照。")
                    if ((operation == "click" && target.blocked) || (operation != "click" && target.scrollBlocked)) return response("requires_user", "此操作涉及授权、登录、发送、支付或其他需你确认的流程，请手动完成。")
                    val node = findNode(root, target.path) ?: return response("stale_snapshot", "目标节点已变化，请重新读取界面。")
                    try {
                        if (!node.refresh() || signature(node) != target.signature || !node.isVisibleToUser || !node.isEnabled || node.windowId != prior.window)
                            return response("stale_snapshot", "目标节点已变化，请重新读取界面。")
                        if (operation == "click" && !clickBranchSafe(node)) return response("requires_user", "目标内容涉及需你确认的流程，请手动完成。")
                        if (!permitted(scope, lease, authorized)) return response("needs_permission", "授权已失效。")
                        val action = when (operation) {
                            "click" -> AccessibilityNodeInfo.ACTION_CLICK
                            "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        }
                        if ((operation == "click" && !node.isClickable) || (operation != "click" && !node.isScrollable))
                            return response("unsupported", "此节点不支持所请求的操作。")
                        node.performAction(action)
                    } finally { if (node !== root) node.recycle() }
                }
            }
            snapshot = null
            return response(if (dispatched) "action_dispatched" else "action_rejected",
                if (dispatched) "已向系统发出操作，请重新读取界面验证结果；不能据此声称目标事务已完成。" else "系统未接受此操作，请重新读取界面。")
                .put("requires_observation", true)
        } finally { root.recycle() }
    }
    private fun findNode(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var current = root
        for (index in path) {
            val child = current.getChild(index)
            if (current !== root) current.recycle()
            current = child ?: return null
        }
        return current
    }
    /** Recheck descendant labels at dispatch: a blank clickable parent cannot bypass a protected child. */
    private fun clickBranchSafe(root: AccessibilityNodeInfo): Boolean {
        var visited = 0
        fun check(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > 120 || depth > 12 || node.childCount > 60 || node.isPassword || node.isEditable) return false
            val text = listOf(node.text, node.contentDescription, node.viewIdResourceName).joinToString(" ")
            if (PhonePolicy.protectedText(text)) return false
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: return false
                val safe = try { check(child, depth + 1) } finally { child.recycle() }
                if (!safe) return false
            }
            return true
        }
        return check(root, 0)
    }
    private fun signature(node: AccessibilityNodeInfo): String {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        return PhonePolicy.hash(listOf(node.windowId, node.className, node.viewIdResourceName, node.text,
            node.contentDescription, node.isClickable, node.isScrollable, node.isEnabled, node.isPassword, node.isEditable, bounds).joinToString("|"))
    }
    companion object {
        @Volatile internal var connected: PhoneAccessibilityService? = null
            private set
        internal fun response(status: String, message: String): JSONObject = JSONObject().put("status", status).put("message", message)
    }
}
