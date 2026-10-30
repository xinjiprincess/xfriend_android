package com.singu.xfriend

import org.json.JSONObject

data class PortableCapabilityNotice(val resourceRef: String, val capability: String, val reason: String)

/** Read-only installation input. Caller owns one account-scoped atomic transaction, including
 * all metadata/assets and separately encrypted credentials. Never turn asset files into commands.
 * A false trustSkills leaves imported skill instructions disabled as well as scripts inert.
 */
class PortableInstallPlan internal constructor(
    private val pack: PortablePack,
    val includeSecrets: Boolean,
    val trustSkills: Boolean,
    val notices: List<PortableCapabilityNotice>,
) {
    fun manifest(): JSONObject = pack.manifest()
    fun files(): Map<String, ByteArray> = pack.files()
    fun secrets(): Map<String, String> = if (includeSecrets) pack.secrets() else emptyMap()
}

fun preparePortableInstall(pack: PortablePack, includeSecrets: Boolean = false, trustSkills: Boolean = false): PortableInstallPlan {
    val notices = mutableListOf<PortableCapabilityNotice>()
    val experts = pack.manifest().packArray("experts")
    pack.manifest().packArray("providers").objects().forEach { p ->
        if (p.packString("family", "text") != "text" || p.getString("kind") != "openai") notices.add(PortableCapabilityNotice(p.getString("ref"), "provider:${p.getString("kind")}", "提供商配置完整保留；本机需要显式适配，否则仅可远程使用"))
        if (p.optString("base_url").startsWith("http:")) notices.add(PortableCapabilityNotice(p.getString("ref"), "provider_http", "配置完整保留；手机运行时要求 HTTPS 地址"))
    }
    repeat(experts.length()) { i ->
        val e = experts.getJSONObject(i); val ref = e.getString("ref")
        if (e.packString("engine", "native") != "native") notices.add(PortableCapabilityNotice(ref, "engine:dsh", "仅支持远程桌面引擎"))
        e.packArray("dependencies").packStrings().forEach { notices.add(PortableCapabilityNotice(ref, "dependency:$it", "依赖已保留；需要宿主验证或远程能力，不自动下载或执行")) }
        val files = e.packObject("files")
        if (files.packKeys().any { it.startsWith("skills/") }) notices.add(PortableCapabilityNotice(ref, "skills", if (trustSkills) "仅可接入已信任的技能文本；脚本需远程工具" else "技能未获信任，保持禁用；资产原样保留"))
        if (files.has("TOOLS.md")) notices.add(PortableCapabilityNotice(ref, "desktop_tools", "桌面工具声明仅作为元数据保留；手机不会据此新增执行能力"))
        if (e.packObject("preferences").length() > 0 || e.has("model") || e.packObject("member_models").length() > 0) notices.add(PortableCapabilityNotice(ref, "runtime_configuration", "模型及运行偏好完整保留；宿主需显式适配后启用"))
    }
    return PortableInstallPlan(pack, includeSecrets, trustSkills, notices.toList())
}

/** The application supplies the account-scoped repository; core never selects another account. */
fun interface PortableInstallRepository {
    suspend fun commit(plan: PortableInstallPlan)
}
