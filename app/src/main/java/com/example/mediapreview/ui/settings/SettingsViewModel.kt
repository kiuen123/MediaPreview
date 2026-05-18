package com.example.mediapreview.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.mediapreview.data.ThemeMode
import com.example.mediapreview.data.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = ThemePreferences(application)

    private val _themeMode = MutableStateFlow(prefs.getThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun setThemeMode(mode: ThemeMode) {
        prefs.setThemeMode(mode)
        _themeMode.update { mode }
    }
}

