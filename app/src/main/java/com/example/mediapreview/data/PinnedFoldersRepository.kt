package com.example.mediapreview.data

import android.content.Context

class PinnedFoldersRepository(context: Context) {
    private val prefs = context.getSharedPreferences("pinned_folders", Context.MODE_PRIVATE)

    fun getPinnedFolders(): Set<String> =
        prefs.getStringSet(KEY_PINNED, emptySet()) ?: emptySet()

    /** Toggle pin state. Returns the new full set of pinned folders. */
    fun togglePin(folderName: String): Set<String> {
        val current = getPinnedFolders().toMutableSet()
        if (folderName in current) current.remove(folderName) else current.add(folderName)
        prefs.edit().putStringSet(KEY_PINNED, current).apply()
        return current.toSet()
    }

    fun isPinned(folderName: String): Boolean = folderName in getPinnedFolders()

    companion object {
        private const val KEY_PINNED = "pinned"
    }
}

