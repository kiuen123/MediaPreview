package com.example.mediapreview.data

import android.content.Context

class FavoritesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("media_favorites", Context.MODE_PRIVATE)
    private val KEY = "favorite_ids"

    fun getFavoriteIds(): Set<Long> =
        prefs.getStringSet(KEY, emptySet())
            ?.mapNotNull { it.toLongOrNull() }
            ?.toSet() ?: emptySet()

    fun toggleFavorite(id: Long): Set<Long> {
        val current = getFavoriteIds().toMutableSet()
        if (id in current) current.remove(id) else current.add(id)
        save(current)
        return current
    }

    fun removeFavorites(ids: Set<Long>): Set<Long> {
        val current = getFavoriteIds().toMutableSet()
        current.removeAll(ids)
        save(current)
        return current
    }

    private fun save(ids: Set<Long>) {
        prefs.edit().putStringSet(KEY, ids.map { it.toString() }.toSet()).apply()
    }
}

