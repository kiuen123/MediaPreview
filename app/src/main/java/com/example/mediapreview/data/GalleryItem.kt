package com.example.mediapreview.data

import android.net.Uri

data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val dateAdded: Long,        // seconds since epoch
    val mimeType: String,
    val duration: Long = 0L,
    val folderName: String = "",
    val dateTaken: Long = 0L,   // milliseconds since epoch (0 = unknown)
    val fileSize: Long = 0L,    // bytes
    val width: Int = 0,
    val height: Int = 0,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    /** Best available timestamp in milliseconds for sorting/grouping. */
    val effectiveTimeMs: Long get() = if (dateTaken > 0) dateTaken else dateAdded * 1000L

    val hasResolution: Boolean get() = width > 0 && height > 0
    val resolution: String get() = "$width × $height"
}
