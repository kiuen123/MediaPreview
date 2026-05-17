package com.example.mediapreview.ui.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.mediapreview.data.MusicItem
import com.example.mediapreview.data.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MusicState(
    val songs: List<MusicItem> = emptyList(),
    val isLoading: Boolean = false,
    val currentSong: MusicItem? = null,
    val isPlaying: Boolean = false,
    val searchQuery: String = "",
    val error: String? = null,
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)

    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = player.currentMediaItemIndex
            val songs = _state.value.songs
            if (index in songs.indices) {
                _state.update { it.copy(currentSong = songs[index]) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _state.update { it.copy(isPlaying = false) }
            }
        }
    }

    init {
        player.addListener(playerListener)
    }

    fun loadMusic() {
        if (_state.value.songs.isNotEmpty()) return   // already loaded
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val songs = repository.loadAllMusic()
                _state.update { it.copy(songs = songs, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun reload() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val songs = repository.loadAllMusic()
                _state.update { it.copy(songs = songs, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun playSong(song: MusicItem) {
        val songs = _state.value.songs
        val index = songs.indexOfFirst { it.id == song.id }
        if (index < 0) return

        // Toggle play/pause if same song
        if (_state.value.currentSong?.id == song.id) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }

        // Load full playlist and start from selected song
        player.clearMediaItems()
        songs.forEach { s -> player.addMediaItem(MediaItem.fromUri(s.uri)) }
        player.seekTo(index, 0)
        player.prepare()
        player.play()
        _state.update { it.copy(currentSong = song, isPlaying = true) }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipToNext() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            val index = player.currentMediaItemIndex
            val songs = _state.value.songs
            if (index in songs.indices) {
                _state.update { it.copy(currentSong = songs[index]) }
            }
        }
    }

    fun skipToPrevious() {
        player.seekToPreviousMediaItem()
        val index = player.currentMediaItemIndex
        val songs = _state.value.songs
        if (index in songs.indices) {
            _state.update { it.copy(currentSong = songs[index]) }
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    override fun onCleared() {
        super.onCleared()
        player.removeListener(playerListener)
        player.release()
    }
}

