package com.singu.xfriend

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.util.Rational
import kotlinx.coroutines.flow.MutableSharedFlow

/** Only an explicit current-user request (or starting a recording) may enter the small window. */
internal object SmallWindowIntent {
    private val pattern = Regex("^(?:请|帮我|麻烦|给我)?\\s*(?:打开|进入|切换|使用)?\\s*(?:小窗模式|小窗|悬浮窗|画中画)(?:模式)?$")
    fun matches(input: String): Boolean {
        val text = input.trim().trimEnd('。', '！', '!', '？', '?', '，', ',', '吧', ' ', '\n')
        if (text.isEmpty() || text.length > 24) return false
        if (text.startsWith("他说") || text.startsWith("她说") || text.startsWith("引用") ||
            text.startsWith("网页") || text.startsWith("工具") || text.startsWith("之前")) return false
        return pattern.matches(text)
    }
}

internal object SmallWindowController {
    /** Consumed by MainActivity on the main thread. Buffer 1 is enough; duplicate taps coalesce. */
    val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun request() { requests.tryEmit(Unit) }

    fun supported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enter(activity: Activity): Boolean {
        if (!supported(activity)) return false
        return runCatching {
            activity.enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build(),
            )
        }.getOrDefault(false)
    }
}
