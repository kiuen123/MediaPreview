package com.example.mediapreview.ui.gallery

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediapreview.data.FavoritesRepository
import com.example.mediapreview.data.GalleryItem
import com.example.mediapreview.data.LockedFoldersRepository
import com.example.mediapreview.data.MediaRepository
import com.example.mediapreview.data.NomediaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Enums ──────────────────────────────────────────────────────────────────

/** Which type of media is shown in the current tab. */
enum class MediaTypeFilter { ALL, IMAGES, VIDEOS }

/**
 * How items are arranged in PHOTOS tab.
 * FLAT = flat grid newest→oldest, DATE = grouped by day/month, FOLDER = folder cards
 */
enum class DisplayMode { FLAT, DATE, FOLDER }

/** Bottom navigation tabs, Samsung OneUI Gallery style. */
enum class NavigationTab { PHOTOS, ALBUMS, FAVORITES }

// ── List item model ────────────────────────────────────────────────────────

sealed class GalleryListItem {
    data class Header(val label: String, val count: Int) : GalleryListItem()
    data class Media(val item: GalleryItem) : GalleryListItem()
    data class FolderCard(
        val folderName: String,
        val count: Int,
        val previewItems: List<GalleryItem>,
        val isLocked: Boolean = false,
    ) : GalleryListItem()
}

// ── Events ─────────────────────────────────────────────────────────────────

sealed class GalleryEvent {
    data class RequestDelete(val items: List<GalleryItem>) : GalleryEvent()
    data class RequestShare(val items: List<GalleryItem>) : GalleryEvent()
}

// ── State ──────────────────────────────────────────────────────────────────

data class GalleryState(
    val rawAllMedia: List<GalleryItem> = emptyList(),
    val displayItems: List<GalleryListItem> = emptyList(),
    val isLoading: Boolean = false,
    val mediaFilter: MediaTypeFilter = MediaTypeFilter.ALL,
    val displayMode: DisplayMode = DisplayMode.FLAT,
    val selectedFolder: String? = null,
    val error: String? = null,
    // Bottom nav
    val navigationTab: NavigationTab = NavigationTab.PHOTOS,
    // Multi-select
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    // Favorites
    val favoriteIds: Set<Long> = emptySet(),
    // Search
    val searchQuery: String = "",
    // Pager viewer items
    val viewerItems: List<GalleryItem> = emptyList(),
    // Locked folders
    val lockedFolderNames: Set<String> = emptySet(),
    // Folder waiting for password to open (null = none)
    val pendingPasswordFolder: String? = null,
    // Media items loaded directly from filesystem for a locked folder
    // (MediaStore has them hidden, so we bypass it for viewing)
    val lockedFolderFileItems: List<GalleryItem> = emptyList(),
)

// ── ViewModel ──────────────────────────────────────────────────────────────

private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1_000

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val favoritesRepo = FavoritesRepository(application)
    private val lockedFoldersRepo = LockedFoldersRepository(application)
    private val nomediaManager = NomediaManager(application)
    private val _state = MutableStateFlow(
        GalleryState(
            favoriteIds = favoritesRepo.getFavoriteIds(),
            lockedFolderNames = lockedFoldersRepo.getLockedFolders(),
        )
    )
    val state: StateFlow<GalleryState> = _state

    private val _events = MutableSharedFlow<GalleryEvent>()
    val events: SharedFlow<GalleryEvent> = _events

    // ── MediaStore ContentObserver ─────────────────────────────────────────
    // Tự động reload khi có ảnh/video mới được chụp hoặc quay

    private var debounceJob: Job? = null

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // Debounce 800 ms để tránh gọi liên tục khi MediaStore cập nhật nhiều lần
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(800)
                loadMedia()
            }
        }
    }

    init {
        val contentResolver = application.contentResolver
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver,
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver,
        )
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().contentResolver.unregisterContentObserver(mediaObserver)
    }

    // ── Public API ─────────────────────────────────────────────────────────

    fun loadMedia() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val locked = lockedFoldersRepo.getLockedFolders()
                // Re-apply full protection for ALL locked folders on every load:
                //  • Recreate .nomedia if it was somehow deleted (e.g. by a file manager).
                //  • Set IS_PENDING=1 + IS_TRASHED=1 in case a system media scan reset them
                //    (e.g. after device reboot or MediaStore database rebuild).
                //  • Trigger a MediaScanner pass so existing database rows are actively
                //    removed — this covers "old entries" that predate the triple-protection
                //    strategy and ensures they are not visible to any other app.
                if (locked.isNotEmpty() && nomediaManager.hasPermission()) {
                    withContext(Dispatchers.IO) {
                        locked.forEach { folderName ->
                            val path = lockedFoldersRepo.getFolderPath(folderName)
                            if (path != null) {
                                // Layer 3: ensure .nomedia sentinel exists
                                nomediaManager.createNomedia(path)
                                // Layer 1+2: set IS_PENDING=1 + IS_TRASHED=1 immediately
                                nomediaManager.hideFromMediaStore(path)
                                // Layer 4: async scan to remove DB rows via MediaScanner
                                nomediaManager.scanFolderAfterNomedia(path)
                            }
                        }
                    }
                }
                val raw = repository.loadAllMedia()
                val cur = _state.value
                rebuild(raw, cur.mediaFilter, cur.displayMode, null,
                    cur.navigationTab, cur.searchQuery, cur.favoriteIds, locked)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun setNavigationTab(tab: NavigationTab) {
        val cur = _state.value
        if (tab == cur.navigationTab) return
        val newMode = when (tab) {
            NavigationTab.ALBUMS -> DisplayMode.FOLDER
            else -> if (cur.displayMode == DisplayMode.FOLDER) DisplayMode.FLAT else cur.displayMode
        }
        rebuild(cur.rawAllMedia, cur.mediaFilter, newMode, null,
            tab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
    }

    fun setMediaFilter(filter: MediaTypeFilter) {
        val cur = _state.value
        if (filter == cur.mediaFilter) return
        rebuild(cur.rawAllMedia, filter, cur.displayMode, null,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
    }

    /** Called when user picks one of the first 3 tabs (Tất cả / Ảnh / Video).
     *  Resets displayMode from FOLDER → FLAT so the folder tab is deselected. */
    fun setMediaFilterFromTab(filter: MediaTypeFilter) {
        val cur = _state.value
        val mode = if (cur.displayMode == DisplayMode.FOLDER) DisplayMode.FLAT else cur.displayMode
        if (filter == cur.mediaFilter && cur.displayMode == mode) return
        rebuild(cur.rawAllMedia, filter, mode, null,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
    }

    fun setDisplayMode(mode: DisplayMode) {
        val cur = _state.value
        if (mode == cur.displayMode) return
        val newFilter = if (mode == DisplayMode.FOLDER) MediaTypeFilter.ALL else cur.mediaFilter
        rebuild(cur.rawAllMedia, newFilter, mode, null,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
    }

    fun selectFolder(folderName: String?) {
        val cur = _state.value
        // If trying to open a locked folder, show password prompt instead
        if (folderName != null && folderName in cur.lockedFolderNames) {
            _state.update { it.copy(pendingPasswordFolder = folderName) }
            return
        }
        rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, folderName,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
    }

    fun setSearchQuery(query: String) {
        val cur = _state.value
        if (query == cur.searchQuery) return
        rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, query, cur.favoriteIds, cur.lockedFolderNames)
    }

    // ── Folder lock ────────────────────────────────────────────────────────

    /**
     * Lock the folder and hide its contents from every other app.
     *
     * Protection strategy (applied in order):
     *  Step A – Move files to app-private external storage
     *           (Android/data/<package>/files/locked/<folder>/).
     *           This is the primary, strongest protection:
     *            • MediaStore never indexes Android/data/<package>/.
     *            • Scoped storage (Android 11+) prevents ALL other apps — including
     *              Samsung Gallery, Discord, Messenger, file managers, etc. — from
     *              reading the directory directly.
     *            • The Android photo picker / SAF browser does not expose it.
     *  Step B – Write .nomedia in the original (now-empty) folder so any leftover
     *           files are excluded from future MediaStore scans.
     *  Step C – Set IS_PENDING=1 + IS_TRASHED=1 on any remaining MediaStore entries
     *           (belt-and-suspenders for files that could not be moved).
     *  Step D – Scan the folder with .nomedia so MediaScanner removes existing
     *           database rows entirely.
     */
    fun lockFolder(folderName: String, password: String) {
        viewModelScope.launch {
            // 1. Get path while MediaStore still sees the files
            val folderPath = withContext(Dispatchers.IO) {
                nomediaManager.getFolderPath(folderName)
            }
            // 2. Lock in prefs + immediate UI update
            val itemCount = _state.value.rawAllMedia.count {
                it.folderName.ifBlank { "Khác" } == folderName
            }
            lockedFoldersRepo.lockFolder(folderName, password, folderPath, itemCount)
            val locked = lockedFoldersRepo.getLockedFolders()
            val cur = _state.value
            rebuild(
                cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
                cur.navigationTab, cur.searchQuery, cur.favoriteIds, locked,
            )
            // 3. Move files + belt-and-suspenders MediaStore protection
            if (folderPath != null && nomediaManager.hasPermission()) {
                withContext(Dispatchers.IO) {
                    // Step A: move files to Android/data/<package>/files/locked/
                    val securePath = nomediaManager.moveToSecureStorage(folderPath, folderName)
                    if (securePath != null) {
                        lockedFoldersRepo.saveSecurePath(folderName, securePath)
                    }
                    // Step B: write .nomedia in original folder (now mostly empty)
                    nomediaManager.createNomedia(folderPath)
                    // Step C: mark any remaining MediaStore rows as PENDING + TRASHED
                    nomediaManager.hideFromMediaStore(folderPath)
                    // Step D: make MediaScanner remove the remaining database rows
                    nomediaManager.scanFolderAfterNomedia(folderPath)
                }
                // Reload so rawAllMedia no longer contains the locked folder's items
                loadMedia()
            }
        }
    }

    /** Verify password without unlocking. Returns true if correct. */
    fun verifyFolderPassword(folderName: String, password: String): Boolean =
        lockedFoldersRepo.verifyPassword(folderName, password)

    /**
     * Permanently remove the lock.
     * Moves files back from secure storage to original location, deletes .nomedia,
     * rescans so files reappear in MediaStore, then reloads.
     */
    fun permanentlyUnlockFolder(folderName: String, password: String): Boolean {
        if (!lockedFoldersRepo.verifyPassword(folderName, password)) return false
        val folderPath  = lockedFoldersRepo.getFolderPath(folderName)
        val securePath  = lockedFoldersRepo.getSecurePath(folderName)
        lockedFoldersRepo.unlockFolder(folderName)
        val locked = lockedFoldersRepo.getLockedFolders()
        val cur = _state.value
        rebuild(
            cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, locked,
        )
        viewModelScope.launch(Dispatchers.IO) {
            if (nomediaManager.hasPermission()) {
                // Move files back from secure storage → original location
                if (securePath != null && folderPath != null) {
                    nomediaManager.moveFromSecureStorage(securePath, folderPath)
                }
                // Remove .nomedia and restore MediaStore visibility
                if (folderPath != null) {
                    nomediaManager.deleteNomedia(folderPath)
                    nomediaManager.unhideFromMediaStore(folderPath)
                }
            }
            withContext(Dispatchers.Main) { loadMedia() }
        }
        return true
    }

    /**
     * Open a locked folder after the user entered the correct password.
     *
     * Files are loaded from the secure storage path (Android/data/<package>/files/locked/)
     * where they were physically moved when the folder was locked.
     * Falls back to the original folder path for legacy entries locked before the
     * secure-move feature was added.
     */
    fun openLockedFolder(folderName: String) {
        _state.update { it.copy(pendingPasswordFolder = null) }
        // Prefer secure path (files physically moved there), fall back to original path
        val folderPath = lockedFoldersRepo.getSecurePath(folderName)
            ?: lockedFoldersRepo.getFolderPath(folderName)
        if (folderPath != null) {
            viewModelScope.launch {
                val fsItems = withContext(Dispatchers.IO) {
                    loadItemsFromFilesystem(folderName, folderPath)
                }
                // Store filesystem items in state so rebuild can use them
                _state.update { it.copy(lockedFolderFileItems = fsItems) }
                val cur = _state.value
                rebuild(
                    cur.rawAllMedia, cur.mediaFilter, cur.displayMode, folderName,
                    cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames,
                )
            }
        } else {
            // No stored path – fall back to whatever rawAllMedia has (may be empty)
            val cur = _state.value
            rebuild(
                cur.rawAllMedia, cur.mediaFilter, cur.displayMode, folderName,
                cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames,
            )
        }
    }

    fun clearPendingPasswordFolder() {
        _state.update { it.copy(pendingPasswordFolder = null) }
    }

    // ── Selection ──────────────────────────────────────────────────────────

    fun enterSelectionMode(firstItemId: Long) {
        _state.update { it.copy(selectionMode = true, selectedIds = setOf(firstItemId)) }
    }

    fun toggleItemSelection(id: Long) {
        _state.update { cur ->
            val newIds = cur.selectedIds.toMutableSet()
            if (id in newIds) newIds.remove(id) else newIds.add(id)
            cur.copy(selectedIds = newIds.toSet(), selectionMode = newIds.isNotEmpty())
        }
    }

    fun selectAll() {
        val allIds = _state.value.displayItems
            .filterIsInstance<GalleryListItem.Media>()
            .map { it.item.id }.toSet()
        _state.update { it.copy(selectedIds = allIds) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    // ── Favorites ──────────────────────────────────────────────────────────

    fun toggleFavorite(id: Long) {
        val newFavs = favoritesRepo.toggleFavorite(id)
        val cur = _state.value
        if (cur.navigationTab == NavigationTab.FAVORITES) {
            rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
                cur.navigationTab, cur.searchQuery, newFavs, cur.lockedFolderNames)
        } else {
            _state.update { it.copy(favoriteIds = newFavs) }
        }
        _state.update { it.copy(favoriteIds = newFavs) }
    }

    fun addSelectedToFavorites() {
        val ids = _state.value.selectedIds
        var favs = _state.value.favoriteIds.toMutableSet()
        ids.forEach { id ->
            if (id !in favs) {
                favoritesRepo.toggleFavorite(id)
                favs.add(id)
            }
        }
        _state.update { it.copy(favoriteIds = favs.toSet()) }
        clearSelection()
    }

    // ── Share / Delete events ──────────────────────────────────────────────

    fun requestShareSelected() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch { _events.emit(GalleryEvent.RequestShare(items)) }
        clearSelection()
    }

    fun requestDeleteSelected() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch { _events.emit(GalleryEvent.RequestDelete(items)) }
    }

    fun requestShareItem(item: GalleryItem) {
        viewModelScope.launch { _events.emit(GalleryEvent.RequestShare(listOf(item))) }
    }

    fun requestDeleteItem(item: GalleryItem) {
        viewModelScope.launch { _events.emit(GalleryEvent.RequestDelete(listOf(item))) }
    }

    fun onDeleteCompleted(deletedIds: Set<Long>) {
        val cur = _state.value
        val newRaw = cur.rawAllMedia.filter { it.id !in deletedIds }
        val newViewerItems = cur.viewerItems.filter { it.id !in deletedIds }
        val newFavs = favoritesRepo.removeFavorites(deletedIds)
        rebuild(newRaw, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, cur.searchQuery, newFavs, cur.lockedFolderNames)
        _state.update { it.copy(viewerItems = newViewerItems, selectionMode = false, selectedIds = emptySet()) }
    }

    // ── Viewer ─────────────────────────────────────────────────────────────

    fun setViewerItems(items: List<GalleryItem>) {
        _state.update { it.copy(viewerItems = items) }
    }

    fun getCurrentMediaItems(): List<GalleryItem> =
        _state.value.displayItems
            .filterIsInstance<GalleryListItem.Media>()
            .map { it.item }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun getSelectedItems(): List<GalleryItem> {
        val ids = _state.value.selectedIds
        return _state.value.rawAllMedia.filter { it.id in ids }
    }

    private fun rebuild(
        raw: List<GalleryItem>,
        filter: MediaTypeFilter,
        mode: DisplayMode,
        selectedFolder: String?,
        tab: NavigationTab,
        searchQuery: String,
        favoriteIds: Set<Long>,
        lockedFolderNames: Set<String>,
    ) {
        // Snapshot of filesystem items loaded by openLockedFolder (may be empty)
        val lockedFsItems = _state.value.lockedFolderFileItems

        // Type filter (not for Albums)
        var filtered = when (tab) {
            NavigationTab.ALBUMS -> raw
            else -> when (filter) {
                MediaTypeFilter.ALL    -> raw
                MediaTypeFilter.IMAGES -> raw.filter { !it.isVideo }
                MediaTypeFilter.VIDEOS -> raw.filter { it.isVideo }
            }
        }
        // Search filter
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            filtered = filtered.filter {
                it.name.lowercase().contains(q) || it.folderName.lowercase().contains(q)
            }
        }
        // Build display list
        var displayItems: List<GalleryListItem> = when (tab) {
            NavigationTab.FAVORITES -> {
                // Never show media from locked folders in favorites
                val favs = filtered
                    .filter { it.id in favoriteIds && it.folderName !in lockedFolderNames }
                    .sortedByDescending { it.effectiveTimeMs }
                when (mode) {
                    DisplayMode.DATE -> groupByDate(favs)
                    else -> favs.map { GalleryListItem.Media(it) }
                }
            }
            NavigationTab.ALBUMS -> groupByFolder(filtered, selectedFolder, lockedFolderNames)
            NavigationTab.PHOTOS -> when (mode) {
                DisplayMode.FLAT -> {
                    val items = if (selectedFolder == null)
                        filtered.filter { it.folderName !in lockedFolderNames }
                    else filtered
                    items.sortedByDescending { it.effectiveTimeMs }.map { GalleryListItem.Media(it) }
                }
                DisplayMode.DATE -> groupByDate(
                    filtered.filter { it.folderName !in lockedFolderNames }
                )
                DisplayMode.FOLDER -> groupByFolder(filtered, selectedFolder, lockedFolderNames)
            }
        }

        // ── Locked-folder override ─────────────────────────────────────────────
        // If we are INSIDE a locked folder and MediaStore has no files for it
        // (they are hidden), substitute the pre-loaded filesystem items.
        if (selectedFolder != null
            && selectedFolder in lockedFolderNames
            && displayItems.none { it is GalleryListItem.Media }
            && lockedFsItems.isNotEmpty()
        ) {
            val q = searchQuery.trim().lowercase()
            val visible = if (searchQuery.isNotBlank())
                lockedFsItems.filter { it.name.lowercase().contains(q) }
            else lockedFsItems
            displayItems = visible
                .sortedByDescending { it.effectiveTimeMs }
                .map { GalleryListItem.Media(it) }
        }

        _state.update { prev ->
            prev.copy(
                rawAllMedia          = raw,
                displayItems         = displayItems,
                isLoading            = false,
                mediaFilter          = filter,
                displayMode          = mode,
                selectedFolder       = selectedFolder,
                navigationTab        = tab,
                searchQuery          = searchQuery,
                favoriteIds          = favoriteIds,
                lockedFolderNames    = lockedFolderNames,
                selectionMode        = false,
                selectedIds          = emptySet(),
                error                = null,
                // Clear filesystem items when returning to the folder list
                lockedFolderFileItems = if (selectedFolder == null) emptyList() else lockedFsItems,
            )
        }
    }

    private fun groupByDate(items: List<GalleryItem>): List<GalleryListItem> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedByDescending { it.effectiveTimeMs }
        val spanMs = sorted.first().effectiveTimeMs - sorted.last().effectiveTimeMs
        val fmt = if (spanMs > THIRTY_DAYS_MS)
            SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        else
            SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        return buildList {
            sorted.groupBy { fmt.format(Date(it.effectiveTimeMs)) }
                .forEach { (label, group) ->
                    add(GalleryListItem.Header(label, group.size))
                    group.forEach { add(GalleryListItem.Media(it)) }
                }
        }
    }

    private fun groupByFolder(
        items: List<GalleryItem>,
        selectedFolder: String?,
        lockedFolderNames: Set<String>,
    ): List<GalleryListItem> {
        if (items.isEmpty() && lockedFolderNames.isEmpty()) return emptyList()
        val sorted  = items.sortedByDescending { it.effectiveTimeMs }
        val grouped = sorted.groupBy { it.folderName.ifBlank { "Khác" } }
        return if (selectedFolder == null) {
            // Always include locked folders, even if their files are hidden from MediaStore
            val allFolderNames = (grouped.keys + lockedFolderNames)
                .toSortedSet(compareBy { it.lowercase() })
            allFolderNames.map { folder ->
                val locked = folder in lockedFolderNames
                val group  = grouped[folder] ?: emptyList()
                GalleryListItem.FolderCard(
                    folderName   = folder,
                    // For locked folders, use stored count (MediaStore count is 0 after hiding)
                    count        = if (locked)
                                       lockedFoldersRepo.getItemCount(folder).coerceAtLeast(group.size)
                                   else group.size,
                    previewItems = if (locked) emptyList() else group.take(4),
                    isLocked     = locked,
                )
            }
        } else {
            (grouped[selectedFolder] ?: emptyList()).map { GalleryListItem.Media(it) }
        }
    }

    // ── Filesystem helpers (for locked folders) ────────────────────────────

    /**
     * Loads media items directly from the filesystem for [folderPath].
     * Used when MediaStore has the files hidden (folder is locked).
     */
    private fun loadItemsFromFilesystem(folderName: String, folderPath: String): List<GalleryItem> {
        val extensions = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif",
            "mp4", "3gp", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v",
        )
        return File(folderPath).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in extensions }
            ?.map { file ->
                GalleryItem(
                    id         = file.absolutePath.hashCode().toLong(),
                    uri        = Uri.fromFile(file),
                    name       = file.name,
                    dateAdded  = file.lastModified() / 1000L,
                    mimeType   = mimeTypeOf(file),
                    folderName = folderName,
                    dateTaken  = file.lastModified(),
                    fileSize   = file.length(),
                )
            }
            ?.sortedByDescending { it.effectiveTimeMs }
            ?: emptyList()
    }

    private fun mimeTypeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png"         -> "image/png"
        "gif"         -> "image/gif"
        "webp"        -> "image/webp"
        "bmp"         -> "image/bmp"
        "heic"        -> "image/heic"
        "heif"        -> "image/heif"
        "avif"        -> "image/avif"
        "mp4"         -> "video/mp4"
        "3gp"         -> "video/3gpp"
        "mkv"         -> "video/x-matroska"
        "avi"         -> "video/avi"
        "mov"         -> "video/quicktime"
        "wmv"         -> "video/x-ms-wmv"
        "flv"         -> "video/x-flv"
        "webm"        -> "video/webm"
        "ts"          -> "video/mp2ts"
        "m4v"         -> "video/x-m4v"
        else          -> "application/octet-stream"
    }
}
