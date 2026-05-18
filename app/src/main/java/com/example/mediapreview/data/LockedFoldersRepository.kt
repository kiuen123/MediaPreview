package com.example.mediapreview.data

import android.content.Context
import java.security.MessageDigest

class LockedFoldersRepository(context: Context) {
    private val prefs = context.getSharedPreferences("locked_folders", Context.MODE_PRIVATE)

    /** Returns names of all locked folders (excludes internal path/count/securePath storage keys). */
    fun getLockedFolders(): Set<String> =
        prefs.all.keys
            .filter { key ->
                !key.endsWith(PATH_SUFFIX) &&
                !key.endsWith(COUNT_SUFFIX) &&
                !key.endsWith(SECURE_PATH_SUFFIX)
            }
            .toSet()

    fun isLocked(folderName: String): Boolean = prefs.contains(folderName)

    /**
     * Lock a folder: save hashed password, the real filesystem path, and item count
     * (so we can still display the folder card after files are hidden from MediaStore).
     */
    fun lockFolder(
        folderName: String,
        password: String,
        folderPath: String? = null,
        itemCount: Int = 0,
    ) {
        prefs.edit()
            .putString(folderName, hash(folderName, password))
            .also { ed ->
                if (folderPath != null) ed.putString(folderName + PATH_SUFFIX, folderPath)
                ed.putInt(folderName + COUNT_SUFFIX, itemCount)
            }
            .apply()
    }

    /** Returns the stored filesystem path for the folder, if any. */
    fun getFolderPath(folderName: String): String? =
        prefs.getString(folderName + PATH_SUFFIX, null)

    /** Returns the item count stored when the folder was locked. */
    fun getItemCount(folderName: String): Int =
        prefs.getInt(folderName + COUNT_SUFFIX, 0)

    /**
     * Saves the path inside app-private external storage where the locked files
     * physically reside (Android/data/<package>/files/locked/<folder>/).
     * Other apps cannot access this directory on Android 11+ (scoped storage).
     */
    fun saveSecurePath(folderName: String, securePath: String) {
        prefs.edit().putString(folderName + SECURE_PATH_SUFFIX, securePath).apply()
    }

    /**
     * Returns the secure storage path where a locked folder's files have been
     * moved to, or null if the folder was locked before the move-to-secure-storage
     * feature was added (legacy entry — files may still be at [getFolderPath]).
     */
    fun getSecurePath(folderName: String): String? =
        prefs.getString(folderName + SECURE_PATH_SUFFIX, null)

    /** Change password without unlocking the folder. */
    fun changePassword(folderName: String, newPassword: String) {
        prefs.edit().putString(folderName, hash(folderName, newPassword)).apply()
    }

    fun verifyPassword(folderName: String, password: String): Boolean {
        val stored = prefs.getString(folderName, null) ?: return false
        return stored == hash(folderName, password)
    }

    fun unlockFolder(folderName: String) {
        prefs.edit()
            .remove(folderName)
            .remove(folderName + PATH_SUFFIX)
            .remove(folderName + COUNT_SUFFIX)
            .remove(folderName + SECURE_PATH_SUFFIX)
            .apply()
    }

    private fun hash(folderName: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$folderName:$password".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PATH_SUFFIX        = "\u0000path"
        private const val COUNT_SUFFIX       = "\u0000count"
        /** Path inside Android/data/<package>/files/locked/ where files were moved. */
        private const val SECURE_PATH_SUFFIX = "\u0000securePath"
    }
}
