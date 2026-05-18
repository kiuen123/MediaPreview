package com.example.mediapreview.data

import android.content.Context

data class PlaylistInfo(val name: String, val songIds: List<Long>)

class PlaylistRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    private val NAMES_KEY = "playlist_names"

    fun getAllPlaylists(): List<PlaylistInfo> {
        val names = prefs.getStringSet(NAMES_KEY, emptySet()) ?: emptySet()
        return names.sorted().map { name -> PlaylistInfo(name, getSongIds(name).toList()) }
    }

    fun getPlaylist(name: String): PlaylistInfo? {
        val names = prefs.getStringSet(NAMES_KEY, emptySet()) ?: emptySet()
        return if (name in names) PlaylistInfo(name, getSongIds(name).toList()) else null
    }

    private fun getSongIds(name: String): Set<Long> =
        prefs.getStringSet("pl_$name", emptySet())
            ?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

    fun createPlaylist(name: String) {
        val names = (prefs.getStringSet(NAMES_KEY, emptySet()) ?: emptySet()).toMutableSet()
        if (names.add(name)) prefs.edit().putStringSet(NAMES_KEY, names).apply()
    }

    fun deletePlaylist(name: String) {
        val names = (prefs.getStringSet(NAMES_KEY, emptySet()) ?: emptySet()).toMutableSet()
        names.remove(name)
        prefs.edit().putStringSet(NAMES_KEY, names).remove("pl_$name").apply()
    }

    fun addToPlaylist(name: String, ids: Set<Long>) {
        val current = getSongIds(name).toMutableSet()
        current.addAll(ids)
        saveIds(name, current)
    }

    fun removeFromPlaylist(name: String, id: Long) {
        val current = getSongIds(name).toMutableSet()
        current.remove(id)
        saveIds(name, current)
    }

    private fun saveIds(name: String, ids: Set<Long>) {
        prefs.edit().putStringSet("pl_$name", ids.map { it.toString() }.toSet()).apply()
    }
}

