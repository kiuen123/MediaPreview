package com.example.mediapreview.ui.music

import android.Manifest
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalConfiguration
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.example.mediapreview.data.MusicItem
import com.example.mediapreview.data.PlaylistInfo
import com.example.mediapreview.ui.gallery.formatDuration

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MusicScreen(viewModel: MusicViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var permissionGranted by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Playlist dialogs
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var addToPlaylistSong by remember { mutableStateOf<MusicItem?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) viewModel.loadMusic()
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
    }

    val filteredSongs: List<MusicItem> = remember(state.songs, state.searchQuery, state.selectedPlaylist) {
        val base = if (state.selectedPlaylist != null) {
            val ids = state.selectedPlaylist!!.songIds.toSet()
            state.songs.filter { it.id in ids }
        } else state.songs

        if (state.searchQuery.isBlank()) base
        else {
            val q = state.searchQuery.trim().lowercase()
            base.filter {
                it.title.lowercase().contains(q) ||
                it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
            }
        }
    }

    // Back handlers
    BackHandler(enabled = state.selectedPlaylist != null) { viewModel.selectPlaylist(null) }
    BackHandler(enabled = searchActive) {
        searchActive = false; searchText = ""; viewModel.setSearchQuery("")
    }

    // ── Dialogs ────────────────────────────────────────────────────────────

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onConfirm = { name -> viewModel.createPlaylist(name); showCreatePlaylistDialog = false },
            onDismiss = { showCreatePlaylistDialog = false }
        )
    }

    addToPlaylistSong?.let { song ->
        AddToPlaylistDialog(
            playlists = state.playlists,
            onSelect = { playlistName ->
                viewModel.addSongToPlaylist(playlistName, song.id)
                addToPlaylistSong = null
            },
            onCreateNew = { name ->
                viewModel.createPlaylist(name)
                viewModel.addSongToPlaylist(name, song.id)
                addToPlaylistSong = null
            },
            onDismiss = { addToPlaylistSong = null }
        )
    }

    state.songContextMenu?.let { song ->
        SongContextMenuDialog(
            song = song,
            playlists = state.playlists,
            onAddToPlaylist = { addToPlaylistSong = song; viewModel.setSongContextMenu(null) },
            onDismiss = { viewModel.setSongContextMenu(null) }
        )
    }

    Scaffold(
        topBar = {
            when {
                searchActive -> MusicSearchTopBar(
                    query = searchText,
                    onQueryChange = { q -> searchText = q; viewModel.setSearchQuery(q) },
                    onClose = { searchActive = false; searchText = ""; viewModel.setSearchQuery("") }
                )
                state.selectedPlaylist != null -> TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.selectPlaylist(null) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                        }
                    },
                    title = {
                        Text(state.selectedPlaylist!!.name,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, "Tìm kiếm")
                        }
                    }
                )
                else -> TopAppBar(
                    title = {
                        Text(
                            if (state.showPlaylists) "Danh sách phát" else "Nhạc",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                        )
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, "Tìm kiếm")
                        }
                        if (state.showPlaylists) {
                            IconButton(onClick = { showCreatePlaylistDialog = true }) {
                                Icon(Icons.Default.Add, "Tạo playlist")
                            }
                        }
                    }
                )
            }
        },
    ) { paddingValues ->
        if (isLandscape && state.currentSong != null) {
            Row(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    MusicBody(state, filteredSongs, permissionGranted,
                        { permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO) },
                        viewModel, addPad = 0.dp,
                        onLongPressSong = { addToPlaylistSong = it })
                }
                VerticalDivider(modifier = Modifier.fillMaxHeight())
                state.currentSong?.let { song ->
                    NowPlayingPanel(
                        song = song, isPlaying = state.isPlaying,
                        positionMs = state.currentPositionMs, durationMs = state.durationMs,
                        shuffleEnabled = state.shuffleEnabled, repeatMode = state.repeatMode,
                        onTogglePlayPause = { viewModel.togglePlayPause() },
                        onSkipPrevious = { viewModel.skipToPrevious() },
                        onSkipNext = { viewModel.skipToNext() },
                        onSeek = { viewModel.seekTo(it) },
                        onToggleShuffle = { viewModel.toggleShuffle() },
                        onCycleRepeat = { viewModel.cycleRepeatMode() },
                        modifier = Modifier.width(300.dp).fillMaxHeight()
                    )
                }
            }
        } else {
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                MusicBody(state, filteredSongs, permissionGranted,
                    { permissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO) },
                    viewModel, addPad = if (state.currentSong != null) 80.dp else 0.dp,
                    onLongPressSong = { addToPlaylistSong = it })
                AnimatedVisibility(
                    visible = state.currentSong != null,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    state.currentSong?.let { song ->
                        MiniPlayer(
                            song = song, isPlaying = state.isPlaying,
                            positionMs = state.currentPositionMs, durationMs = state.durationMs,
                            shuffleEnabled = state.shuffleEnabled, repeatMode = state.repeatMode,
                            onTogglePlayPause = { viewModel.togglePlayPause() },
                            onSkipPrevious = { viewModel.skipToPrevious() },
                            onSkipNext = { viewModel.skipToNext() },
                            onSeek = { viewModel.seekTo(it) },
                            onToggleShuffle = { viewModel.toggleShuffle() },
                            onCycleRepeat = { viewModel.cycleRepeatMode() },
                        )
                    }
                }
            }
        }
    }
}

// ── Tab switcher (Songs | Playlists) ─────────────────────────────────────────

@Composable
private fun MusicTabRow(selectedPlaylists: Boolean, onSongs: () -> Unit, onPlaylists: () -> Unit) {
    PrimaryTabRow(selectedTabIndex = if (selectedPlaylists) 1 else 0) {
        Tab(selected = !selectedPlaylists, onClick = onSongs,
            text = { Text("Bài hát", style = MaterialTheme.typography.labelMedium) })
        Tab(selected = selectedPlaylists, onClick = onPlaylists,
            text = { Text("Danh sách phát", style = MaterialTheme.typography.labelMedium) })
    }
}

// ── Music body (songs list or playlist list) ──────────────────────────────────

@Composable
private fun MusicBody(
    state: MusicState,
    filteredSongs: List<MusicItem>,
    permissionGranted: Boolean,
    permissionLauncher: () -> Unit,
    viewModel: MusicViewModel,
    addPad: androidx.compose.ui.unit.Dp,
    onLongPressSong: (MusicItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Tabs only when not drilled into a playlist
        if (state.selectedPlaylist == null) {
            MusicTabRow(
                selectedPlaylists = state.showPlaylists,
                onSongs = { viewModel.setShowPlaylists(false) },
                onPlaylists = { viewModel.setShowPlaylists(true) }
            )
        }

        if (state.showPlaylists && state.selectedPlaylist == null) {
            PlaylistListContent(
                playlists = state.playlists,
                songs = state.songs,
                onPlayPlaylist = { viewModel.playPlaylist(it) },
                onSelectPlaylist = { viewModel.selectPlaylist(it) },
                onDeletePlaylist = { viewModel.deletePlaylist(it.name) },
                modifier = Modifier.weight(1f)
            )
        } else {
            MusicContent(
                state = state, filteredSongs = filteredSongs,
                permissionGranted = permissionGranted, permissionLauncher = permissionLauncher,
                onPlay = { viewModel.playSong(it) },
                onLongPress = onLongPressSong,
                miniPlayerPad = addPad,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ── Playlist list ─────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlaylistListContent(
    playlists: List<PlaylistInfo>,
    songs: List<MusicItem>,
    onPlayPlaylist: (PlaylistInfo) -> Unit,
    onSelectPlaylist: (PlaylistInfo) -> Unit,
    onDeletePlaylist: (PlaylistInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (playlists.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.QueueMusic, null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Text("Chưa có danh sách phát.\nNhấn + để tạo mới.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(playlists, key = { it.name }) { playlist ->
            var showMenu by remember { mutableStateOf(false) }
            val songCount = songs.count { it.id in playlist.songIds.toSet() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onSelectPlaylist(playlist) },
                        onLongClick = { showMenu = true }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$songCount bài hát", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onPlayPlaylist(playlist) }) {
                    Icon(Icons.Default.PlayArrow, "Phát", tint = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Thêm")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Xóa playlist") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { showMenu = false; onDeletePlaylist(playlist) }
                        )
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

// ── Music content (song list) ─────────────────────────────────────────────────

@Composable
private fun MusicContent(
    state: MusicState,
    filteredSongs: List<MusicItem>,
    permissionGranted: Boolean,
    permissionLauncher: () -> Unit,
    onPlay: (MusicItem) -> Unit,
    onLongPress: (MusicItem) -> Unit,
    miniPlayerPad: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    when {
        state.isLoading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        !permissionGranted && state.songs.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.MusicOff, null, modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Cần quyền truy cập để xem nhạc.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = permissionLauncher) { Text("Cấp quyền") }
                }
            }
        }
        state.error != null -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Lỗi tải nhạc:\n${state.error}", color = MaterialTheme.colorScheme.error)
            }
        }
        filteredSongs.isEmpty() -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text(if (state.searchQuery.isNotBlank()) "Không tìm thấy bài hát"
                    else "Không có bài hát nào.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        else -> {
            LazyColumn(
                contentPadding = PaddingValues(bottom = miniPlayerPad),
                modifier = modifier.fillMaxSize()
            ) {
                item {
                    Text("${filteredSongs.size} bài hát",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                items(filteredSongs, key = { it.id }) { song ->
                    SongRow(
                        song = song,
                        isPlaying = state.currentSong?.id == song.id && state.isPlaying,
                        isCurrent = state.currentSong?.id == song.id,
                        onClick = { onPlay(song) },
                        onLongClick = { onLongPress(song) }
                    )
                    HorizontalDivider(thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

// ── Now playing panel (landscape) ────────────────────────────────────────────

@Composable
private fun NowPlayingPanel(
    song: MusicItem,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 4.dp) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(140.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(model = song.albumArtUri, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (song.albumArtUri == null) {
                    Icon(Icons.Default.MusicNote, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(song.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(song.artist, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            if (durationMs > 0) {
                Slider(value = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f),
                    onValueChange = { onSeek((it * durationMs).toLong()) },
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(positionMs), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            // Shuffle + Repeat row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(Icons.Default.Shuffle, "Shuffle",
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                }
                IconButton(onClick = onSkipPrevious) {
                    Icon(Icons.Default.SkipPrevious, "Trước", modifier = Modifier.size(32.dp))
                }
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = onSkipNext) {
                    Icon(Icons.Default.SkipNext, "Tiếp theo", modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                            Player.REPEAT_MODE_ALL -> Icons.Default.Repeat
                            else -> Icons.Default.Repeat
                        },
                        "Lặp lại",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

// ── Song row ──────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SongRow(
    song: MusicItem,
    isPlaying: Boolean,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val accentColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(50.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            AsyncImage(model = song.albumArtUri, contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (song.albumArtUri == null) {
                Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp))
            }
            if (isPlaying) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "Đang phát",
                        tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = accentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(song.artist, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(song.duration), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

// ── Mini player ───────────────────────────────────────────────────────────────

@Composable
private fun MiniPlayer(
    song: MusicItem,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onTogglePlayPause: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Surface(tonalElevation = 8.dp, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    AsyncImage(model = song.albumArtUri, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (song.albumArtUri == null) {
                        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(song.title, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Shuffle
                IconButton(onClick = onToggleShuffle, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Shuffle, "Shuffle", modifier = Modifier.size(18.dp),
                        tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }
                IconButton(onClick = onSkipPrevious, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Trước")
                }
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onSkipNext, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.SkipNext, "Tiếp theo")
                }
                // Repeat
                IconButton(onClick = onCycleRepeat, modifier = Modifier.size(36.dp)) {
                    Icon(
                        when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        },
                        "Lặp lại", modifier = Modifier.size(18.dp),
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

// ── Search top bar ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Đóng") }
        },
        title = {
            TextField(value = query, onValueChange = onQueryChange,
                placeholder = { Text("Tìm bài hát, nghệ sĩ, album...") }, singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ), modifier = Modifier.fillMaxWidth())
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Default.Clear, "Xóa")
            }
        }
    )
}

// ── Playlist dialogs ──────────────────────────────────────────────────────────

@Composable
private fun CreatePlaylistDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
        title = { Text("Tạo danh sách phát") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it; error = false },
                label = { Text("Tên danh sách") }, singleLine = true,
                isError = error,
                supportingText = if (error) ({ Text("Tên không được để trống") }) else null,
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) error = true else onConfirm(name.trim())
            }) { Text("Tạo") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
private fun AddToPlaylistDialog(
    playlists: List<PlaylistInfo>,
    onSelect: (String) -> Unit,
    onCreateNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    if (showCreate) {
        CreatePlaylistDialog(onConfirm = { onCreateNew(it) }, onDismiss = { showCreate = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm vào danh sách phát") },
        text = {
            Column {
                TextButton(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Tạo danh sách mới")
                }
                if (playlists.isEmpty()) {
                    Text("Chưa có danh sách phát",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp))
                } else {
                    playlists.forEach { pl ->
                        TextButton(onClick = { onSelect(pl.name) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(pl.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}

@Composable
private fun SongContextMenuDialog(
    song: MusicItem,
    playlists: List<PlaylistInfo>,
    onAddToPlaylist: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                TextButton(onClick = { onAddToPlaylist() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Thêm vào danh sách phát")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}
