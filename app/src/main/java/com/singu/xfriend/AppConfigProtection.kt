package com.singu.xfriend

import android.annotation.SuppressLint
import java.io.File
import java.net.URI

/**
 * Execution-layer protection for the app's own configuration.
 *
 * This is deliberately independent of any prompt. Settings screens, credential files, account
 * storage and package management are unreachable through model tools, subagents, workspace paths,
 * accessibility clicks, launched apps or deep links. Normal task tools (items, reminders, files in
 * the user workspace, task-manager visibility) are not configuration changes and stay allowed.
 */
@SuppressLint("SdCardPath")
internal object AppConfigProtection {
    const val RESULT_CODE = "APP_CONFIGURATION_PROTECTED"
    private val productPackages = listOf("com.singu.xfriend.mobile", "com.singu.xsalers.mobile")
    private val configFileNames = setOf(
        "xfriend.json", "xfriend.json.bak", "xfriend-encrypted-prefs.json",
        "shared_prefs", "databases", "no_backup",
    )

    /** Current runtime package plus both flavours with debug/qa suffixes, including fixtures. */
    fun protectedPackages(currentPackage: String): Set<String> {
        val bases = productPackages + currentPackage.takeIf { it.isNotBlank() }.orEmpty()
        return bases.flatMap { base ->
            val clean = base.removeSuffix(".debug").removeSuffix(".qa")
            listOf(clean, "$clean.debug", "$clean.qa", "$clean.debug.qa")
        }.toSet()
    }

    fun isSelfPackage(currentPackage: String, targetPackage: String): Boolean =
        targetPackage.isNotBlank() && targetPackage in protectedPackages(currentPackage)

    fun isProtectedSystemPackage(targetPackage: String): Boolean {
        val lower = targetPackage.lowercase()
        return PhonePolicy.protectedPackage(lower) ||
            lower.contains("packageinstaller") || lower.contains("permissioncontroller") ||
            lower.contains("settings") || lower.contains("com.android.vending") ||
            lower.contains("market")
    }

    /** Self screens may not be read or clicked by the agent at all; owner uses them manually. */
    fun isProtectedScreen(currentPackage: String, targetPackage: String): Boolean =
        isSelfPackage(currentPackage, targetPackage) || isProtectedSystemPackage(targetPackage)

    fun isProtectedUri(currentPackage: String, raw: String): Boolean {
        val text = raw.trim()
        if (text.isBlank()) return false
        if (text.startsWith("package:")) return isSelfPackage(currentPackage, text.removePrefix("package:").substringBefore('/'))
        val scheme = runCatching { URI(text).scheme?.lowercase() }.getOrNull()
        if (scheme in setOf("intent", "android-app")) return true
        if (scheme == "file") {
            val path = runCatching { URI(text).path.orEmpty() }.getOrDefault("")
            return path.contains("xfriend.json") || path.contains("xfriend-encrypted-prefs") ||
                path.contains("/data/data/") || path.contains("/data/user/")
        }
        return false
    }

    /** Workspace writes must never reach Store, credentials, the run ledger or phone storage. */
    fun isProtectedAgentPath(root: File, target: File): Boolean {
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { return true }
        val canonical = runCatching { target.canonicalFile }.getOrElse { return true }
        if (!canonical.toPath().startsWith(canonicalRoot.toPath())) return true
        val relative = canonicalRoot.toPath().relativize(canonical.toPath()).toString().replace('\\', '/')
        val segments = relative.split('/')
        if (segments.any { it in configFileNames }) return true
        if (relative.contains("xfriend-encrypted-prefs")) return true
        return false
    }

    fun rejection(): String =
        "APP_CONFIGURATION_PROTECTED：这是应用自身配置，agent 不能读取或修改；请由你本人在设置页手动操作。"

    fun prompt(): String =
        "应用自身配置只能由用户手动修改。不要尝试通过工具、子代理、文件路径、Intent/深链接、" +
            "无障碍点击或打开系统设置来更改供应商、密钥、账号/激活、权限开关、默认策略、专家/技能或授权存储。"
}
