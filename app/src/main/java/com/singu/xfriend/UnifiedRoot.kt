package com.singu.xfriend

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext

internal enum class AppMode(val preference: String) {
    PHONE("phone"), DESKTOP("desktop");

    companion object {
        fun restore(value: String?): AppMode = entries.firstOrNull { it.preference == value } ?: PHONE
    }
}

@Composable internal fun UnifiedRoot() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("app_mode", 0) }
    var selected by rememberSaveable { mutableStateOf(AppMode.restore(preferences.getString("mode", null)).preference) }
    fun select(mode: AppMode) {
        preferences.edit().putString("mode", mode.preference).apply()
        selected = mode.preference
    }
    when (AppMode.restore(selected)) {
        AppMode.PHONE -> AccountRoot(onConnectDesktop = { select(AppMode.DESKTOP) })
        AppMode.DESKTOP -> CompanionScreen(onUsePhone = { select(AppMode.PHONE) })
    }
}
