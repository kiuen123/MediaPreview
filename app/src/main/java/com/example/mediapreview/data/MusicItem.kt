package com.example.mediapreview.data

import android.net.Uri

data class MusicItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,     // milliseconds
    val dateAdded: Long,    // seconds since epoch
    val fileSize: Long,
    val albumArtUri: Uri?,
)

