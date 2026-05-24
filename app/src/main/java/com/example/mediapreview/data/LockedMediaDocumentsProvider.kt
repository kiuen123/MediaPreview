package com.example.mediapreview.data

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.example.mediapreview.R
import java.io.File

/**
 * DocumentsProvider that exposes locked folder contents to any app that uses
 * the Android Storage Access Framework (SAF) file picker.
 *
 * ── How it works ─────────────────────────────────────────────────────────────
 *  • Our locked files live in app-private storage (Android/data/<pkg>/files/locked/)
 *    which is invisible to other apps and to MediaStore.
 *  • By implementing DocumentsProvider we create a "virtual drive" called
 *    "MediaPreview" that appears in every app's file picker under the
 *    "Storage / Documents providers" section.
 *  • Only locked folders that have at least one app granted via
 *    [AppFolderPermissionRepository] are shown in the provider root.
 *
 * ── Access control logic ──────────────────────────────────────────────────────
 *  A folder appears in the provider when:
 *   1. The DIRECT caller has been explicitly granted access, OR
 *   2. ANY app has been granted access (handles DocumentsUI intermediary case —
 *      on Android 11+ DocumentsUI calls on behalf of the user-launched app).
 *
 * ── Security ──────────────────────────────────────────────────────────────────
 *  • [openDocument] verifies the requested file is inside our secure storage dir.
 *  • The provider is protected by android.permission.MANAGE_DOCUMENTS so only
 *    the system can call it directly.
 *  • Files are served as read-only ParcelFileDescriptors (no write access).
 */
class LockedMediaDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.example.mediapreview.locked_docs"

        private const val ROOT_ID     = "locked_root"
        const  val FOLDER_PREFIX = "folder:"
        const  val FILE_PREFIX   = "file:"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_QUERY_ARGS,
        )

        private val DEFAULT_DOC_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        private val MEDIA_EXTENSIONS = setOf(
            "jpg","jpeg","png","gif","webp","bmp","heic","heif","avif",
            "mp4","3gp","mkv","avi","mov","wmv","flv","webm","ts","m4v",
        )
    }

    private val ctx             get() = context!!
    private val permissionRepo  by lazy { AppFolderPermissionRepository(ctx) }
    private val lockedFoldersRepo by lazy { LockedFoldersRepository(ctx) }

    override fun onCreate(): Boolean = true

    // ── Roots ─────────────────────────────────────────────────────────────────

    /**
     * Returns one root ("MediaPreview") only when there is at least one locked
     * folder with a granted app.  Returning an empty cursor hides the root from
     * all file pickers until the user grants access to at least one app.
     */
    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val hasAccessible = lockedFoldersRepo.getLockedFolders().any { folderName ->
            permissionRepo.getGrantedApps(folderName).isNotEmpty()
        }
        if (!hasAccessible) return result

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID,     ROOT_ID)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES,  "image/*\nvideo/*")
            add(DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY or
                DocumentsContract.Root.FLAG_SUPPORTS_RECENTS or
                DocumentsContract.Root.FLAG_SUPPORTS_SEARCH)
            add(DocumentsContract.Root.COLUMN_ICON,        R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_TITLE,       "MediaPreview")
            add(DocumentsContract.Root.COLUMN_SUMMARY,     "Thư mục bảo mật")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_QUERY_ARGS,  null)
        }
        return result
    }

    // ── Single document ───────────────────────────────────────────────────────

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        when {
            documentId == ROOT_ID -> result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_ID)
                add(DocumentsContract.Document.COLUMN_MIME_TYPE,   DocumentsContract.Document.MIME_TYPE_DIR)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,"MediaPreview")
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                add(DocumentsContract.Document.COLUMN_SIZE, null)
            }
            documentId.startsWith(FOLDER_PREFIX) -> result.newRow().apply {
                val folderName = documentId.removePrefix(FOLDER_PREFIX)
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,  documentId)
                add(DocumentsContract.Document.COLUMN_MIME_TYPE,    DocumentsContract.Document.MIME_TYPE_DIR)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, folderName)
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                add(DocumentsContract.Document.COLUMN_SIZE, null)
            }
            documentId.startsWith(FILE_PREFIX) -> {
                val file = File(documentId.removePrefix(FILE_PREFIX))
                if (file.exists()) addFileRow(result, file)
            }
        }
        return result
    }

    // ── Children ──────────────────────────────────────────────────────────────

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val caller = callingPackage ?: ""

        when {
            parentDocumentId == ROOT_ID -> {
                // List all locked folders the caller (or anyone) has access to.
                lockedFoldersRepo.getLockedFolders().forEach { folderName ->
                    if (isAccessible(folderName, caller)) {
                        result.newRow().apply {
                            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,  "$FOLDER_PREFIX$folderName")
                            add(DocumentsContract.Document.COLUMN_MIME_TYPE,    DocumentsContract.Document.MIME_TYPE_DIR)
                            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, folderName)
                            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis())
                            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                            add(DocumentsContract.Document.COLUMN_SIZE, null)
                        }
                    }
                }
            }
            parentDocumentId.startsWith(FOLDER_PREFIX) -> {
                val folderName = parentDocumentId.removePrefix(FOLDER_PREFIX)
                if (!isAccessible(folderName, caller)) return result

                getSecureDir(folderName)
                    .listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in MEDIA_EXTENSIONS }
                    ?.sortedByDescending { it.lastModified() }
                    ?.forEach { addFileRow(result, it) }
            }
        }
        return result
    }

    // ── Open file ─────────────────────────────────────────────────────────────

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor? {
        if (!documentId.startsWith(FILE_PREFIX)) return null
        val file = File(documentId.removePrefix(FILE_PREFIX))
        if (!file.exists() || !isInSecureStorage(file)) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Provides a thumbnail so file pickers can show image previews. */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor? {
        if (!documentId.startsWith(FILE_PREFIX)) return null
        val file = File(documentId.removePrefix(FILE_PREFIX))
        if (!file.exists() || !isInSecureStorage(file)) return null
        val mime = getMimeType(file)
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0, AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * A folder is accessible when:
     *  a) The direct caller was explicitly granted access, OR
     *  b) The folder has at least one granted app (covers the DocumentsUI
     *     intermediary case on Android 11+).
     */
    private fun isAccessible(folderName: String, callerPkg: String): Boolean =
        permissionRepo.hasPermission(folderName, callerPkg) ||
                permissionRepo.getGrantedApps(folderName).isNotEmpty()

    private fun getSecureDir(folderName: String): File {
        val sanitized = folderName.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(64)
        return File(ctx.getExternalFilesDir("locked"), sanitized)
    }

    /** Path-traversal guard: file must live under our locked directory. */
    private fun isInSecureStorage(file: File): Boolean {
        val lockedBase = ctx.getExternalFilesDir("locked") ?: return false
        return try { file.canonicalPath.startsWith(lockedBase.canonicalPath) }
        catch (_: Exception) { false }
    }

    private fun addFileRow(cursor: MatrixCursor, file: File) {
        val mime  = getMimeType(file)
        val flags = if (mime.startsWith("image/") || mime.startsWith("video/"))
            DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL else 0
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,  "$FILE_PREFIX${file.absolutePath}")
            add(DocumentsContract.Document.COLUMN_MIME_TYPE,    mime)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.name)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(DocumentsContract.Document.COLUMN_FLAGS,         flags)
            add(DocumentsContract.Document.COLUMN_SIZE,          file.length())
        }
    }

    private fun getMimeType(file: File): String = when (file.extension.lowercase()) {
        "jpg","jpeg" -> "image/jpeg"; "png"  -> "image/png";  "gif"  -> "image/gif"
        "webp"       -> "image/webp"; "bmp"  -> "image/bmp";  "heic" -> "image/heic"
        "heif"       -> "image/heif"; "avif" -> "image/avif"
        "mp4"        -> "video/mp4";  "3gp"  -> "video/3gpp"; "mkv"  -> "video/x-matroska"
        "avi"        -> "video/avi";  "mov"  -> "video/quicktime"
        "wmv"        -> "video/x-ms-wmv"; "flv" -> "video/x-flv"
        "webm"       -> "video/webm"; "ts"   -> "video/mp2ts"; "m4v"  -> "video/x-m4v"
        else         -> "application/octet-stream"
    }
}

