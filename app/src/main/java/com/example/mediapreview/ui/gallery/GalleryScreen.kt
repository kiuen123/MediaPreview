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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import android.content.res.Configuration
import coil3.compose.AsyncImage
import com.example.mediapreview.data.GalleryItem
import com.example.mediapreview.ui.music.MusicScreen
import com.example.mediapreview.ui.music.MusicViewModel
import com.example.mediapreview.util.BiometricHelper
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.platform.LocalConfiguration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    musicViewModel: MusicViewModel,
    onOpenSettings: () -> Unit = {},
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

    // ── Custom album dialog states ─────────────────────────────────────────
    var showCreateAlbumDialog by remember { mutableStateOf(false) }
    var longPressedCustomAlbum by remember { mutableStateOf<String?>(null) }
    var showAddToAlbumDialog by remember { mutableStateOf(false) }

    // ── Folder lock dialog states ──────────────────────────────────────────
    var longPressedFolder by remember { mutableStateOf<String?>(null) }
    var folderToSetPassword by remember { mutableStateOf<String?>(null) }
    var folderToRemoveLock by remember { mutableStateOf<String?>(null) }
    var folderToChangePassword by remember { mutableStateOf<String?>(null) }
    var pendingLockAfterPermission by remember { mutableStateOf<String?>(null) }
    var showStoragePermissionDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val pending = pendingLockAfterPermission
            if (pending != null && Environment.isExternalStorageManager()) {
                folderToSetPassword = pending
                pendingLockAfterPermission = null
            }
            if (permissionGranted) viewModel.loadMedia()
        }
    }

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

    // ── Trash launcher ─────────────────────────────────────────────────────
    var pendingTrashIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onTrashCompleted(pendingTrashIds)
            pendingTrashIds = emptySet()
        }
    }

    // ── Restore launcher ───────────────────────────────────────────────────
    var pendingRestoreIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onRestoreCompleted(pendingRestoreIds)
            pendingRestoreIds = emptySet()
        }
    }

    // ── Collect events ─────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
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
                is GalleryEvent.RequestTrash -> {
                    val uris = event.items.map { it.uri }
                    pendingTrashIds = event.items.map { it.id }.toSet()
                    val request = MediaStore.createTrashRequest(context.contentResolver, uris, true)
                    trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
                is GalleryEvent.RequestRestore -> {
                    val uris = event.items.map { it.uri }
                    pendingRestoreIds = event.items.map { it.id }.toSet()
                    val request = MediaStore.createTrashRequest(context.contentResolver, uris, false)
                    restoreLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        ))
    }

    // ── Back handlers ──────────────────────────────────────────────────────
    androidx.activity.compose.BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }
    androidx.activity.compose.BackHandler(enabled = searchActive) {
        searchActive = false; searchText = ""; viewModel.setSearchQuery("")
    }
    val inFolder = state.selectedFolder != null
    val inCustomAlbum = state.selectedCustomAlbum != null
    androidx.activity.compose.BackHandler(enabled = inCustomAlbum && !state.selectionMode && !searchActive) {
        viewModel.selectCustomAlbum(null)
    }
    androidx.activity.compose.BackHandler(enabled = inFolder && !state.selectionMode && !searchActive) {
        viewModel.selectFolder(null)
    }

    // ── Password dialogs ───────────────────────────────────────────────────

    state.pendingPasswordFolder?.let { folderName ->
        EnterPasswordDialog(
            title = "Mở thư mục đã khóa",
            message = "Nhập mật khẩu để xem nội dung của \"$folderName\"",
            confirmLabel = "Mở",
            onConfirm = { pwd ->
                if (viewModel.verifyFolderPassword(folderName, pwd)) { viewModel.openLockedFolder(folderName); true }
                else false
            },
            onBiometricAuth = if (BiometricHelper.isAvailable(context)) ({
                (context as? FragmentActivity)?.let { activity ->
                    BiometricHelper.authenticate(activity, "Mở thư mục \"$folderName\"",
                        onSuccess = { viewModel.openLockedFolder(folderName) },
                        onError = { /* ignore */ }
                    )
                }
            }) else null,
            onDismiss = { viewModel.clearPendingPasswordFolder() }
        )
    }

    folderToSetPassword?.let { folderName ->
        SetPasswordDialog(folderName = folderName,
            onConfirm = { pwd -> viewModel.lockFolder(folderName, pwd); folderToSetPassword = null },
            onDismiss = { folderToSetPassword = null })
    }

    folderToChangePassword?.let { folderName ->
        ChangePasswordDialog(folderName = folderName,
            onConfirm = { oldPwd, newPwd ->
                if (viewModel.changeFolderPassword(folderName, oldPwd, newPwd)) { folderToChangePassword = null; true }
                else false
            },
            onDismiss = { folderToChangePassword = null })
    }

    folderToRemoveLock?.let { folderName ->
        EnterPasswordDialog(
            title = "Gỡ khóa thư mục",
            message = "Nhập mật khẩu hiện tại để gỡ khóa \"$folderName\"",
            confirmLabel = "Gỡ khóa",
            onConfirm = { pwd ->
                if (viewModel.permanentlyUnlockFolder(folderName, pwd)) { folderToRemoveLock = null; true }
                else false
            },
            onBiometricAuth = null,
            onDismiss = { folderToRemoveLock = null }
        )
    }

    longPressedFolder?.let { folderName ->
        val isLocked = folderName in state.lockedFolderNames
        val isPinned = folderName in state.pinnedFolderNames
        AlertDialog(
            onDismissRequest = { longPressedFolder = null },
            title = { Text(folderName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = { viewModel.togglePinFolder(folderName); longPressedFolder = null },
                        modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PushPin, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isPinned) "Bỏ ghim thư mục" else "Ghim thư mục lên đầu")
                    }
                    if (isLocked) {
                        TextButton(onClick = { longPressedFolder = null; folderToChangePassword = folderName },
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Key, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("Đổi mật khẩu")
                        }
                        TextButton(onClick = { longPressedFolder = null; folderToRemoveLock = folderName },
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("Gỡ khóa thư mục")
                        }
                    } else {
                        TextButton(onClick = {
                            longPressedFolder = null
                            if (Environment.isExternalStorageManager()) folderToSetPassword = folderName
                            else { pendingLockAfterPermission = folderName; showStoragePermissionDialog = true }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Lock, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("Khóa bằng mật khẩu")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { longPressedFolder = null }) { Text("Đóng") } }
        )
    }

    // Custom album long-press context menu
    longPressedCustomAlbum?.let { albumName ->
        AlertDialog(
            onDismissRequest = { longPressedCustomAlbum = null },
            title = { Text(albumName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                TextButton(onClick = { viewModel.deleteCustomAlbum(albumName); longPressedCustomAlbum = null },
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("Xóa album")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { longPressedCustomAlbum = null }) { Text("Đóng") } }
        )
    }

    // Create custom album dialog
    if (showCreateAlbumDialog) {
        CreateCustomAlbumDialog(
            onConfirm = { name -> viewModel.createCustomAlbum(name); showCreateAlbumDialog = false },
            onDismiss = { showCreateAlbumDialog = false }
        )
    }

    // Add to album dialog (from selection)
    if (showAddToAlbumDialog) {
        AddToCustomAlbumDialog(
            albums = state.customAlbums,
            onSelect = { albumName -> viewModel.addSelectedToCustomAlbum(albumName); showAddToAlbumDialog = false },
            onCreate = { name -> viewModel.createCustomAlbum(name); viewModel.addSelectedToCustomAlbum(name); showAddToAlbumDialog = false },
            onDismiss = { showAddToAlbumDialog = false }
        )
    }

    if (showStoragePermissionDialog) {
        ManageStoragePermissionDialog(context = context,
            onDismiss = { showStoragePermissionDialog = false; pendingLockAfterPermission = null })
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
                    onQueryChange = { q -> searchText = q; viewModel.setSearchQuery(q) },
                    onClose = { searchActive = false; searchText = ""; viewModel.setSearchQuery("") }
                )
                else -> MainTopBar(
                    state = state,
                    inFolder = inFolder,
                    inCustomAlbum = inCustomAlbum,
                    showModeMenu = showModeMenu,
                    onShowModeMenu = { showModeMenu = true },
                    onDismissModeMenu = { showModeMenu = false },
                    onSetDisplayMode = { viewModel.setDisplayMode(it); showModeMenu = false },
                    onBack = {
                        if (inCustomAlbum) viewModel.selectCustomAlbum(null)
                        else viewModel.selectFolder(null)
                    },
                    onSearch = { searchActive = true },
                    onSettings = onOpenSettings
                )
            }
        },
        floatingActionButton = {
            // FAB for creating custom album in Albums tab (top-level, not in folder)
            if (!state.selectionMode && !searchActive
                && state.navigationTab == NavigationTab.ALBUMS
                && state.selectedFolder == null && state.selectedCustomAlbum == null
            ) {
                FloatingActionButton(onClick = { showCreateAlbumDialog = true }) {
                    Icon(Icons.Default.CreateNewFolder, "Tạo album tùy chỉnh")
                }
            }
        },
        bottomBar = {
            when {
                state.selectionMode -> {
                    if (state.navigationTab == NavigationTab.TRASH) {
                        TrashSelectionBottomBar(
                            onRestore = { viewModel.requestRestoreSelected() },
                            onDelete = { viewModel.requestDeleteSelected() }
                        )
                    } else {
                        SelectionBottomBar(
                            onShare = { viewModel.requestShareSelected() },
                            onFavorite = { viewModel.addSelectedToFavorites() },
                            onAddToAlbum = { showAddToAlbumDialog = true },
                            onTrash = { viewModel.requestTrashSelected() },
                            onDelete = { viewModel.requestDeleteSelected() }
                        )
                    }
                }
                !searchActive && !inFolder && !inCustomAlbum && !useNavigationRail -> GlobalNavigationBar(
                    currentTab = state.navigationTab,
                    onTabSelected = { tab ->
                        viewModel.setNavigationTab(tab)
                        if (tab == NavigationTab.MUSIC) musicViewModel.loadMusic()
                    }
                )
            }
        }
    ) { paddingValues ->
        Row(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (useNavigationRail && !state.selectionMode && !searchActive && !inFolder && !inCustomAlbum) {
                GlobalNavigationRail(
                    currentTab = state.navigationTab,
                    onTabSelected = { tab ->
                        viewModel.setNavigationTab(tab)
                        if (tab == NavigationTab.MUSIC) musicViewModel.loadMusic()
                    }
                )
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Media-type filter tabs (Photos tab, not inside folder)
                if (!searchActive && !state.selectionMode
                    && state.navigationTab == NavigationTab.PHOTOS && !inFolder) {
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
                        NavigationTab.TRASH -> TrashContent(state = state, viewModel = viewModel,
                            onItemClick = onItemClick)
                        else -> when {
                            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            !permissionGranted && state.rawAllMedia.isEmpty() && !state.isLoading ->
                                PermissionPlaceholder { permissionLauncher.launch(arrayOf(
                                    Manifest.permission.READ_MEDIA_IMAGES,
                                    Manifest.permission.READ_MEDIA_VIDEO,
                                    Manifest.permission.READ_MEDIA_AUDIO,
                                )) }
                            state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Lỗi tải media:\n${state.error}", textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(16.dp))
                            }
                            state.displayItems.isEmpty() -> EmptyPlaceholder(state)
                            else -> {
                                val isFolderList = (state.navigationTab == NavigationTab.ALBUMS ||
                                        state.displayMode == DisplayMode.FOLDER) && state.selectedFolder == null
                                        && state.selectedCustomAlbum == null
                                MediaGrid(
                                    items = state.displayItems,
                                    isFolderList = isFolderList,
                                    selectionMode = state.selectionMode,
                                    selectedIds = state.selectedIds,
                                    favoriteIds = state.favoriteIds,
                                    onItemClick = { item ->
                                        if (state.selectionMode) viewModel.toggleItemSelection(item.id)
                                        else onItemClick(item)
                                    },
                                    onItemLongClick = { item ->
                                        if (!state.selectionMode) viewModel.enterSelectionMode(item.id)
                                    },
                                    onFolderClick = { viewModel.selectFolder(it) },
                                    onFolderLongClick = { folderName -> longPressedFolder = folderName },
                                    onCustomAlbumClick = { viewModel.selectCustomAlbum(it) },
                                    onCustomAlbumLongClick = { longPressedCustomAlbum = it }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Trash content ──────────────────────────────────────────────────────────────

@Composable
private fun TrashContent(state: GalleryState, viewModel: GalleryViewModel, onItemClick: (GalleryItem) -> Unit) {
    when {
        state.isLoadingTrash -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.displayItems.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp))
                Text("Thùng rác trống", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Các mục trong thùng rác sẽ bị xóa sau 30 ngày",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            }
        }
        else -> MediaGrid(
            items = state.displayItems, isFolderList = false,
            selectionMode = state.selectionMode, selectedIds = state.selectedIds,
            favoriteIds = emptySet(),
            onItemClick = { item ->
                if (state.selectionMode) viewModel.toggleItemSelection(item.id)
                else viewModel.enterSelectionMode(item.id)
            },
            onItemLongClick = { item -> if (!state.selectionMode) viewModel.enterSelectionMode(item.id) },
            onFolderClick = {}, onFolderLongClick = {},
            onCustomAlbumClick = {}, onCustomAlbumLongClick = {}
        )
    }
}

// ── Placeholders ───────────────────────────────────────────────────────────────

@Composable
private fun PermissionPlaceholder(onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Cần cấp quyền truy cập bộ nhớ\nđể xem ảnh và video.",
                textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            Button(onClick = onGrant) { Text("Cấp quyền") }
        }
    }
}

@Composable
private fun EmptyPlaceholder(state: GalleryState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = when (state.navigationTab) {
                    NavigationTab.FAVORITES -> Icons.Default.FavoriteBorder
                    NavigationTab.ALBUMS -> Icons.Default.PhotoAlbum
                    else -> Icons.Default.PhotoLibrary
                }, contentDescription = null, modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (state.navigationTab) {
                    NavigationTab.FAVORITES -> "Chưa có ảnh yêu thích"
                    else -> if (state.searchQuery.isNotBlank()) "Không tìm thấy kết quả"
                    else "Không tìm thấy ảnh hoặc video nào."
                }, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Top bars ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    state: GalleryState,
    inFolder: Boolean,
    inCustomAlbum: Boolean,
    showModeMenu: Boolean,
    onShowModeMenu: () -> Unit,
    onDismissModeMenu: () -> Unit,
    onSetDisplayMode: (DisplayMode) -> Unit,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit = {},
) {
    val canChangeDisplayMode = !inFolder && !inCustomAlbum &&
            state.navigationTab != NavigationTab.ALBUMS &&
            state.navigationTab != NavigationTab.MUSIC &&
            state.navigationTab != NavigationTab.TRASH

    TopAppBar(
        navigationIcon = {
            if (inFolder || inCustomAlbum) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại")
                }
            }
        },
        title = {
            Text(when {
                inCustomAlbum -> state.selectedCustomAlbum ?: ""
                inFolder      -> state.selectedFolder ?: ""
                state.navigationTab == NavigationTab.ALBUMS    -> "Album"
                state.navigationTab == NavigationTab.FAVORITES -> "Yêu thích"
                state.navigationTab == NavigationTab.MUSIC     -> "Nhạc"
                state.navigationTab == NavigationTab.TRASH     -> "Thùng rác"
                else -> when (state.mediaFilter) {
                    MediaTypeFilter.ALL    -> "Thư viện"
                    MediaTypeFilter.IMAGES -> "Ảnh"
                    MediaTypeFilter.VIDEOS -> "Video"
                }
            }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        },
        actions = {
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "Tìm kiếm") }
            if (canChangeDisplayMode) {
                Box {
                    IconButton(onClick = onShowModeMenu) { Icon(state.displayMode.icon(), "Chế độ hiển thị") }
                    DropdownMenu(expanded = showModeMenu, onDismissRequest = onDismissModeMenu) {
                        DisplayModeMenuItem("Mới đến cũ", DisplayMode.FLAT.icon(), state.displayMode == DisplayMode.FLAT) { onSetDisplayMode(DisplayMode.FLAT) }
                        DisplayModeMenuItem("Theo ngày", DisplayMode.DATE.icon(), state.displayMode == DisplayMode.DATE) { onSetDisplayMode(DisplayMode.DATE) }
                        DisplayModeMenuItem("Theo tháng", DisplayMode.MONTH.icon(), state.displayMode == DisplayMode.MONTH) { onSetDisplayMode(DisplayMode.MONTH) }
                        DisplayModeMenuItem("Theo năm", DisplayMode.YEAR.icon(), state.displayMode == DisplayMode.YEAR) { onSetDisplayMode(DisplayMode.YEAR) }
                    }
                }
            }
            if (!inFolder && !inCustomAlbum) {
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Cài đặt") }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(count: Int, total: Int, onClose: () -> Unit, onSelectAll: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Bỏ chọn") } },
        title = { Text(if (count == 0) "Chọn mục" else "$count đã chọn", fontWeight = FontWeight.SemiBold) },
        actions = {
            TextButton(onClick = onSelectAll) {
                Text(if (count == total && total > 0) "Bỏ chọn tất cả" else "Chọn tất cả")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Đóng") } },
        title = {
            TextField(value = query, onValueChange = onQueryChange,
                placeholder = { Text("Tìm kiếm ảnh, thư mục...") }, singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ), modifier = Modifier.fillMaxWidth())
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Clear, "Xóa") }
        }
    )
}

// ── Bottom bars ─────────────────────────────────────────────────────────────

@Composable
private fun GlobalNavigationBar(currentTab: NavigationTab, onTabSelected: (NavigationTab) -> Unit) {
    NavigationBar {
        NavigationBarItem(selected = currentTab == NavigationTab.PHOTOS,
            onClick = { onTabSelected(NavigationTab.PHOTOS) },
            icon = { Icon(Icons.Default.PhotoLibrary, "Thư viện") }, label = { Text("Thư viện") })
        NavigationBarItem(selected = currentTab == NavigationTab.ALBUMS,
            onClick = { onTabSelected(NavigationTab.ALBUMS) },
            icon = { Icon(Icons.Default.PhotoAlbum, "Album") }, label = { Text("Album") })
        NavigationBarItem(selected = currentTab == NavigationTab.FAVORITES,
            onClick = { onTabSelected(NavigationTab.FAVORITES) },
            icon = { Icon(Icons.Default.FavoriteBorder, "Yêu thích") }, label = { Text("Yêu thích") })
        NavigationBarItem(selected = currentTab == NavigationTab.MUSIC,
            onClick = { onTabSelected(NavigationTab.MUSIC) },
            icon = { Icon(Icons.Default.MusicNote, "Nhạc") }, label = { Text("Nhạc") })
        NavigationBarItem(selected = currentTab == NavigationTab.TRASH,
            onClick = { onTabSelected(NavigationTab.TRASH) },
            icon = { Icon(Icons.Default.Delete, "Thùng rác") }, label = { Text("Thùng rác") })
    }
}

@Composable
private fun GlobalNavigationRail(currentTab: NavigationTab, onTabSelected: (NavigationTab) -> Unit) {
    NavigationRail(containerColor = NavigationBarDefaults.containerColor) {
        Spacer(Modifier.weight(1f))
        NavigationRailItem(selected = currentTab == NavigationTab.PHOTOS, onClick = { onTabSelected(NavigationTab.PHOTOS) },
            icon = { Icon(Icons.Default.PhotoLibrary, "Thư viện") }, label = { Text("Thư viện") })
        NavigationRailItem(selected = currentTab == NavigationTab.ALBUMS, onClick = { onTabSelected(NavigationTab.ALBUMS) },
            icon = { Icon(Icons.Default.PhotoAlbum, "Album") }, label = { Text("Album") })
        NavigationRailItem(selected = currentTab == NavigationTab.FAVORITES, onClick = { onTabSelected(NavigationTab.FAVORITES) },
            icon = { Icon(Icons.Default.FavoriteBorder, "Yêu thích") }, label = { Text("Yêu thích") })
        NavigationRailItem(selected = currentTab == NavigationTab.MUSIC, onClick = { onTabSelected(NavigationTab.MUSIC) },
            icon = { Icon(Icons.Default.MusicNote, "Nhạc") }, label = { Text("Nhạc") })
        NavigationRailItem(selected = currentTab == NavigationTab.TRASH, onClick = { onTabSelected(NavigationTab.TRASH) },
            icon = { Icon(Icons.Default.Delete, "Thùng rác") }, label = { Text("Thùng rác") })
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun MediaTypeFilterRow(selectedIndex: Int, onAll: () -> Unit, onImages: () -> Unit, onVideos: () -> Unit) {
    PrimaryScrollableTabRow(selectedTabIndex = selectedIndex, edgePadding = 8.dp, divider = {}) {
        Tab(selected = selectedIndex == 0, onClick = onAll, text = { Text("Tất cả", style = MaterialTheme.typography.labelMedium) })
        Tab(selected = selectedIndex == 1, onClick = onImages, text = { Text("Ảnh", style = MaterialTheme.typography.labelMedium) })
        Tab(selected = selectedIndex == 2, onClick = onVideos, text = { Text("Video", style = MaterialTheme.typography.labelMedium) })
    }
}

@Composable
private fun SelectionBottomBar(
    onShare: () -> Unit, onFavorite: () -> Unit, onAddToAlbum: () -> Unit,
    onTrash: () -> Unit, onDelete: () -> Unit
) {
    BottomAppBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SelectionAction(Icons.Default.Share, "Chia sẻ", onShare)
            SelectionAction(Icons.Default.FavoriteBorder, "Yêu thích", onFavorite)
            SelectionAction(Icons.Default.CreateNewFolder, "Vào album", onAddToAlbum)
            SelectionAction(Icons.Default.DeleteOutline, "Thùng rác", onTrash)
            SelectionAction(Icons.Default.Delete, "Xóa vĩnh viễn", onDelete)
        }
    }
}

@Composable
private fun TrashSelectionBottomBar(onRestore: () -> Unit, onDelete: () -> Unit) {
    BottomAppBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SelectionAction(Icons.Default.RestoreFromTrash, "Khôi phục", onRestore)
            SelectionAction(Icons.Default.Delete, "Xóa vĩnh viễn", onDelete)
        }
    }
}

@Composable
private fun SelectionAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1)
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun DisplayMode.icon(): ImageVector = when (this) {
    DisplayMode.FLAT   -> Icons.Default.GridView
    DisplayMode.DATE   -> Icons.Default.CalendarToday
    DisplayMode.MONTH  -> Icons.Default.CalendarMonth
    DisplayMode.YEAR   -> Icons.Default.DateRange
    DisplayMode.FOLDER -> Icons.Default.Folder
}

@Composable
private fun DisplayModeMenuItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) }, leadingIcon = { Icon(icon, label) },
        trailingIcon = if (selected) ({ Icon(Icons.Default.Check, "Đang chọn") }) else null,
        onClick = onClick
    )
}

// ── Grid ─────────────────────────────────────────────────────────────────────

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
    onCustomAlbumClick: (String) -> Unit,
    onCustomAlbumLongClick: (String) -> Unit,
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
            start = if (isFolderList) 6.dp else 0.dp, end = if (isFolderList) 6.dp else 0.dp,
            top = if (isFolderList) 6.dp else 0.dp, bottom = 8.dp),
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
                        GalleryItemCell(item = media, isSelectionMode = selectionMode,
                            isSelected = media.id in selectedIds, isFavorite = media.id in favoriteIds,
                            onClick = { onItemClick(media) }, onLongClick = { onItemLongClick(media) })
                    }
                }
                is GalleryListItem.FolderCard -> {
                    item(key = "folder_${listItem.folderName}") {
                        FolderCardItem(folderName = listItem.folderName, count = listItem.count,
                            previewItems = listItem.previewItems, isLocked = listItem.isLocked, isPinned = listItem.isPinned,
                            onClick = { onFolderClick(listItem.folderName) },
                            onLongClick = { onFolderLongClick(listItem.folderName) })
                    }
                }
                is GalleryListItem.CustomAlbumCard -> {
                    item(key = "custom_album_${listItem.albumName}") {
                        CustomAlbumCardItem(albumName = listItem.albumName, count = listItem.count,
                            previewItems = listItem.previewItems,
                            onClick = { onCustomAlbumClick(listItem.albumName) },
                            onLongClick = { onCustomAlbumLongClick(listItem.albumName) })
                    }
                }
            }
        }
    }
}

// ── Custom Album Card ─────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CustomAlbumCardItem(
    albumName: String, count: Int, previewItems: List<GalleryItem>,
    onClick: () -> Unit, onLongClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = CardDefaults.outlinedCardBorder()) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)) {
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
                } else if (previewItems.isNotEmpty()) {
                    AsyncImage(model = previewItems[0].uri, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Collections, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                    }
                }
                // Custom album badge
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f), CircleShape).size(18.dp),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Collections, null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(11.dp))
                }
            }
            Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
                Text(text = albumName, style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = "$count mục", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Folder card ──────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderCardItem(
    folderName: String, count: Int, previewItems: List<GalleryItem>,
    isLocked: Boolean, isPinned: Boolean, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(8.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (isLocked) {
                    Box(modifier = Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Lock, "Thư mục đã khóa",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(40.dp))
                    }
                    Box(modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), CircleShape),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp))
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
                    Icon(Icons.Default.Folder, null, tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp))
                }
            }
            Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = folderName, style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                }
                Text(text = if (isLocked) "🔒 $count mục" else "$count mục",
                    style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isPinned) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PushPin, null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(10.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Đã ghim", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

// ── Section header ───────────────────────────────────────────────────────────

@Composable
fun GroupHeader(label: String, count: Int) {
    Row(modifier = Modifier.fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
        Text("$count", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Media cell ───────────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryItemCell(
    item: GalleryItem, isSelectionMode: Boolean, isSelected: Boolean, isFavorite: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit
) {
    Box(modifier = Modifier.aspectRatio(1f).padding(1.dp)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        AsyncImage(model = item.uri, contentDescription = item.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (item.isVideo) {
            Box(modifier = Modifier.align(Alignment.Center).size(36.dp)
                .background(Color.Black.copy(alpha = 0.50f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(22.dp))
            }
            if (item.duration > 0) {
                Text(text = formatDuration(item.duration), color = Color.White, fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 3.dp, vertical = 1.dp))
            }
        }
        AnimatedVisibility(visible = isFavorite && !isSelectionMode, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)) {
            Icon(Icons.Default.Favorite, "Yêu thích", tint = Color(0xFFFF4081), modifier = Modifier.size(14.dp))
        }
        AnimatedVisibility(visible = isSelectionMode, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                if (!isSelected) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.15f)))
                Box(modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(0.85f), CircleShape)
                    .border(width = if (isSelected) 0.dp else 1.5.dp,
                        color = if (isSelected) Color.Transparent else Color.Gray, shape = CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (isSelected) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600; val minutes = (totalSeconds % 3600) / 60; val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

// ── Custom Album Dialogs ──────────────────────────────────────────────────────

@Composable
private fun CreateCustomAlbumDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Collections, null) },
        title = { Text("Tạo album tùy chỉnh") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it; error = false },
                label = { Text("Tên album") }, singleLine = true, isError = error,
                supportingText = if (error) ({ Text("Tên không được để trống") }) else null,
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { if (name.isBlank()) error = true else onConfirm(name.trim()) }) { Text("Tạo") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
private fun AddToCustomAlbumDialog(
    albums: List<com.example.mediapreview.data.CustomAlbumInfo>,
    onSelect: (String) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    if (showCreate) {
        CreateCustomAlbumDialog(onConfirm = { onCreate(it) }, onDismiss = { showCreate = false })
        return
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Thêm vào album") },
        text = {
            Column {
                TextButton(onClick = { showCreate = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text("Tạo album mới")
                }
                if (albums.isEmpty()) {
                    Text("Chưa có album tùy chỉnh", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                } else {
                    albums.forEach { album ->
                        TextButton(onClick = { onSelect(album.name) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Collections, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text(album.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}

// ── Password Dialogs ──────────────────────────────────────────────────────────

@Composable
private fun ChangePasswordDialog(folderName: String, onConfirm: (oldPwd: String, newPwd: String) -> Boolean, onDismiss: () -> Unit) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Key, null) }, title = { Text("Đổi mật khẩu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Đổi mật khẩu cho \"$folderName\"", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = oldPassword, onValueChange = { oldPassword = it; errorMsg = null },
                    label = { Text("Mật khẩu hiện tại") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = newPassword, onValueChange = { newPassword = it; errorMsg = null },
                    label = { Text("Mật khẩu mới") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }}, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = confirmPassword, onValueChange = { confirmPassword = it; errorMsg = null },
                    label = { Text("Xác nhận mật khẩu mới") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                if (errorMsg != null) Text(errorMsg!!, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    oldPassword.isBlank() -> errorMsg = "Nhập mật khẩu hiện tại."
                    newPassword.isBlank() -> errorMsg = "Mật khẩu mới không được để trống."
                    newPassword.length < 4 -> errorMsg = "Mật khẩu mới phải có ít nhất 4 ký tự."
                    newPassword != confirmPassword -> errorMsg = "Mật khẩu xác nhận không khớp."
                    else -> if (!onConfirm(oldPassword, newPassword)) { errorMsg = "Mật khẩu hiện tại không đúng."; oldPassword = "" }
                }
            }) { Text("Đổi mật khẩu") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
private fun ManageStoragePermissionDialog(context: android.content.Context, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Storage, null) },
        title = { Text("Cần quyền quản lý bộ nhớ") },
        text = {
            Text("Để ẩn hoàn toàn các ảnh và video với tất cả ứng dụng khác khi khóa thư mục, " +
                    "ứng dụng cần quyền \"Quản lý tất cả file\".\n\n" +
                    "Nhấn \"Đi đến Cài đặt\", bật quyền này, rồi quay lại để tiếp tục khóa thư mục.",
                style = MaterialTheme.typography.bodyMedium)
        },
        confirmButton = {
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                try { context.startActivity(intent) }
                catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                onDismiss()
            }) { Text("Đi đến Cài đặt") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Để sau") } }
    )
}

@Composable
private fun SetPasswordDialog(folderName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Lock, null) }, title = { Text("Khóa thư mục") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Đặt mật khẩu để khóa \"$folderName\".", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = password, onValueChange = { password = it; errorMsg = null },
                    label = { Text("Mật khẩu") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }}, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = confirmPassword, onValueChange = { confirmPassword = it; errorMsg = null },
                    label = { Text("Xác nhận mật khẩu") }, singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                if (errorMsg != null) Text(errorMsg!!, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

@Composable
private fun EnterPasswordDialog(
    title: String, message: String, confirmLabel: String,
    onConfirm: (String) -> Boolean,
    onBiometricAuth: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Lock, null) }, title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = password, onValueChange = { password = it; showError = false },
                    label = { Text("Mật khẩu") }, singleLine = true, isError = showError,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                    }}, modifier = Modifier.fillMaxWidth())
                if (showError) Text("Mật khẩu không đúng. Vui lòng thử lại.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                // Biometric option
                if (onBiometricAuth != null) {
                    TextButton(onClick = { onBiometricAuth(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Fingerprint, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Dùng sinh trắc học")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!onConfirm(password)) { showError = true; password = "" }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Hủy") } }
    )
}

