package com.example.mediapreview

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import com.example.mediapreview.navigation.AppNavigation
import com.example.mediapreview.ui.settings.SettingsViewModel
import com.example.mediapreview.ui.theme.MediaPreviewTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
            MediaPreviewTheme(themeMode = themeMode) {
                AppNavigation()
            }
        }
    }
}
