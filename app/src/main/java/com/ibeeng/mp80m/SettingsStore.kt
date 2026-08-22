package com.ibeeng.mp80m

import android.content.Context

/** Persisted print preferences (mirrors the daemon's Settings). */
data class PrintSettings(
    val trim: Boolean = true,
    val trimMargin: Int = 16,
    val lastMac: String? = null,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mp80m", Context.MODE_PRIVATE)

    fun load(): PrintSettings = PrintSettings(
        trim = prefs.getBoolean("trim", true),
        trimMargin = prefs.getInt("trim_margin", 16),
        lastMac = prefs.getString("last_mac", null),
    )

    fun save(s: PrintSettings) {
        prefs.edit()
            .putBoolean("trim", s.trim)
            .putInt("trim_margin", s.trimMargin)
            .putString("last_mac", s.lastMac)
            .apply()
    }
}
