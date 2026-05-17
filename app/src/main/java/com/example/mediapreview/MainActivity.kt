package com.example.mediapreview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.mediapreview.navigation.AppNavigation
import com.example.mediapreview.ui.theme.MediaPreviewTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaPreviewTheme {
                AppNavigation()
            }
        }
    }
}
