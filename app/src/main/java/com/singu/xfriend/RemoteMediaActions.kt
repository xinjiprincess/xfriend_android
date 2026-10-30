package com.singu.xfriend

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

private fun mediaDirectory(context: Context) = File(context.cacheDir, "remote-media").apply { mkdirs() }
private fun mediaUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".updates", file)

/** User-triggered system camera and audio picker use the ordinary owned upload API. */
@Composable internal fun RemoteMediaActions(
    session: FriendRemoteSession?, resourceId: String, enabled: Boolean,
    onAttachment: (String, JSONObject) -> Unit, onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf("") }
    var cameraResource by rememberSaveable { mutableStateOf("") }
    var cameraScope by rememberSaveable { mutableStateOf("") }
    var audioScope by rememberSaveable { mutableStateOf("") }

    fun upload(uri: Uri, target: Pair<FriendRemoteSession, String>, filename: String? = null, mime: String? = null, cleanup: File? = null) {
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) {
                    var name = filename ?: "recording"
                    if (filename == null) context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) name = cursor.getString(0) ?: name }
                    val temporary = File.createTempFile("upload-", ".bin", mediaDirectory(context))
                    try {
                        context.contentResolver.openInputStream(uri)!!.use { input -> temporary.outputStream().use { output ->
                            val buffer = ByteArray(65536); var length = 0L
                            while (true) { val count = input.read(buffer); if (count < 0) break; length += count; require(length <= 100L * 1024 * 1024) { "附件最多100MiB" }; output.write(buffer, 0, count) }
                        } }
                        FriendRemoteClient().upload(target.first, target.second, temporary, name, mime ?: context.contentResolver.getType(uri) ?: "application/octet-stream")
                    } finally { temporary.delete() }
                }
                onAttachment(target.second, result)
                onNotice("已上传 ${result.getString("filename")}，发送任务后交给电脑处理")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onNotice(e.message ?: "采集附件上传失败") }
            finally { busy = false; cleanup?.delete() }
        }
    }

    val audio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null && session != null && audioScope == friendRemoteSessionScope(session)) upload(uri, session to resourceId) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = cameraPath.takeIf{it.isNotBlank()}?.let(::File)
        val target = session?.takeIf{friendRemoteSessionScope(it)==cameraScope}?.let{it to cameraResource}
        cameraPath = ""; cameraResource = ""; cameraScope = ""
        if (captured && file != null && target != null) upload(mediaUri(context, file), target, file.name, "image/jpeg", file) else file?.delete()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            XfButton("拍照添加", {
                if (session != null) {
                    val file = File.createTempFile("photo-", ".jpg", mediaDirectory(context))
                    cameraPath = file.path; cameraResource = resourceId; cameraScope = friendRemoteSessionScope(session)
                    try { camera.launch(mediaUri(context, file)) }
                    catch (_: android.content.ActivityNotFoundException) { file.delete(); cameraPath = ""; onNotice("此设备没有可用相机应用，可使用添加附件选择图片") }
                }
            }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
            XfButton("上传音频", { session?.let { audioScope=friendRemoteSessionScope(it); audio.launch("audio/*") } }, enabled = enabled && !busy, kind = XfButtonKind.Ghost, small = true)
        }
    }
}

@Composable internal fun RemoteShareResult(session: FriendRemoteSession?, resourceId: String, label: String, path: String, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    XfButton("分享 $label", {
        if (session != null) scope.launch {
            busy = true
            try {
                val file = withContext(Dispatchers.IO) {
                    val filename = label.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[/:\\\\\u0000-\u001f]"), "_").take(120).ifBlank { "result" }
                    File(mediaDirectory(context), java.util.UUID.randomUUID().toString() + "-" + filename).also { FriendRemoteClient().download(session, resourceId, path, it) }
                }
                val intent = Intent(Intent.ACTION_SEND).setType(java.net.URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream").putExtra(Intent.EXTRA_STREAM, mediaUri(context, file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(intent, "分享成果文件"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onNotice(e.message ?: "成果分享失败") }
            finally { busy = false }
        }
    }, enabled = session != null && !busy, kind = XfButtonKind.Ghost, small = true)
}
