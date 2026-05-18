package com.example.mediapreview.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mediapreview.data.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    var showThemeDialog by remember { mutableStateOf(false) }

    if (showThemeDialog) {
        ThemeModeDialog(
            current = themeMode,
            onSelect = { viewModel.setThemeMode(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                    }
                },
                title = {
                    Text("Cài đặt", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ── Giao diện ───────────────────────────────────────────────────
            item {
                SettingsSectionHeader("Giao diện")
            }
            item {
                val themeName = when (themeMode) {
                    ThemeMode.SYSTEM -> "Theo hệ thống"
                    ThemeMode.LIGHT  -> "Sáng"
                    ThemeMode.DARK   -> "Tối"
                }
                SettingsRow(
                    icon = Icons.Default.Brightness6,
                    title = "Chủ đề",
                    subtitle = themeName,
                    onClick = { showThemeDialog = true }
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            // ── Thông tin ────────────────────────────────────────────────────
            item {
                SettingsSectionHeader("Thông tin")
            }
            item {
                SettingsRow(
                    icon = Icons.Default.Info,
                    title = "Phiên bản",
                    subtitle = "1.0.0",
                    onClick = {}
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Default.Security,
                    title = "Bảo mật",
                    subtitle = "Khóa thư mục bằng mật khẩu — giữ lâu vào thư mục trong tab Album",
                    onClick = {}
                )
            }
        }
    }
}

// ── Section header ──────────────────────────────────────────────────────────

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

// ── Settings row ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

// ── Theme mode dialog ────────────────────────────────────────────────────────

@Composable
private fun ThemeModeDialog(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Brightness6, contentDescription = null) },
        title = { Text("Chủ đề") },
        text = {
            Column {
                ThemeModeOption("Theo hệ thống", ThemeMode.SYSTEM, current, onSelect)
                ThemeModeOption("Sáng", ThemeMode.LIGHT, current, onSelect)
                ThemeModeOption("Tối", ThemeMode.DARK, current, onSelect)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}

@Composable
private fun ThemeModeOption(
    label: String,
    mode: ThemeMode,
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(mode) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = current == mode, onClick = { onSelect(mode) })
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

