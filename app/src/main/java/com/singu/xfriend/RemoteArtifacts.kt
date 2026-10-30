package com.singu.xfriend

/** Only workspace result links are offered as owned-resource downloads. */
internal fun remoteArtifactLinks(text: String): List<Pair<String, String>> =
    Regex("\\[([^]\\n]+)]\\(([^)\\n]+)\\)").findAll(text).mapNotNull { match ->
        val label=match.groupValues[1].take(180)
        val raw=match.groupValues[2]
        val path=runCatching {
            if(raw.startsWith("/api/") && raw.contains("/workspace/download?")) {
                java.net.URI(raw).rawQuery.split('&').firstOrNull { it.startsWith("path=") }?.substringAfter('=')?.let { java.net.URLDecoder.decode(it,"UTF-8") }
            } else if(!raw.contains(":") && !raw.startsWith("/") && !raw.contains("..")) raw else null
        }.getOrNull()
        path?.takeIf { it.isNotBlank() && !it.startsWith("/") && !it.contains("..") && !it.contains("\\") }?.let { label to it }
    }.distinct().take(32).toList()
