package com.example.mediapreview.data

import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    suspend fun loadAllMedia(): List<GalleryItem> = withContext(Dispatchers.IO) {
        (queryImages() + queryVideos()).sortedByDescending { it.effectiveTimeMs }
    }

    suspend fun loadImages(): List<GalleryItem> = withContext(Dispatchers.IO) {
        queryImages().sortedByDescending { it.effectiveTimeMs }
    }

    suspend fun loadVideos(): List<GalleryItem> = withContext(Dispatchers.IO) {
        queryVideos().sortedByDescending { it.effectiveTimeMs }
    }

    /** Load only trashed (soft-deleted) media items. */
    suspend fun loadTrashedMedia(): List<GalleryItem> = withContext(Dispatchers.IO) {
        (queryImages(trashed = true) + queryVideos(trashed = true)).sortedByDescending { it.effectiveTimeMs }
    }

    // ── Internal query helpers ────────────────────────────────────────────

    private fun queryImages(trashed: Boolean = false): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val queryArgs = if (trashed) Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        } else null

        val cursor = if (queryArgs != null) {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, queryArgs, null
            )
        } else {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )
        }
        cursor?.use { c ->
            val idCol     = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeCol   = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val takenCol  = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val sizeCol   = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            val widthCol  = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightCol = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                items += GalleryItem(
                    id         = id,
                    uri        = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    name       = c.getString(nameCol) ?: "",
                    dateAdded  = c.getLong(dateCol),
                    mimeType   = c.getString(mimeCol) ?: "image/*",
                    folderName = if (bucketCol >= 0) c.getString(bucketCol) ?: "" else "",
                    dateTaken  = if (takenCol  >= 0) c.getLong(takenCol)   else 0L,
                    fileSize   = if (sizeCol   >= 0) c.getLong(sizeCol)    else 0L,
                    width      = if (widthCol  >= 0) c.getInt(widthCol)    else 0,
                    height     = if (heightCol >= 0) c.getInt(heightCol)   else 0,
                )
            }
        }
        return items
    }

    private fun queryVideos(trashed: Boolean = false): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        val queryArgs = if (trashed) Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        } else null

        val cursor = if (queryArgs != null) {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, queryArgs, null
            )
        } else {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )
        }
        cursor?.use { c ->
            val idCol     = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol   = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol   = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeCol   = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val durCol    = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val bucketCol = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val takenCol  = c.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
            val sizeCol   = c.getColumnIndex(MediaStore.Video.Media.SIZE)
            val widthCol  = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightCol = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                items += GalleryItem(
                    id         = id,
                    uri        = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    name       = c.getString(nameCol) ?: "",
                    dateAdded  = c.getLong(dateCol),
                    mimeType   = c.getString(mimeCol) ?: "video/*",
                    duration   = c.getLong(durCol),
                    folderName = if (bucketCol >= 0) c.getString(bucketCol) ?: "" else "",
                    dateTaken  = if (takenCol  >= 0) c.getLong(takenCol)   else 0L,
                    fileSize   = if (sizeCol   >= 0) c.getLong(sizeCol)    else 0L,
                    width      = if (widthCol  >= 0) c.getInt(widthCol)    else 0,
                    height     = if (heightCol >= 0) c.getInt(heightCol)   else 0,
                )
            }
        }
        return items
    }

}
