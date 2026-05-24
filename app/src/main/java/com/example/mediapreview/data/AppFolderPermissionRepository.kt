package com.example.mediapreview.data

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import java.io.File

/**
 * Manages per-folder, per-app read permissions for locked folders.
 *
 * Strategy:
 *  1. Permissions are persisted in SharedPreferences so they survive app restarts.
 *  2. For each granted (folderName, packageName) pair we call
 *     [Context.grantUriPermission] with FLAG_GRANT_READ_URI_PERMISSION so the
 *     other app can open the individual file URIs served by our FileProvider.
 *  3. On every app start / folder-unlock [reGrantAllPermissions] should be called
 *     to restore ephemeral OS grants that may have been lost after a device reboot.
 *  4. On folder unlock [revokeAllForFolder] removes all records and OS grants for
 *     that folder.
 *
 * Note: Android does not support making a file visible to *only* selected apps
 * in MediaStore – that is OS-level and cannot be done per-package.  This class
 * instead uses the FileProvider (content://) mechanism: files stay in our
 * app-private secure storage and are only accessible to packages that have been
 * explicitly granted a URI permission by our app.
 */
class AppFolderPermissionRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("app_folder_permissions", Context.MODE_PRIVATE)

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Grant [packageName] read access to all current files inside the locked
     * folder [folderName].  Persists the permission and immediately issues OS
     * URI grants so the app can open the files right away.
     */
    fun grantPermission(folderName: String, packageName: String) {
        prefs.edit().putBoolean(key(folderName, packageName), true).apply()
        grantUriPermissions(folderName, packageName)
        notifyDocumentsRootsChanged()
    }

    /**
     * Revoke [packageName]'s read access to [folderName].  Removes the
     * persisted record and revokes the OS URI grants.
     */
    fun revokePermission(folderName: String, packageName: String) {
        prefs.edit().remove(key(folderName, packageName)).apply()
        revokeUriPermissions(folderName, packageName)
        notifyDocumentsRootsChanged()
    }

    /** Returns true if [packageName] has been granted access to [folderName]. */
    fun hasPermission(folderName: String, packageName: String): Boolean =
        prefs.getBoolean(key(folderName, packageName), false)

    /** Returns the set of package names currently granted access to [folderName]. */
    fun getGrantedApps(folderName: String): Set<String> =
        prefs.all.keys
            .filter { it.startsWith("$folderName$KEY_SEP") }
            .map    { it.removePrefix("$folderName$KEY_SEP") }
            .toSet()

    /**
     * Remove ALL permission records and URI grants for [folderName].
     * Call this when the folder is permanently unlocked.
     */
    fun revokeAllForFolder(folderName: String) {
        getGrantedApps(folderName).forEach { pkg ->
            prefs.edit().remove(key(folderName, pkg)).apply()
            revokeUriPermissions(folderName, pkg)
        }
        notifyDocumentsRootsChanged()
    }

    /**
     * Re-issues URI grants for every (folder, package) pair stored in prefs.
     *
     * Call this on app start so that URI permissions lost after a device reboot
     * are restored.  Individual [grantUriPermission] calls are no-ops for pairs
     * that are already active, so calling this on every start is safe.
     */
    fun reGrantAllPermissions() {
        prefs.all.keys.forEach { k ->
            val idx = k.indexOf(KEY_SEP)
            if (idx > 0) {
                val folderName  = k.substring(0, idx)
                val packageName = k.substring(idx + KEY_SEP.length)
                grantUriPermissions(folderName, packageName)
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun grantUriPermissions(folderName: String, packageName: String) {
        try {
            val dir = secureDirOf(folderName)
            dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file,
                )
                context.grantUriPermission(
                    packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
            }
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun revokeUriPermissions(folderName: String, packageName: String) {
        try {
            val dir = secureDirOf(folderName)
            dir.listFiles()?.filter { it.isFile }?.forEach { file ->
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file,
                )
                context.revokeUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun secureDirOf(folderName: String): File =
        File(context.getExternalFilesDir("locked"), sanitize(folderName))

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(64)

    /**
     * Notifies the system DocumentsUI that our roots have changed
     * so it refreshes the file picker immediately when permissions are added/removed.
     */
    private fun notifyDocumentsRootsChanged() {
        try {
            val authority = "${context.packageName}.locked_docs"
            context.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(authority), null,
            )
        } catch (_: Exception) { /* best-effort */ }
    }

    private fun key(folderName: String, packageName: String) =
        "$folderName$KEY_SEP$packageName"

    companion object {
        /** Separator between folder name and package name in the prefs key. */
        private const val KEY_SEP = "\u0000"
    }
}

