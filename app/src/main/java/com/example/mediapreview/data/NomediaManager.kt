package com.example.mediapreview.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Manages hiding / unhiding folder contents from the system MediaStore.
 *
 * Strategy (triple belt-and-suspenders):
 *  1. IS_PENDING = 1  — immediately invisible in every other app's MediaStore
 *                        query (they never include pending items by default).
 *                        Physical files are NOT touched.
 *  2. IS_TRASHED = 1  — additionally marks entries as "in trash"; apps querying
 *                        without QUERY_ARG_INCLUDE_TRASHED won't see these items
 *                        either, providing a second hiding layer on top of IS_PENDING.
 *  3. .nomedia file   — prevents future media scans from re-adding entries,
 *                        even after a device reboot.
 *  4. scanFolderAfterNomedia() — after .nomedia is written, triggers MediaScanner
 *                        on the directory so it actively **removes** the existing
 *                        MediaStore rows for all files in that folder from the
 *                        database.  Combined with (1)+(2)+(3) this ensures entries
 *                        are gone from MediaStore even for apps that explicitly
 *                        include pending / trashed items in their queries.
 *
 * On unlock all layers are reversed: IS_PENDING → 0, IS_TRASHED → 0,
 * .nomedia deleted, files re-scanned so metadata is refreshed.
 *
 * Requires MANAGE_EXTERNAL_STORAGE (Android 11+).
 */
class NomediaManager(private val context: Context) {

    /** Returns true if the app holds MANAGE_EXTERNAL_STORAGE. */
    fun hasPermission(): Boolean = Environment.isExternalStorageManager()

    /**
     * Looks up the real filesystem path for [folderName] by querying MediaStore.
     * Must be called BEFORE creating .nomedia (because afterwards the entries are hidden).
     */
    fun getFolderPath(folderName: String): String? {
        @Suppress("DEPRECATION")
        val projection = arrayOf(MediaStore.MediaColumns.DATA)
        val selection  = "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?"
        val args       = arrayOf(folderName)

        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            context.contentResolver.query(uri, projection, selection, args, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        @Suppress("DEPRECATION")
                        val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                        if (col >= 0) {
                            cursor.getString(col)?.let { path ->
                                File(path).parent?.let { return it }
                            }
                        }
                    }
                }
        }
        return null
    }

    /** Creates a .nomedia sentinel file. Returns true on success. */
    fun createNomedia(folderPath: String): Boolean = try {
        val f = File(folderPath, ".nomedia")
        if (f.exists()) true else f.createNewFile()
    } catch (_: Exception) { false }

    /** Deletes the .nomedia sentinel file. Returns true on success. */
    fun deleteNomedia(folderPath: String): Boolean = try {
        val f = File(folderPath, ".nomedia")
        if (f.exists()) f.delete() else true
    } catch (_: Exception) { false }

    /**
     * Called AFTER .nomedia is created.
     *
     * Sets IS_PENDING = 1 **and** IS_TRASHED = 1 on every MediaStore entry for
     * files in [folderPath].
     *  • IS_PENDING = 1 hides the item from apps that query normally.
     *  • IS_TRASHED = 1 adds a second layer — apps that query without
     *    QUERY_ARG_INCLUDE_TRASHED also cannot see these items.
     * Physical files are NOT touched.
     *
     * ⚠️ Never use ContentResolver.delete() here — with MANAGE_EXTERNAL_STORAGE
     *    that permanently deletes the physical files.
     */
    suspend fun hideFromMediaStore(folderPath: String) = withContext(Dispatchers.IO) {
        setFlagsForFolder(folderPath, hide = true)
    }

    /**
     * Called AFTER .nomedia is deleted.
     *
     * Clears IS_PENDING = 0 and IS_TRASHED = 0 to make files visible again in
     * MediaStore, then scans each file so MediaStore refreshes its metadata.
     */
    suspend fun unhideFromMediaStore(folderPath: String) = withContext(Dispatchers.IO) {
        setFlagsForFolder(folderPath, hide = false)
        // Re-scan so MediaStore refreshes thumbnails / metadata
        val files = collectMediaFiles(folderPath)
        if (files.isNotEmpty()) {
            scanFilesAndWait(files)
        } else {
            suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(
                    context, arrayOf(folderPath), null,
                ) { _, _ -> if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Updates IS_PENDING and IS_TRASHED for all image/video MediaStore entries
     * whose DATA path is under [folderPath].
     *
     * When [hide] = true  → sets IS_PENDING=1 + IS_TRASHED=1.
     *   • IS_PENDING=1 hides items from apps that do not explicitly include
     *     pending items in their queries (the default for every gallery app).
     *   • IS_TRASHED=1 provides a second hiding layer: apps that don't include
     *     trashed items (also the default) will not see these items either.
     *   Together they ensure the files are invisible to any standard gallery query.
     *
     * When [hide] = false → clears both flags back to 0 (visible again).
     *
     * Uses the Bundle-based update API (API 30+) so we can include both pending
     * and trashed items in the search when clearing the flags.
     */
    private fun setFlagsForFolder(folderPath: String, hide: Boolean) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, if (hide) 1 else 0)
            put(MediaStore.MediaColumns.IS_TRASHED, if (hide) 1 else 0)
        }

        @Suppress("DEPRECATION")
        val extras = Bundle().apply {
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DATA} LIKE ?",
            )
            putStringArray(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("$folderPath/%"),
            )
            if (!hide) {
                // When clearing flags we must explicitly include items that have
                // IS_PENDING=1 or IS_TRASHED=1 so we can find and reset them.
                // Raw keys for MediaStore.QUERY_ARG_INCLUDE_PENDING (API 29+)
                // and MediaStore.QUERY_ARG_INCLUDE_TRASHED (API 29+).
                putInt("android:query-arg-include-pending", 1)
                putInt("android:query-arg-include-trashed", 1)
            }
        }

        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            try {
                context.contentResolver.update(uri, values, extras)
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /**
     * Triggers a MediaScanner pass on [folderPath] **after** the .nomedia sentinel
     * file has been written.
     *
     * When MediaScanner revisits a directory that contains a .nomedia file it
     * actively **removes** all existing MediaStore rows for files in that directory
     * from the database.  This is the strongest possible protection: even apps
     * that craft queries with INCLUDE_PENDING / INCLUDE_TRASHED will find nothing,
     * because the rows simply no longer exist in MediaStore.
     *
     * The scan is intentionally limited to two paths:
     *  • the .nomedia file itself  — triggers the "remove directory" logic
     *  • the folder path           — ensures the directory-level cleanup runs
     *
     * Note: [hideFromMediaStore] should still be called first so files are
     * invisible immediately while the async scan completes in the background.
     */
    suspend fun scanFolderAfterNomedia(folderPath: String) = withContext(Dispatchers.IO) {
        val paths = arrayOf("$folderPath/.nomedia", folderPath)
        val remaining = AtomicInteger(paths.size)
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, paths, null) { _, _ ->
                if (remaining.decrementAndGet() <= 0 && cont.isActive) cont.resume(Unit)
            }
        }
    }

    // ── Secure-storage move helpers ─────────────────────────────────────────

    /**
     * Moves all media files from [sourceFolderPath] into the app's private external
     * storage directory:
     *   Android/data/<package>/files/locked/<sanitised-folderName>/
     *
     * Why this is the strongest possible protection:
     *  • Android/data/<package>/ is NEVER indexed by MediaStore → no gallery app
     *    (including Samsung Gallery) can discover the files via MediaStore queries.
     *  • Android 11+ scoped storage prevents ALL other apps from reading the
     *    directory directly, even apps that hold READ_EXTERNAL_STORAGE.
     *  • Only an app with MANAGE_EXTERNAL_STORAGE AND knowledge of the exact path
     *    could theoretically access the files — far beyond what Discord, Messenger,
     *    Samsung Gallery or any normal third-party app can do.
     *  • The Android photo picker / SAF browser does not expose
     *    Android/data/<other-package> to users or apps.
     *
     * The previous IS_PENDING=1 / IS_TRASHED=1 / .nomedia layers remain as a
     * belt-and-suspenders fallback for any file that could not be moved.
     *
     * @return The absolute path of the secure destination directory, or null on failure.
     */
    suspend fun moveToSecureStorage(sourceFolderPath: String, folderName: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val secureDir = File(
                    context.getExternalFilesDir("locked"),
                    sanitizeFolderName(folderName),
                ).also { it.mkdirs() }

                var movedAny = false
                File(sourceFolderPath).listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in mediaExtensions }
                    ?.forEach { file ->
                        try {
                            file.copyTo(File(secureDir, file.name), overwrite = true)
                            file.delete()
                            movedAny = true
                        } catch (_: Exception) { /* skip unreadable file, leave it for IS_PENDING */ }
                    }

                if (movedAny || secureDir.exists()) secureDir.absolutePath else null
            } catch (_: Exception) { null }
        }

    /**
     * Moves all files from [secureFolderPath] back to [destFolderPath].
     * Called when the user permanently unlocks a folder.
     * Cleans up the (now-empty) secure directory afterwards.
     */
    suspend fun moveFromSecureStorage(secureFolderPath: String, destFolderPath: String) =
        withContext(Dispatchers.IO) {
            try {
                val secureDir = File(secureFolderPath)
                val destDir   = File(destFolderPath).also { it.mkdirs() }
                secureDir.listFiles()
                    ?.filter { it.isFile }
                    ?.forEach { file ->
                        try {
                            file.copyTo(File(destDir, file.name), overwrite = true)
                            file.delete()
                        } catch (_: Exception) { }
                    }
                secureDir.delete() // remove now-empty secure dir
            } catch (_: Exception) { /* best-effort */ }
        }

    /**
     * Converts a folder name to a safe directory name for use inside
     * the app-private secure storage path (no path-separator characters, etc.).
     */
    private fun sanitizeFolderName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(64)

    private val mediaExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
        "mp4", "3gp", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v",
    )

    private fun collectMediaFiles(folderPath: String): List<String> =
        File(folderPath).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in mediaExtensions }
            ?.map { it.absolutePath }
            ?: emptyList()

    /** Scan all [paths] individually and suspend until every callback fires. */
    private suspend fun scanFilesAndWait(paths: List<String>) {
        val remaining = AtomicInteger(paths.size)
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(
                context,
                paths.toTypedArray(),
                null,
            ) { _, _ ->
                if (remaining.decrementAndGet() <= 0 && cont.isActive) {
                    cont.resume(Unit)
                }
            }
        }
    }
}
