package com.singu.xfriend

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Canonical diagram read/preview/export only. Editing remains in the desktop workspace. */
@Composable internal fun RemoteDiagramPreview(session: FriendRemoteSession?, resources: List<FriendRemoteResource>, accountScope: String, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    var resource by remember(accountScope) { mutableStateOf("") }
    var path by remember(accountScope) { mutableStateOf("") }
    var saved by remember(accountScope) { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember(accountScope) { mutableStateOf("") }
    var export by remember(accountScope) { mutableStateOf<Triple<String, JSONObject, String>?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val current = session
        val selection = export
        if (uri != null && current != null && selection != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val temporary = File.createTempFile("xf-diagram-", ".${selection.third}", context.cacheDir)
                    try {
                        FriendRemoteClient().diagramExport(current, selection.first, selection.second, selection.third, temporary)
                        context.contentResolver.openOutputStream(uri)?.use { output -> temporary.inputStream().use { it.copyTo(output) } }
                            ?: error("无法写入所选位置")
                    } finally { temporary.delete() }
                }
                notice = "流程图已保存"
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { notice = failure.message ?: "流程图导出失败" }
            finally { busy = false; export = null }
        }
    }
    XfCard {
        XfSectionHeader(title = "电脑流程图预览", subtitle = "读取专家工作区的 .xfdiagram.json；保留图与源码，编辑和保存请回到电脑。")
        resources.filter { it.kind == "expert" || it.kind == "agent" }.forEach { item ->
            XfButton("${if (resource == item.id) "● " else "○ "}${item.name}", { resource = item.id; saved = null }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
        }
        XfTextField(path, { path = it; saved = null }, label = "工作区相对路径（.xfdiagram.json）", singleLine = true)
        XfButton("读取并预览", {
            val current = session ?: return@XfButton
            scope.launch {
                busy = true
                try { saved = withContext(Dispatchers.IO) { FriendRemoteClient().diagram(current, resource, path) }; notice = "" }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { saved = null; notice = failure.message ?: "流程图不可读，请检查路径和权限" }
                finally { busy = false }
            }
        }, enabled = enabled && !busy && resource.isNotBlank() && path.endsWith(".xfdiagram.json"), kind = XfButtonKind.Secondary, small = true)
        saved?.optJSONObject("document")?.let { document ->
            Text("版本 ${document.optInt("revision")} · ${document.optString("mode")} · ${saved?.optString("digest")?.take(12)}", style = XfType.meta)
            val graph = document.optJSONObject("graph")
            if (graph != null) RemoteDiagramGraph(graph)
            if (document.optString("warning").isNotBlank()) Text(document.optString("warning"), style = XfType.meta)
            Text(document.optString("source"), modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()), style = XfType.small)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (if (graph == null) listOf("mmd") else listOf("mmd", "svg", "png", "pdf")).forEach { format ->
                    XfButton(format.uppercase(), { export = Triple(resource, document, format); saveAs.launch("diagram.$format") }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
                }
            }
        }
        if (notice.isNotBlank()) Text(notice, style = XfType.meta)
    }
}

@Composable private fun RemoteDiagramGraph(graph: JSONObject) {
    val nodes = graph.optJSONArray("nodes") ?: JSONArray()
    val edges = graph.optJSONArray("edges") ?: JSONArray()
    val rows = (0 until nodes.length()).map { nodes.getJSONObject(it) }
    val byId = rows.associateBy { it.optString("id") }
    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
        if (rows.isEmpty()) return@Canvas
        val left = rows.minOf { it.optDouble("x").toFloat() }
        val top = rows.minOf { it.optDouble("y").toFloat() }
        val width = rows.maxOf { it.optDouble("x").toFloat() } - left + 180f
        val height = rows.maxOf { it.optDouble("y").toFloat() } - top + 76f
        val scale = minOf((size.width - 16f) / width, (size.height - 16f) / height)
        fun point(node: JSONObject) = Offset(8f + (node.optDouble("x").toFloat() - left) * scale, 8f + (node.optDouble("y").toFloat() - top) * scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(35, 45, 55); textSize = 13f * scale }
        for (index in 0 until edges.length()) {
            val edge = edges.getJSONObject(index)
            val source = byId[edge.optString("source")] ?: continue
            val target = byId[edge.optString("target")] ?: continue
            val a = point(source) + Offset(90f * scale, 38f * scale)
            val b = point(target) + Offset(90f * scale, 38f * scale)
            drawLine(Color(0xFF6B7C90), a, b, strokeWidth = 2f)
            val angle = kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            for (offset in listOf(-0.45, 0.45)) drawLine(Color(0xFF6B7C90), b, Offset(b.x - (12 * kotlin.math.cos(angle + offset)).toFloat(), b.y - (12 * kotlin.math.sin(angle + offset)).toFloat()), strokeWidth = 2f)
            drawContext.canvas.nativeCanvas.drawText(edge.optString("label").take(40), (a.x + b.x) / 2, (a.y + b.y) / 2, paint)
        }
        rows.forEach { node ->
            val origin = point(node)
            drawRect(Color(0xFFE8EFF8), origin, Size(180f * scale, 76f * scale))
            node.optString("label").chunked(18).take(3).forEachIndexed { line, text ->
                drawContext.canvas.nativeCanvas.drawText(text, origin.x + 8f * scale, origin.y + (20f + line * 17f) * scale, paint)
            }
        }
    }
}
