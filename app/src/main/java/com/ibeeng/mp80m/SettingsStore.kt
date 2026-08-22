package com.ibeeng.mp80m

import android.content.Context
import org.json.JSONArray

/** Persisted print preferences (mirrors the daemon's Settings). */
data class PrintSettings(
    val trim: Boolean = true,
    val trimMargin: Int = 16,
    val lastMac: String? = null,
    val autoReconnect: Boolean = true,
    val copies: Int = 1,
)

/** A saved text template (label/receipt) the user reuses. */
data class Template(val name: String, val body: String)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("mp80m", Context.MODE_PRIVATE)

    fun load(): PrintSettings = PrintSettings(
        trim = prefs.getBoolean("trim", true),
        trimMargin = prefs.getInt("trim_margin", 16),
        lastMac = prefs.getString("last_mac", null),
        autoReconnect = prefs.getBoolean("auto_reconnect", true),
        copies = prefs.getInt("copies", 1).coerceIn(1, 20),
    )

    fun save(s: PrintSettings) {
        prefs.edit()
            .putBoolean("trim", s.trim)
            .putInt("trim_margin", s.trimMargin)
            .putString("last_mac", s.lastMac)
            .putBoolean("auto_reconnect", s.autoReconnect)
            .putInt("copies", s.copies.coerceIn(1, 20))
            .apply()
    }

    // --- Templates (stored as JSON array in prefs) ---

    fun loadTemplates(): List<Template> {
        val raw = prefs.getString("templates", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Template(o.getString("name"), o.getString("body"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTemplates(list: List<Template>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(org.json.JSONObject().put("name", it.name).put("body", it.body))
        }
        prefs.edit().putString("templates", arr.toString()).apply()
    }

    fun addTemplate(t: Template) {
        val list = loadTemplates().toMutableList()
        // replace by name if exists
        val idx = list.indexOfFirst { it.name == t.name }
        if (idx >= 0) list[idx] = t else list.add(t)
        saveTemplates(list)
    }

    fun removeTemplate(name: String) {
        saveTemplates(loadTemplates().filterNot { it.name == name })
    }
}
