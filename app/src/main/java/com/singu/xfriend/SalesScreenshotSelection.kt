package com.singu.xfriend

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 多张沟通截图导入前的排序确认：按对话先后排列，最多 20 张。
 * 只调整顺序与移除，不做任何写入，确认后才交给调用方导入。
 */
@Composable internal fun SalesScreenshotSelection(files: List<Uri>, change: (List<Uri>) -> Unit, confirm: () -> Unit) {
    val colors = xf
    XfDialog(
        title = "整理沟通截图",
        onDismiss = { change(emptyList()) },
        confirmLabel = "导入 ${files.size} 张",
        onConfirm = confirm,
        confirmEnabled = files.size in 1..20,
    ) {
        Text("按照对话先后排列，复盘会按此顺序理解内容。支持长截图，每次最多 20 张。", style = XfType.small, color = colors.ink2)
        LazyColumn(Modifier.heightIn(max = 400.dp)) {
            itemsIndexed(files) { index, uri ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", Modifier.width(28.dp), style = XfType.mono, color = colors.ink3)
                    Text(
                        uri.lastPathSegment?.substringAfterLast('/') ?: "沟通截图",
                        Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = XfType.small,
                        color = colors.ink,
                    )
                    XfIconButton(XfIcons.ArrowUp, "前移", {
                        val next = files.toMutableList()
                        next[index] = next[index - 1]
                        next[index - 1] = uri
                        change(next)
                    }, enabled = index > 0, size = 36.dp)
                    XfIconButton(XfIcons.X, "移除", { change(files.filterIndexed { i, _ -> i != index }) }, size = 36.dp)
                }
            }
        }
    }
}
