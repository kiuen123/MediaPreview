package com.example.mediapreview.ui.viewer

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.example.mediapreview.data.GalleryItem
import com.example.mediapreview.ui.gallery.GalleryEvent
import com.example.mediapreview.ui.gallery.GalleryViewModel
import com.example.mediapreview.ui.gallery.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

@androidx.annotation.OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaPagerScreen(
    initialIndex: Int,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = state.viewerItems
    if (items.isEmpty()) { onBack(); return }

    val safeIndex = initialIndex.coerceIn(0, items.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeIndex) { items.size }

    val context = LocalContext.current
    var barsVisible by remember { mutableStateOf(true) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var currentScale by remember { mutableFloatStateOf(1f) }

    // Delete launcher
    var pendingDeleteItem by remember { mutableStateOf<GalleryItem?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDeleteItem?.let { item ->
                viewModel.onDeleteCompleted(setOf(item.id))
            }
            pendingDeleteItem = null
        }
    }

    // Auto-navigate to adjacent when current item is deleted
    LaunchedEffect(items.size) {
        if (items.isEmpty()) onBack()
        else if (pagerState.currentPage >= items.size) {
            pagerState.scrollToPage(items.lastIndex)
        }
    }

    val currentItem = items.getOrNull(pagerState.currentPage) ?: return

    // Collect share/delete events emitted from ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.RequestShare -> {
                    val uris = event.items.map { it.uri }
                    val intent = Intent().apply {
                        action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
                        type = "*/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (uris.size == 1) {
                            putExtra(Intent.EXTRA_STREAM, uris[0])
                            clipData = ClipData.newRawUri(null, uris[0])
                        } else {
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            clipData = ClipData.newRawUri(null, uris[0]).also { cd ->
                                uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
                            }
                        }
                    }
                    context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
                }
                is GalleryEvent.RequestDelete -> {
                    pendingDeleteItem = event.items.firstOrNull()
                    val uris = event.items.map { it.uri }
                    val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Pager ──────────────────────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = currentScale <= 1.01f,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
            val item = items.getOrNull(page) ?: return@HorizontalPager
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val fraction = pageOffset.coerceIn(0f, 1f)
                        scaleX = 1f - fraction * 0.08f
                        scaleY = 1f - fraction * 0.08f
                        alpha  = 1f - fraction * 0.40f
                    }
            ) {
                if (item.isVideo) {
                    if (page == pagerState.settledPage) {
                        VideoPage(uri = item.uri, onTap = { barsVisible = !barsVisible })
                    } else {
                        // Thumbnail for non-current video pages
                        Box(
                            Modifier.fillMaxSize().background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = item.uri, contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            Icon(
                                Icons.Default.PlayCircle, null,
                                tint = Color.White.copy(0.7f),
                                modifier = Modifier.size(72.dp)
                            )
                        }
                    }
                } else {
                    ImagePage(
                        item = item,
                        isCurrentPage = page == pagerState.currentPage,
                        onScaleChanged = { if (page == pagerState.currentPage) currentScale = it },
                        onTap = { barsVisible = !barsVisible }
                    )
                }
            }
        }

        // ── Top bar ────────────────────────────────────────────────────────
        AnimatedVisibility(
            visible = barsVisible,
            enter = slideInVertically { -it } + fadeIn(animationSpec = tween(200)),
            exit  = slideOutVertically { -it } + fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", tint = Color.White)
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${items.size}",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
                Row(modifier = Modifier.align(Alignment.CenterEnd)) {
                    val isFav = currentItem.id in state.favoriteIds
                    IconButton(onClick = { viewModel.toggleFavorite(currentItem.id) }) {
                        Icon(
                            if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            "Yêu thích",
                            tint = if (isFav) Color(0xFFFF4081) else Color.White
                        )
                    }
                    IconButton(onClick = { viewModel.requestShareItem(currentItem) }) {
                        Icon(Icons.Default.Share, "Chia sẻ", tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.requestDeleteItem(currentItem) }) {
                        Icon(Icons.Default.Delete, "Xóa", tint = Color.White)
                    }
                    IconButton(onClick = { showInfoSheet = true }) {
                        Icon(Icons.Default.Info, "Thông tin", tint = Color.White)
                    }
                }
            }
        }

        // ── Bottom caption bar ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = barsVisible,
            enter = slideInVertically { it } + fadeIn(animationSpec = tween(200)),
            exit  = slideOutVertically { it } + fadeOut(animationSpec = tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Column {
                    if (currentItem.name.isNotBlank()) {
                        Text(
                            text = currentItem.name,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = formatDateFull(currentItem.effectiveTimeMs),
                        color = Color.White.copy(0.75f),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    // ── Info bottom sheet ──────────────────────────────────────────────────
    if (showInfoSheet) {
        MediaInfoSheet(
            item = currentItem,
            isFavorite = currentItem.id in state.favoriteIds,
            onDismiss = { showInfoSheet = false },
            onToggleFavorite = { viewModel.toggleFavorite(currentItem.id) }
        )
    }
}

// ── Image page with pinch zoom + pan + double tap ─────────────────────────
// NOTE: We intentionally do NOT use `transformable` here because it consumes
// all pointer events (including single-touch horizontal swipes), which would
// prevent the parent HorizontalPager from handling swipe-to-navigate.
// Instead we use a custom gesture handler that:
//  • Multi-touch  → zoom + pan (events consumed)
//  • Single-touch + scale > 1 → pan image (events consumed, pager disabled)
//  • Single-touch + scale == 1 → events NOT consumed → pager scrolls (swipe nav)

@Composable
private fun ImagePage(
    item: GalleryItem,
    isCurrentPage: Boolean,
    onScaleChanged: (Float) -> Unit,
    onTap: () -> Unit
) {
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(scale) {
        if (isCurrentPage) onScaleChanged(scale)
        if (scale <= 1f) offset = Offset.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(item.id) {
                coroutineScope {
                    // ── Tap / double-tap ──────────────────────────────────
                    launch {
                        detectTapGestures(
                            onTap = { onTap() },
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    // ── Zoom + pan gesture ────────────────────────────────
                    launch {
                        awaitEachGesture {
                            // awaitEachGesture starts the block at the initial DOWN event;
                            // the first awaitPointerEvent() below returns that DOWN event.
                            var pressedCount: Int
                            do {
                                val event = awaitPointerEvent()
                                pressedCount = event.changes.count { it.pressed }
                                when {
                                    pressedCount >= 2 -> {
                                        // Pinch-to-zoom + pan
                                        val newScale =
                                            (scale * event.calculateZoom()).coerceIn(1f, 8f)
                                        scale = newScale
                                        if (newScale > 1f) offset += event.calculatePan()
                                        event.changes.forEach { it.consume() }
                                    }
                                    pressedCount == 1 && scale > 1f -> {
                                        // Single-touch pan while zoomed in
                                        offset += event.calculatePan()
                                        event.changes.forEach { it.consume() }
                                    }
                                    // scale == 1 + single touch → don't consume
                                    // → HorizontalPager receives it → swipe navigation ✓
                                }
                            } while (pressedCount > 0)
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

// ── Video page ─────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(uri: android.net.Uri, @Suppress("UNUSED_PARAMETER") onTap: () -> Unit) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { exoPlayer.stop(); exoPlayer.release() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── Info bottom sheet ──────────────────────────────────────────────────────

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaInfoSheet(
    item: GalleryItem,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Thông tin tệp",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            InfoRow(Icons.AutoMirrored.Filled.InsertDriveFile, "Tên tệp", item.name)
            InfoRow(Icons.Default.CalendarToday, "Ngày chụp", formatDateFull(item.effectiveTimeMs))
            if (item.fileSize > 0) {
                InfoRow(Icons.Default.Storage, "Kích thước", formatFileSize(item.fileSize))
            }
            if (item.folderName.isNotBlank()) {
                InfoRow(Icons.Default.Folder, "Thư mục", item.folderName)
            }
            if (item.isVideo && item.duration > 0) {
                InfoRow(Icons.Default.Timer, "Thời lượng", formatDuration(item.duration))
            }
            InfoRow(Icons.Default.Image, "Loại tệp", item.mimeType)

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Favorite toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
                        tint = if (isFavorite) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Yêu thích")
                }
                Switch(checked = isFavorite, onCheckedChange = { onToggleFavorite() })
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp).padding(top = 1.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── Utility functions ──────────────────────────────────────────────────────

private fun formatDateFull(ms: Long): String =
    SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000     -> "%.1f KB".format(bytes / 1_000.0)
    else               -> "$bytes B"
}













