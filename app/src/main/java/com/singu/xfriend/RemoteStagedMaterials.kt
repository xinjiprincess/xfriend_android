package com.singu.xfriend

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Upload stages an owned original. Only an explicit later send grants it to a room/task. */
@Composable internal fun RemoteStagedMaterials(
    session: FriendRemoteSession?, materials: List<RemoteMaterial>, enabled: Boolean,
    onBusyChange: (Boolean) -> Unit = {},
    onChange: (List<RemoteMaterial>) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val currentMaterials by rememberUpdatedState(materials)
    val busyListener by rememberUpdatedState(onBusyChange)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val paired = session
        if (uri != null && paired != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                busy = true
                busyListener(true)
                try {
                    val material = withContext(Dispatchers.IO) {
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "material"
                        val temporary = File.createTempFile("staged-material-", ".upload", context.cacheDir)
                        try {
                            (context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")).use { input ->
                                temporary.outputStream().use { copyRemoteFile(input, it) }
                            }
                            RemoteMaterial.parse(FriendRemoteClient().uploadMaterial(paired, temporary, name,
                                context.contentResolver.getType(uri) ?: "application/octet-stream"))
                        } finally { temporary.delete() }
                    }
                    onChange((currentMaterials + material).distinctBy { it.id })
                    error = ""
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = if (failure is FriendRemoteFailure) failure.message.orEmpty() else "资料未上传完成，请检查文件或连接后重新选择" }
                finally { busy = false; busyListener(false) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        XfButton(if (busy) "正在上传资料…" else "添加群文件 / 资料", { picker.launch(arrayOf("*/*")) },
            enabled = enabled && !busy && materials.size < 20, kind = XfButtonKind.Secondary, small = true)
        if (materials.isNotEmpty()) Text("原件已暂存；明确发送后才共享给本次房间或任务。", style = XfType.meta)
        materials.forEach { material ->
            Text("${material.filename} · ${material.size} 字节 · ${material.readiness}", style = XfType.small)
            XfButton("移出本次发送", { onChange(currentMaterials.filterNot { it.id == material.id }) },
                enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
        }
        if (error.isNotBlank()) Text(error, style = XfType.meta)
    }
}
