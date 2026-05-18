package com.example.mediapreview.data

import android.content.Context

data class CustomAlbumInfo(val name: String, val itemIds: List<Long>)

class CustomAlbumsRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("custom_albums", Context.MODE_PRIVATE)
    private val NAMES_KEY = "album_names"

    fun getAllAlbums(): List<CustomAlbumInfo> {
        val names = prefs.getStringSet(NAMES_KEY, emptySet()) ?: emptySet()
        return names.sorted().map { name -> CustomAlbumInfo(name, getItemIds(name).toList()) }
    }

    private fun getItemIds(name: String): Set<Long> =
        prefs.getStringSet("ca_$name", emptySet())
            ?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

    fun createAlbum(name: String) {
        val names = (prefs.getStringSet(NAMES_KEY, emptySet()) ?: emptySet()).toMutableSet()
        if (names.add(name)) prefs.edit().putStringSet(NAMES_KEY, names).apply()
    }

    fun deleteAlbum(name: String) {
        val names = (prefs.getStringSet(NAMES_KEY, emptySet()) ?: emptySet()).toMutableSet()
        names.remove(name)
        prefs.edit().putStringSet(NAMES_KEY, names).remove("ca_$name").apply()
    }

    fun addToAlbum(name: String, ids: Set<Long>) {
        val current = getItemIds(name).toMutableSet()
        current.addAll(ids)
        prefs.edit().putStringSet("ca_$name", current.map { it.toString() }.toSet()).apply()
    }

    fun removeFromAlbum(name: String, id: Long) {
        val current = getItemIds(name).toMutableSet()
        current.remove(id)
        prefs.edit().putStringSet("ca_$name", current.map { it.toString() }.toSet()).apply()
    }
}

