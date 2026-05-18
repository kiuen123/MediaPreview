package com.example.mediapreview.data

import android.content.Context

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class ThemePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    fun getThemeMode(): ThemeMode {
        val name = prefs.getString(KEY, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        return runCatching { ThemeMode.valueOf(name) }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
    }

    companion object {
        private const val KEY = "theme_mode"
    }
}

