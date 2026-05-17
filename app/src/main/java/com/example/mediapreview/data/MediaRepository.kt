package com.example.mediapreview.data

import android.content.ContentUris
import android.content.Context
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

    private fun queryImages(): List<GalleryItem> {
        val items = mutableListOf<GalleryItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol     = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val mimeCol   = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val bucketCol = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val takenCol  = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val sizeCol   = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items += GalleryItem(
                    id         = id,
                    uri        = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                    name       = cursor.getString(nameCol) ?: "",
                    dateAdded  = cursor.getLong(dateCol),
                    mimeType   = cursor.getString(mimeCol) ?: "image/*",
                    folderName = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "" else "",
                    dateTaken  = if (takenCol  >= 0) cursor.getLong(takenCol)   else 0L,
                    fileSize   = if (sizeCol   >= 0) cursor.getLong(sizeCol)    else 0L,
                )
            }
        }
        return items
    }

    private fun queryVideos(): List<GalleryItem> {
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
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol     = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeCol   = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val durCol    = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val bucketCol = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val takenCol  = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
            val sizeCol   = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items += GalleryItem(
                    id         = id,
                    uri        = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                    name       = cursor.getString(nameCol) ?: "",
                    dateAdded  = cursor.getLong(dateCol),
                    mimeType   = cursor.getString(mimeCol) ?: "video/*",
                    duration   = cursor.getLong(durCol),
                    folderName = if (bucketCol >= 0) cursor.getString(bucketCol) ?: "" else "",
                    dateTaken  = if (takenCol  >= 0) cursor.getLong(takenCol)   else 0L,
                    fileSize   = if (sizeCol   >= 0) cursor.getLong(sizeCol)    else 0L,
                )
            }
        }
        return items
    }
}
