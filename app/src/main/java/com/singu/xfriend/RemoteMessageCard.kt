package com.singu.xfriend

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.security.MessageDigest

internal fun remoteScopeKey(scope: String): String =
    MessageDigest.getInstance("SHA-256").digest(scope.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Shared transcript row for native desktop meeting and room messages. */
@Composable internal fun RemoteMessageCard(
    name: String, role: String, content: String, round: String = "", stage: String = "",
    publicReasoning: String = "", readiness: String = "ready",
) {
    var showMessage by remember(name, round, content) { mutableStateOf(false) }
    var showReasoning by remember(name, round, content) { mutableStateOf(false) }
    XfCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            XfIconTile(icon = if (role == "user") XfIcons.User else XfIcons.Brand, size = XfDimens.avatarSmall)
            Column(Modifier.weight(1f)) {
                Text(name.ifBlank { role.ifBlank { "电脑参与者" } }, style = XfType.bodyStrong)
                val meta = listOfNotNull(round.takeIf(String::isNotBlank), stage.takeIf(String::isNotBlank), role.takeIf(String::isNotBlank)).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = XfType.meta)
            }
            if (readiness in setOf("queued", "starting")) {
                XfStarMark(size = 20.dp)
                Text(if (readiness == "queued") "排队" else "准备中", style = XfType.meta)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (content.isNotBlank()) {
            Text(if (showMessage || content.length <= 180) content else content.take(180) + "…", style = XfType.body)
            TextButton(onClick = { showMessage = !showMessage }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showMessage) "收起消息" else "展开消息")
            }
        }
        if (publicReasoning.isNotBlank()) {
            TextButton(onClick = { showReasoning = !showReasoning }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showReasoning) "收起公开思考" else "展开公开思考")
            }
            if (showReasoning) Text(publicReasoning, style = XfType.small)
        }
    }
}
