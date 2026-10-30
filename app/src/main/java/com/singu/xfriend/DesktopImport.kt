package com.singu.xfriend

/** The native branch intentionally cannot be flattened into DesktopBundle (which loses metadata). */
internal sealed interface DesktopImportDocument {
    class Native(val pack: PortablePack) : DesktopImportDocument
    class Legacy(val bundle: DesktopBundle) : DesktopImportDocument
}

internal fun parseDesktopImport(bytes: ByteArray, password: String = ""): DesktopImportDocument {
    require(bytes.size <= PortablePackCodec.MAX_ARCHIVE) { "导入包过大" }
    val json = bytes.firstOrNull { it.toInt().toChar() !in " \r\n\t" } == '{'.code.toByte()
    return if (json) {
        require(bytes.size <= 4 * 1024 * 1024) { "旧版导入 JSON 不能超过 4 MiB" }
        DesktopImportDocument.Legacy(DesktopBundle.parse(portableUtf8(bytes)))
    } else DesktopImportDocument.Native(PortablePackCodec.decode(bytes, password))
}

internal data class ResourceSnapshot(
    val experts: List<Expert>, val teams: List<Team>,
    val skills: List<Skill>, val knowledge: List<Knowledge>,
)

/** Both AtomicFile writes must finish before either UI reports an import success. */
internal fun commitDesktopBundle(
    old: SalesData, bundle: DesktopBundle, secrets: Boolean, resources: ResourceSnapshot,
    apply: (ResourceSnapshot) -> Unit, saveResources: () -> Unit, saveSales: (SalesData) -> Unit,
): SalesData {
    val incoming = bundle.profiles.map { p ->
        if (secrets) p else p.copy(key = old.profiles.find { it.id == p.id }?.key.orEmpty())
    }
    val next = old.copy(profiles = old.profiles.filterNot { p -> incoming.any { it.id == p.id } } + incoming)
    val merged = ResourceSnapshot(
        resources.experts.filterNot { e -> bundle.experts.any { it.id == e.id } } + bundle.experts,
        resources.teams.filterNot { e -> bundle.teams.any { it.id == e.id } } + bundle.teams,
        resources.skills.filterNot { e -> bundle.skills.any { it.id == e.id } } + bundle.skills,
        resources.knowledge.filterNot { e -> bundle.knowledge.any { it.id == e.id } } + bundle.knowledge,
    )
    saveSales(next)
    try {
        apply(merged)
        saveResources()
    } catch (failure: Exception) {
        apply(resources)
        try { saveSales(old) } catch (rollback: Exception) { failure.addSuppressed(rollback) }
        throw failure
    }
    return next
}
