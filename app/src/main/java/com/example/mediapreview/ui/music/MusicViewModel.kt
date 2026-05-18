package com.example.mediapreview.ui.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.mediapreview.data.MusicItem
import com.example.mediapreview.data.MusicRepository
import com.example.mediapreview.data.PlaylistInfo
import com.example.mediapreview.data.PlaylistRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    // Shuffle & Repeat
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    // Playlist
    val playlists: List<PlaylistInfo> = emptyList(),
    val selectedPlaylist: PlaylistInfo? = null,
    val showPlaylists: Boolean = false,
    val songContextMenu: MusicItem? = null,   // song tapped for context menu
)

class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository(application)
    private val playlistRepo = PlaylistRepository(application)

    private val _state = MutableStateFlow(
        MusicState(playlists = loadPlaylists())
    )
    val state: StateFlow<MusicState> = _state

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private var progressJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressTracking() else progressJob?.cancel()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = player.currentMediaItemIndex
            val songs = _state.value.songs
            if (index in songs.indices) {
                _state.update { it.copy(currentSong = songs[index]) }
            }
            startProgressTracking()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                _state.update { it.copy(isPlaying = false) }
                progressJob?.cancel()
            }
        }
    }

    private fun startProgressTracking() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                delay(250)
                val dur = player.duration.coerceAtLeast(0)
                _state.update {
                    it.copy(currentPositionMs = player.currentPosition, durationMs = dur)
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        _state.update { it.copy(currentPositionMs = positionMs) }
    }

    init {
        player.addListener(playerListener)
    }

    fun loadMusic() {
        if (_state.value.songs.isNotEmpty()) return
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

        if (_state.value.currentSong?.id == song.id) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }

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
            // currentSong updated by onMediaItemTransition listener
        }
    }

    fun skipToPrevious() {
        player.seekToPreviousMediaItem()
        // currentSong updated by onMediaItemTransition listener
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    // ── Shuffle & Repeat ───────────────────────────────────────────────────

    fun toggleShuffle() {
        val newShuffle = !_state.value.shuffleEnabled
        player.shuffleModeEnabled = newShuffle
        _state.update { it.copy(shuffleEnabled = newShuffle) }
    }

    fun cycleRepeatMode() {
        val next = when (_state.value.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = next
        _state.update { it.copy(repeatMode = next) }
    }

    // ── Playlist ───────────────────────────────────────────────────────────

    private fun loadPlaylists() = playlistRepo.getAllPlaylists()

    fun setShowPlaylists(show: Boolean) {
        _state.update { it.copy(showPlaylists = show, selectedPlaylist = null) }
    }

    fun selectPlaylist(playlist: PlaylistInfo?) {
        _state.update { it.copy(selectedPlaylist = playlist) }
    }

    fun createPlaylist(name: String) {
        playlistRepo.createPlaylist(name)
        _state.update { it.copy(playlists = loadPlaylists()) }
    }

    fun deletePlaylist(name: String) {
        playlistRepo.deletePlaylist(name)
        val cur = _state.value
        _state.update {
            it.copy(
                playlists = loadPlaylists(),
                selectedPlaylist = if (it.selectedPlaylist?.name == name) null else it.selectedPlaylist,
            )
        }
    }

    fun addSongToPlaylist(playlistName: String, songId: Long) {
        playlistRepo.addToPlaylist(playlistName, setOf(songId))
        _state.update { it.copy(playlists = loadPlaylists()) }
    }

    fun removeSongFromPlaylist(playlistName: String, songId: Long) {
        playlistRepo.removeFromPlaylist(playlistName, songId)
        _state.update { it.copy(playlists = loadPlaylists()) }
    }

    fun playPlaylist(playlist: PlaylistInfo) {
        val ids = playlist.songIds.toSet()
        val songsInPlaylist = _state.value.songs.filter { it.id in ids }
        if (songsInPlaylist.isEmpty()) return
        player.clearMediaItems()
        songsInPlaylist.forEach { player.addMediaItem(MediaItem.fromUri(it.uri)) }
        player.seekTo(0, 0)
        player.prepare()
        player.play()
        _state.update { it.copy(currentSong = songsInPlaylist.first(), isPlaying = true, selectedPlaylist = playlist) }
    }

    fun setSongContextMenu(song: MusicItem?) {
        _state.update { it.copy(songContextMenu = song) }
    }

    override fun onCleared() {
        super.onCleared()
        progressJob?.cancel()
        player.removeListener(playerListener)
        player.release()
    }
}
