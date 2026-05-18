package com.example.mediapreview.ui.viewer

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageViewerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var isPortrait by remember { mutableStateOf(true) }

    // Detect image dimensions on IO thread
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                if (options.outWidth > 0 && options.outHeight > 0) {
                    isPortrait = options.outHeight >= options.outWidth
                }
            } catch (_: Exception) {}
        }
    }

    // Lock screen orientation based on image orientation
    LaunchedEffect(isPortrait) {
        val activity = context as? Activity
        activity?.requestedOrientation = if (isPortrait)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        else
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    // Restore orientation when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Reset offset when scale goes to 1
    LaunchedEffect(scale) {
        if (scale <= 1f) { scale = 1f; offset = Offset.Zero }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(uri) {
                coroutineScope {
                    launch {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                                else scale = 2.5f
                            }
                        )
                    }
                    launch {
                        awaitEachGesture {
                            var pressedCount: Int
                            do {
                                val event = awaitPointerEvent()
                                pressedCount = event.changes.count { it.pressed }
                                when {
                                    pressedCount >= 2 -> {
                                        val newScale = (scale * event.calculateZoom()).coerceIn(1f, 10f)
                                        scale = newScale
                                        if (newScale > 1f) offset += event.calculatePan()
                                        event.changes.forEach { it.consume() }
                                    }
                                    pressedCount == 1 && scale > 1f -> {
                                        offset += event.calculatePan()
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (pressedCount > 0)
                        }
                    }
                }
            }
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
        )

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }
    }
}
