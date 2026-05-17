package com.example.mediapreview.ui.gallery

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.content.res.Configuration
import coil3.compose.AsyncImage
import com.example.mediapreview.data.GalleryItem
import com.example.mediapreview.ui.music.MusicScreen
import com.example.mediapreview.ui.music.MusicViewModel
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.platform.LocalConfiguration

// ── Screen ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    musicViewModel: MusicViewModel,
    onItemClick: (GalleryItem) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val useNavigationRail = isLandscape
    var permissionGranted by remember { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    // ── Folder lock dialog states ──────────────────────────────────────────
    var longPressedFolder by remember { mutableStateOf<String?>(null) }    // context menu
    var folderToSetPassword by remember { mutableStateOf<String?>(null) }  // set-password dialog
    var folderToRemoveLock by remember { mutableStateOf<String?>(null) }   // remove-lock dialog
    // Storage permission flow: if permission missing, save pending folder here
    var pendingLockAfterPermission by remember { mutableStateOf<String?>(null) }
    var showStoragePermissionDialog by remember { mutableStateOf(false) }

    // When returning from system Settings, auto-continue locking if permission was granted
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val pending = pendingLockAfterPermission
            if (pending != null && Environment.isExternalStorageManager()) {
                folderToSetPassword = pending
                pendingLockAfterPermission = null
            }
            // Reload media mỗi lần app quay lại foreground (ví dụ: sau khi chụp ảnh/quay video)
            if (permissionGranted) {
                viewModel.loadMedia()
            }
        }
    }

    // ── Permission launcher ────────────────────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results.values.any { it }
        if (permissionGranted) viewModel.loadMedia()
    }

    // ── Delete launcher ────────────────────────────────────────────────────
    var pendingDeleteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onDeleteCompleted(pendingDeleteIds)
            pendingDeleteIds = emptySet()
        }
    }

    // ── Collect events ─────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is GalleryEvent.RequestShare -> {
                    val uris = event.items.map { it.uri }
                    val intent = Intent().apply {
                        action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
                        type = "*/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (uris.size == 1) {
                            putExtra(Intent.EXTRA_STREAM, uris[0])
                            clipData = ClipData.newRawUri(null, uris[0])
                        } else {
                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            clipData = ClipData.newRawUri(null, uris[0]).also { cd ->
                                uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
                            }
                        }
                    }
                    context.startActivity(Intent.createChooser(intent, "Chia sẻ"))
                }
                is GalleryEvent.RequestDelete -> {
                    val uris = event.items.map { it.uri }
                    pendingDeleteIds = event.items.map { it.id }.toSet()
                    val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        )
    }

    // ── Back handler ───────────────────────────────────────────────────────
    androidx.activity.compose.BackHandler(enabled = state.selectionMode) {
        viewModel.clearSelection()
    }
    androidx.activity.compose.BackHandler(enabled = searchActive) {
        searchActive = false
        searchText = ""
        viewModel.setSearchQuery("")
    }
    val inFolder = state.selectedFolder != null
    androidx.activity.compose.BackHandler(enabled = inFolder && !state.selectionMode && !searchActive) {
        viewModel.selectFolder(null)
    }

    // ── Password dialogs ───────────────────────────────────────────────────

    // Dialog: enter password to open a locked folder
    state.pendingPasswordFolder?.let { folderName ->
        EnterPasswordDialog(
            title = "Mở thư mục đã khóa",
            message = "Nhập mật khẩu để xem nội dung của \"$folderName\"",
            confirmLabel = "Mở",
            onConfirm = { pwd ->
                if (viewModel.verifyFolderPassword(folderName, pwd)) {
                    viewModel.openLockedFolder(folderName)
                    true
                } else false
            },
            onDismiss = { viewModel.clearPendingPasswordFolder() }
        )
    }

    // Dialog: set a new password to lock a folder
    folderToSetPassword?.let { folderName ->
        SetPasswordDialog(
            folderName = folderName,
            onConfirm = { pwd ->
                viewModel.lockFolder(folderName, pwd)
                folderToSetPassword = null
            },
            onDismiss = { folderToSetPassword = null }
        )
    }

    // Dialog: enter password to permanently remove the lock
    folderToRemoveLock?.let { folderName ->
        EnterPasswordDialog(
            title = "Gỡ khóa thư mục",
            message = "Nhập mật khẩu hiện tại để gỡ khóa \"$folderName\"",
            confirmLabel = "Gỡ khóa",
            onConfirm = { pwd ->
                if (viewModel.permanentlyUnlockFolder(folderName, pwd)) {
                    folderToRemoveLock = null
                    true
                } else false
            },
            onDismiss = { folderToRemoveLock = null }
        )
    }

    // Dialog: folder context menu (long press on folder card)
    longPressedFolder?.let { folderName ->
        val isLocked = folderName in state.lockedFolderNames
        AlertDialog(
            onDismissRequest = { longPressedFolder = null },
            title = { Text(folderName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (isLocked) {
                        TextButton(
                            onClick = {
                                longPressedFolder = null
                                folderToRemoveLock = folderName
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Gỡ khóa thư mục")
                        }
                    } else {
                        TextButton(
                            onClick = {
                                longPressedFolder = null
                                if (Environment.isExternalStorageManager()) {
                                    folderToSetPassword = folderName
                                } else {
                                    pendingLockAfterPermission = folderName
                                    showStoragePermissionDialog = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Khóa bằng mật khẩu")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { longPressedFolder = null }) { Text("Đóng") }
            }
        )
    }

    // Dialog: explain why MANAGE_EXTERNAL_STORAGE is needed
    if (showStoragePermissionDialog) {
        ManageStoragePermissionDialog(
            context = context,
            onDismiss = {
                showStoragePermissionDialog = false
                pendingLockAfterPermission = null
            }
        )
    }

    Scaffold(
        topBar = {
            when {
                state.selectionMode -> SelectionTopBar(
                    count = state.selectedIds.size,
                    total = state.displayItems.filterIsInstance<GalleryListItem.Media>().size,
                    onClose = { viewModel.clearSelection() },
                    onSelectAll = { viewModel.selectAll() }
                )
                searchActive -> SearchTopBar(
                    query = searchText,
                    onQueryChange = { q ->
                        searchText = q
                        viewModel.setSearchQuery(q)
                    },
                    onClose = {
                        searchActive = false
                        searchText = ""
                        viewModel.setSearchQuery("")
                    }
                )
                else -> MainTopBar(
                    state = state,
                    inFolder = inFolder,
                    showModeMenu = showModeMenu,
                    onShowModeMenu = { showModeMenu = true },
                    onDismissModeMenu = { showModeMenu = false },
                    onSetDisplayMode = { viewModel.setDisplayMode(it); showModeMenu = false },
                    onBack = { viewModel.selectFolder(null) },
                    onSearch = { searchActive = true }
                )
            }
        },
        bottomBar = {
            when {
                state.selectionMode -> SelectionBottomBar(
                    onShare = { viewModel.requestShareSelected() },
                    onFavorite = { viewModel.addSelectedToFavorites() },
                    onDelete = { viewModel.requestDeleteSelected() }
                )
                !searchActive && !inFolder && !useNavigationRail -> GlobalNavigationBar(
                    currentTab = state.navigationTab,
                    onTabSelected = { tab ->
                        viewModel.setNavigationTab(tab)
                        if (tab == NavigationTab.MUSIC) musicViewModel.loadMusic()
                    }
                )
            }
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            // Navigation Rail for landscape / tablet
            if (useNavigationRail && !state.selectionMode && !searchActive && !inFolder) {
                GlobalNavigationRail(
                    currentTab = state.navigationTab,
                    onTabSelected = { tab ->
                        viewModel.setNavigationTab(tab)
                        if (tab == NavigationTab.MUSIC) musicViewModel.loadMusic()
                    }
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {

            // ── Media-type filter tabs (only in PHOTOS tab, not inside a folder) ──
            if (!searchActive && !state.selectionMode
                && state.navigationTab == NavigationTab.PHOTOS && !inFolder
            ) {
                val selectedTabIndex = when (state.mediaFilter) {
                    MediaTypeFilter.ALL    -> 0
                    MediaTypeFilter.IMAGES -> 1
                    MediaTypeFilter.VIDEOS -> 2
                }
                MediaTypeFilterRow(
                    selectedIndex = selectedTabIndex,
                    onAll    = { viewModel.setMediaFilterFromTab(MediaTypeFilter.ALL) },
                    onImages = { viewModel.setMediaFilterFromTab(MediaTypeFilter.IMAGES) },
                    onVideos = { viewModel.setMediaFilterFromTab(MediaTypeFilter.VIDEOS) }
                )
                HorizontalDivider(thickness = 1.dp)
            }

            // ── Content ──────────────────────────────────────────────────────
            AnimatedContent(
                targetState = state.navigationTab,
                transitionSpec = {
                    val direction = targetState.ordinal - initialState.ordinal
                    (slideInHorizontally { if (direction > 0) it else -it } + fadeIn()) togetherWith
                            (slideOutHorizontally { if (direction > 0) -it / 3 else it / 3 } + fadeOut(targetAlpha = 0.5f))
                },
                label = "tab_content"
            ) { tab ->
                when (tab) {
                    NavigationTab.MUSIC -> MusicScreen(viewModel = musicViewModel)
                    else -> when {
                        state.isLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        !permissionGranted && state.rawAllMedia.isEmpty() && !state.isLoading -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.LockOpen, null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = "Cần cấp quyền truy cập bộ nhớ\nđể xem ảnh và video.",
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                    Button(onClick = {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.READ_MEDIA_IMAGES,
                                                Manifest.permission.READ_MEDIA_VIDEO,
                                                Manifest.permission.READ_MEDIA_AUDIO,
                                            )
                                        )
                                    }) { Text("Cấp quyền") }
                                }
                            }
                        }
                        state.error != null -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Lỗi tải media:\n${state.error}",
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                        state.displayItems.isEmpty() -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = when (state.navigationTab) {
                                            NavigationTab.FAVORITES -> Icons.Default.FavoriteBorder
                                            NavigationTab.ALBUMS -> Icons.Default.PhotoAlbum
                                            else -> Icons.Default.PhotoLibrary
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = when (state.navigationTab) {
                                            NavigationTab.FAVORITES -> "Chưa có ảnh yêu thích"
                                            else -> if (state.searchQuery.isNotBlank()) "Không tìm thấy kết quả"
                                            else "Không tìm thấy ảnh hoặc video nào."
                                        },
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        else -> {
                            val isFolderList = (state.navigationTab == NavigationTab.ALBUMS ||
                                    state.displayMode == DisplayMode.FOLDER) && state.selectedFolder == null
                            MediaGrid(
                                items = state.displayItems,
                                isFolderList = isFolderList,
                                selectionMode = state.selectionMode,
                                selectedIds = state.selectedIds,
                                favoriteIds = state.favoriteIds,
                                onItemClick = { item ->
                                    if (state.selectionMode) {
                                        viewModel.toggleItemSelection(item.id)
                                    } else {
                                        onItemClick(item)
                                    }
                                },
                                onItemLongClick = { item ->
                                    if (!state.selectionMode) viewModel.enterSelectionMode(item.id)
                                },
                                onFolderClick = { viewModel.selectFolder(it) },
                                onFolderLongClick = { folderName -> longPressedFolder = folderName }
                            )
                        }
                    }
                }
            }
            } // end Column (weight)
        } // end Row
    } // end Scaffold
}

// ── Top bars ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    state: GalleryState,
    inFolder: Boolean,
    showModeMenu: Boolean,
    onShowModeMenu: () -> Unit,
    onDismissModeMenu: () -> Unit,
    onSetDisplayMode: (DisplayMode) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    val canChangeDisplayMode = !inFolder &&
            state.navigationTab != NavigationTab.ALBUMS &&
            state.navigationTab != NavigationTab.MUSIC

    TopAppBar(
        navigationIcon = {
            if (inFolder) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                }
            }
        },
        title = {
            Text(
                    when {
                        inFolder -> state.selectedFolder ?: ""
                        state.navigationTab == NavigationTab.ALBUMS -> "Album"
                        state.navigationTab == NavigationTab.FAVORITES -> "Yêu thích"
                        state.navigationTab == NavigationTab.MUSIC -> "Nhạc"
                        else -> when (state.mediaFilter) {
                        MediaTypeFilter.ALL    -> "Thư viện"
                        MediaTypeFilter.IMAGES -> "Ảnh"
                        MediaTypeFilter.VIDEOS -> "Video"
                    }
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Tìm kiếm")
            }
            if (canChangeDisplayMode) {
                Box {
                    IconButton(onClick = onShowModeMenu) {
                        Icon(state.displayMode.icon(), contentDescription = "Chế độ hiển thị")
                    }
                    DropdownMenu(expanded = showModeMenu, onDismissRequest = onDismissModeMenu) {
                            DisplayModeMenuItem("Mới đến cũ", DisplayMode.FLAT.icon(),
                                state.displayMode == DisplayMode.FLAT) { onSetDisplayMode(DisplayMode.FLAT) }
                            DisplayModeMenuItem("Theo ngày", DisplayMode.DATE.icon(),
                                state.displayMode == DisplayMode.DATE) { onSetDisplayMode(DisplayMode.DATE) }
                        }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    total: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Bỏ chọn")
            }
        },
        title = {
            Text(
                if (count == 0) "Chọn mục" else "$count đã chọn",
                fontWeight = FontWeight.SemiBold
            )
        },
        actions = {
            TextButton(onClick = onSelectAll) {
                Text(if (count == total && total > 0) "Bỏ chọn tất cả" else "Chọn tất cả")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Đóng tìm kiếm")
            }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Tìm kiếm ảnh, thư mục...") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Xóa")
                }
            }
        }
    )
}

// ── Bottom bars ────────────────────────────────────────────────────────────

@Composable
private fun GlobalNavigationBar(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = currentTab == NavigationTab.PHOTOS,
            onClick = { onTabSelected(NavigationTab.PHOTOS) },
            icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = "Thư viện") },
            label = { Text("Thư viện") }
        )
        NavigationBarItem(
            selected = currentTab == NavigationTab.ALBUMS,
            onClick = { onTabSelected(NavigationTab.ALBUMS) },
            icon = { Icon(Icons.Default.PhotoAlbum, contentDescription = "Album") },
            label = { Text("Album") }
        )
        NavigationBarItem(
            selected = currentTab == NavigationTab.FAVORITES,
            onClick = { onTabSelected(NavigationTab.FAVORITES) },
            icon = { Icon(Icons.Default.FavoriteBorder, contentDescription = "Yêu thích") },
            label = { Text("Yêu thích") }
        )
        NavigationBarItem(
            selected = currentTab == NavigationTab.MUSIC,
            onClick = { onTabSelected(NavigationTab.MUSIC) },
            icon = { Icon(Icons.Default.MusicNote, contentDescription = "Nhạc") },
            label = { Text("Nhạc") }
        )
    }
}

@Composable
private fun GlobalNavigationRail(
    currentTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
) {
    NavigationRail(
        containerColor = NavigationBarDefaults.containerColor,
    ) {
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = currentTab == NavigationTab.PHOTOS,
            onClick = { onTabSelected(NavigationTab.PHOTOS) },
            icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = "Thư viện") },
            label = { Text("Thư viện") }
        )
        NavigationRailItem(
            selected = currentTab == NavigationTab.ALBUMS,
            onClick = { onTabSelected(NavigationTab.ALBUMS) },
            icon = { Icon(Icons.Default.PhotoAlbum, contentDescription = "Album") },
            label = { Text("Album") }
        )
        NavigationRailItem(
            selected = currentTab == NavigationTab.FAVORITES,
            onClick = { onTabSelected(NavigationTab.FAVORITES) },
            icon = { Icon(Icons.Default.FavoriteBorder, contentDescription = "Yêu thích") },
            label = { Text("Yêu thích") }
        )
        NavigationRailItem(
            selected = currentTab == NavigationTab.MUSIC,
            onClick = { onTabSelected(NavigationTab.MUSIC) },
            icon = { Icon(Icons.Default.MusicNote, contentDescription = "Nhạc") },
            label = { Text("Nhạc") }
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MediaTypeFilterRow(
    selectedIndex: Int,
    onAll: () -> Unit,
    onImages: () -> Unit,
    onVideos: () -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 8.dp,
        divider = {}
    ) {
        Tab(selected = selectedIndex == 0, onClick = onAll,
            text = { Text("Tất cả", style = MaterialTheme.typography.labelMedium) })
        Tab(selected = selectedIndex == 1, onClick = onImages,
            text = { Text("Ảnh", style = MaterialTheme.typography.labelMedium) })
        Tab(selected = selectedIndex == 2, onClick = onVideos,
            text = { Text("Video", style = MaterialTheme.typography.labelMedium) })
    }
}

@Composable
private fun SelectionBottomBar(
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SelectionAction(icon = Icons.Default.Share, label = "Chia sẻ", onClick = onShare)
            SelectionAction(icon = Icons.Default.FavoriteBorder, label = "Yêu thích", onClick = onFavorite)
            SelectionAction(icon = Icons.Default.Delete, label = "Xóa", onClick = onDelete)
        }
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = label)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}


// ── Helpers ────────────────────────────────────────────────────────────────

private fun DisplayMode.icon(): ImageVector = when (this) {
    DisplayMode.FLAT   -> Icons.Default.GridView
    DisplayMode.DATE   -> Icons.Default.CalendarMonth
    DisplayMode.FOLDER -> Icons.Default.Folder
}

@Composable
private fun DisplayModeMenuItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text          = { Text(label) },
        leadingIcon   = { Icon(icon, contentDescription = label) },
        trailingIcon  = if (selected) ({ Icon(Icons.Default.Check, contentDescription = "Đang chọn") }) else null,
        onClick       = onClick
    )
}

// ── Grid ───────────────────────────────────────────────────────────────────

@Composable
private fun MediaGrid(
    items: List<GalleryListItem>,
    isFolderList: Boolean,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    favoriteIds: Set<Long>,
    onItemClick: (GalleryItem) -> Unit,
    onItemLongClick: (GalleryItem) -> Unit,
    onFolderClick: (String) -> Unit,
    onFolderLongClick: (String) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = when {
        isFolderList -> if (isLandscape) 4 else 3
        else         -> if (isLandscape) 6 else 4
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            start  = if (isFolderList) 6.dp else 0.dp,
            end    = if (isFolderList) 6.dp else 0.dp,
            top    = if (isFolderList) 6.dp else 0.dp,
            bottom = 8.dp
        ),
        horizontalArrangement = if (isFolderList) Arrangement.spacedBy(6.dp) else Arrangement.spacedBy(0.dp),
        verticalArrangement   = if (isFolderList) Arrangement.spacedBy(6.dp) else Arrangement.spacedBy(0.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items.forEachIndexed { index, listItem ->
            when (listItem) {
                is GalleryListItem.Header -> {
                    item(key = "header_${listItem.label}_$index", span = { GridItemSpan(maxLineSpan) }) {
                        GroupHeader(label = listItem.label, count = listItem.count)
                    }
                }
                is GalleryListItem.Media -> {
                    val media = listItem.item
                    item(key = "${media.isVideo}_${media.id}") {
                        GalleryItemCell(
                            item = media,
                            isSelectionMode = selectionMode,
                            isSelected = media.id in selectedIds,
                            isFavorite = media.id in favoriteIds,
                            onClick = { onItemClick(media) },
                            onLongClick = { onItemLongClick(media) }
                        )
                    }
                }
                is GalleryListItem.FolderCard -> {
                    item(key = "folder_${listItem.folderName}") {
                        FolderCardItem(
                            folderName   = listItem.folderName,
                            count        = listItem.count,
                            previewItems = listItem.previewItems,
                            isLocked     = listItem.isLocked,
                            onClick      = { onFolderClick(listItem.folderName) },
                            onLongClick  = { onFolderLongClick(listItem.folderName) }
                        )
                    }
                }
            }
        }
    }
}

// ── Folder card ────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderCardItem(
    folderName: String,
    count: Int,
    previewItems: List<GalleryItem>,
    isLocked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier  = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape     = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (isLocked) {
                    // Locked: show a dark gradient background with large lock icon
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Thư mục đã khóa",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    // Small lock badge bottom-start
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .size(20.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Lock, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                } else {
                    if (previewItems.size >= 4) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                AsyncImage(model = previewItems[0].uri, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                                Spacer(Modifier.width(1.dp))
                                AsyncImage(model = previewItems[1].uri, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                            Spacer(Modifier.height(1.dp))
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                AsyncImage(model = previewItems[2].uri, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                                Spacer(Modifier.width(1.dp))
                                AsyncImage(model = previewItems[3].uri, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                    } else {
                        AsyncImage(model = previewItems.firstOrNull()?.uri, contentDescription = null,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Icon(Icons.Default.Folder, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp))
                }
            }
            Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = folderName,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = if (isLocked) "🔒 $count mục" else "$count mục",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ── Section header ─────────────────────────────────────────────────────────

@Composable
fun GroupHeader(label: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(text = "$count", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Media cell ─────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryItemCell(
    item: GalleryItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = item.uri, contentDescription = item.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )

        // Video badge
        if (item.isVideo) {
            Box(
                modifier = Modifier.align(Alignment.Center).size(36.dp)
                    .background(Color.Black.copy(alpha = 0.50f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Video",
                    tint = Color.White, modifier = Modifier.size(22.dp))
            }
            if (item.duration > 0) {
                Text(
                    text = formatDuration(item.duration),
                    color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }
        }

        // Favorite heart
        AnimatedVisibility(
            visible = isFavorite && !isSelectionMode,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
        ) {
            Icon(Icons.Default.Favorite, contentDescription = "Yêu thích",
                tint = Color(0xFFFF4081), modifier = Modifier.size(14.dp))
        }

        // Selection overlay + checkbox
        AnimatedVisibility(
            visible = isSelectionMode,
            enter = fadeIn(), exit = fadeOut()
        ) {
            Box(Modifier.fillMaxSize()) {
                // Dim overlay for unselected
                if (!isSelected) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
                }
                // Checkbox circle (top-right)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(22.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(0.85f),
                            CircleShape
                        )
                        .border(
                            width = if (isSelected) 0.dp else 1.5.dp,
                            color = if (isSelected) Color.Transparent else Color.Gray,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours   = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/**
 * Dialog to explain why MANAGE_EXTERNAL_STORAGE is needed and open system Settings.
 */
@Composable
private fun ManageStoragePermissionDialog(
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Storage, contentDescription = null) },
        title = { Text("Cần quyền quản lý bộ nhớ") },
        text = {
            Text(
                "Để ẩn hoàn toàn các ảnh và video với tất cả ứng dụng khác khi khóa thư mục, " +
                "ứng dụng cần quyền \"Quản lý tất cả file\".\n\n" +
                "Nhấn \"Đi đến Cài đặt\", bật quyền này, rồi quay lại để tiếp tục khóa thư mục.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                onDismiss()
            }) { Text("Đi đến Cài đặt") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Để sau") }
        }
    )
}

// ── Password Dialogs ────────────────────────────────────────────────────────

/**
 * Dialog to set a new password for locking a folder.
 * Requires the user to enter and confirm the password.
 */
@Composable
private fun SetPasswordDialog(
    folderName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text("Khóa thư mục") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Đặt mật khẩu để khóa \"$folderName\". Ảnh và video sẽ bị ẩn ở tất cả các nơi khác.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMsg = null },
                    label = { Text("Mật khẩu") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; errorMsg = null },
                    label = { Text("Xác nhận mật khẩu") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMsg != null) {
                    Text(
                        text = errorMsg!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    password.isBlank() -> errorMsg = "Mật khẩu không được để trống."
                    password.length < 4 -> errorMsg = "Mật khẩu phải có ít nhất 4 ký tự."
                    password != confirmPassword -> errorMsg = "Mật khẩu xác nhận không khớp."
                    else -> onConfirm(password)
                }
            }) { Text("Khóa") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

/**
 * Dialog to enter a password (for opening a locked folder or removing lock).
 * [onConfirm] returns true if password is correct, false to show error.
 */
@Composable
private fun EnterPasswordDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; showError = false },
                    label = { Text("Mật khẩu") },
                    singleLine = true,
                    isError = showError,
                    visualTransformation = if (showPassword) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (showError) {
                    Text(
                        text = "Mật khẩu không đúng. Vui lòng thử lại.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!onConfirm(password)) {
                    showError = true
                    password = ""
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

