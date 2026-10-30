package com.singu.xfriend

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fetches only the paired resource avatar route; never follows arbitrary icon URLs. */
@Composable internal fun RemoteExpertAvatar(session: FriendRemoteSession?, resourceId: String, name: String, hasAvatar: Boolean, team: Boolean = false) {
    var bitmap by remember(session?.origin, session?.cookie, resourceId, hasAvatar) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(session?.origin, session?.cookie, resourceId, hasAvatar) {
        val current = session ?: return@LaunchedEffect
        if (!hasAvatar || resourceId.isBlank()) return@LaunchedEffect
        try {
            bitmap = withContext(Dispatchers.IO) {
                val bytes = FriendRemoteClient().avatar(current, resourceId)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 256 || bounds.outHeight / inSampleSize > 256) inSampleSize *= 2
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { bitmap = null }
    }
    val image = bitmap
    if (image != null) Image(image, contentDescription = name, modifier = Modifier.size(XfDimens.avatarSmall).clip(CircleShape))
    else XfIconTile(icon = if (team) XfIcons.Users else XfIcons.Brand, size = XfDimens.avatarSmall)
}
