package com.example.mediapreview.ui.gallery

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import com.example.mediapreview.data.AppFolderPermissionRepository
import com.example.mediapreview.data.CustomAlbumInfo
import com.example.mediapreview.data.CustomAlbumsRepository
import com.example.mediapreview.data.FavoritesRepository
import com.example.mediapreview.data.GalleryItem
import com.example.mediapreview.data.LockedFoldersRepository
import com.example.mediapreview.data.MediaRepository
import com.example.mediapreview.data.NomediaManager
import com.example.mediapreview.data.PinnedFoldersRepository
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

enum class MediaTypeFilter { ALL, IMAGES, VIDEOS }
enum class DisplayMode { FLAT, DATE, MONTH, YEAR, FOLDER }
enum class NavigationTab { PHOTOS, ALBUMS, FAVORITES, MUSIC, TRASH }

// ── List item model ────────────────────────────────────────────────────────

sealed class GalleryListItem {
    data class Header(val label: String, val count: Int) : GalleryListItem()
    data class Media(val item: GalleryItem) : GalleryListItem()
    data class FolderCard(
        val folderName: String,
        val count: Int,
        val previewItems: List<GalleryItem>,
        val isLocked: Boolean = false,
        val isPinned: Boolean = false,
    ) : GalleryListItem()
    data class CustomAlbumCard(
        val albumName: String,
        val count: Int,
        val previewItems: List<GalleryItem>,
    ) : GalleryListItem()
}

// ── Events ─────────────────────────────────────────────────────────────────

sealed class GalleryEvent {
    data class RequestDelete(val items: List<GalleryItem>) : GalleryEvent()
    data class RequestShare(val items: List<GalleryItem>) : GalleryEvent()
    data class RequestTrash(val items: List<GalleryItem>) : GalleryEvent()
    data class RequestRestore(val items: List<GalleryItem>) : GalleryEvent()
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
    val navigationTab: NavigationTab = NavigationTab.PHOTOS,
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val favoriteIds: Set<Long> = emptySet(),
    val searchQuery: String = "",
    val viewerItems: List<GalleryItem> = emptyList(),
    val lockedFolderNames: Set<String> = emptySet(),
    val pinnedFolderNames: Set<String> = emptySet(),
    val pendingPasswordFolder: String? = null,
    val lockedFolderFileItems: List<GalleryItem> = emptyList(),
    // Trash
    val trashedMedia: List<GalleryItem> = emptyList(),
    val isLoadingTrash: Boolean = false,
    // Custom Albums
    val customAlbums: List<CustomAlbumInfo> = emptyList(),
    val selectedCustomAlbum: String? = null,
)

// ── ViewModel ──────────────────────────────────────────────────────────────

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val favoritesRepo = FavoritesRepository(application)
    private val lockedFoldersRepo = LockedFoldersRepository(application)
    private val nomediaManager = NomediaManager(application)
    private val pinnedFoldersRepo = PinnedFoldersRepository(application)
    private val customAlbumsRepo = CustomAlbumsRepository(application)
    private val appPermissionRepo = AppFolderPermissionRepository(application)

    private val _state = MutableStateFlow(
        GalleryState(
            favoriteIds = favoritesRepo.getFavoriteIds(),
            lockedFolderNames = lockedFoldersRepo.getLockedFolders(),
            pinnedFolderNames = pinnedFoldersRepo.getPinnedFolders(),
            customAlbums = customAlbumsRepo.getAllAlbums(),
        )
    )
    val state: StateFlow<GalleryState> = _state

    private val _events = MutableSharedFlow<GalleryEvent>()
    val events: SharedFlow<GalleryEvent> = _events

    private var debounceJob: Job? = null

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            debounceJob?.cancel()
            debounceJob = viewModelScope.launch {
                delay(800)
                loadMedia()
            }
        }
    }

    init {
        val cr = application.contentResolver
        cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        // Restore ephemeral URI grants that may have been lost after a device reboot.
        viewModelScope.launch(Dispatchers.IO) { appPermissionRepo.reGrantAllPermissions() }
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
                if (locked.isNotEmpty() && nomediaManager.hasPermission()) {
                    withContext(Dispatchers.IO) {
                        locked.forEach { folderName ->
                            val path = lockedFoldersRepo.getFolderPath(folderName)
                            if (path != null) {
                                nomediaManager.createNomedia(path)
                                nomediaManager.hideFromMediaStore(path)
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

    // ── Trash ──────────────────────────────────────────────────────────────

    fun loadTrashedMedia() {
        _state.update { it.copy(isLoadingTrash = true) }
        viewModelScope.launch {
            try {
                val trashed = repository.loadTrashedMedia()
                _state.update { prev ->
                    // Only update displayItems when still on TRASH tab to avoid
                    // overwriting the current tab's content if user navigated away.
                    val q = prev.searchQuery.trim().lowercase()
                    val filtered = if (q.isBlank()) trashed
                    else trashed.filter { it.name.lowercase().contains(q) }
                    prev.copy(
                        trashedMedia = trashed,
                        displayItems = if (prev.navigationTab == NavigationTab.TRASH)
                            filtered.map { GalleryListItem.Media(it) }
                        else prev.displayItems,
                        isLoadingTrash = false,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingTrash = false) }
            }
        }
    }

    fun requestTrashSelected() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch { _events.emit(GalleryEvent.RequestTrash(items)) }
    }

    fun requestRestoreSelected() {
        val items = getSelectedFromTrash()
        if (items.isEmpty()) return
        viewModelScope.launch { _events.emit(GalleryEvent.RequestRestore(items)) }
    }

    fun onTrashCompleted(ids: Set<Long>) {
        val cur = _state.value
        val newRaw = cur.rawAllMedia.filter { it.id !in ids }
        rebuild(newRaw, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
        _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }
    }

    fun onRestoreCompleted(ids: Set<Long>) {
        val trashed = _state.value.trashedMedia.filter { it.id !in ids }
        _state.update {
            it.copy(
                trashedMedia = trashed,
                displayItems = trashed.map { GalleryListItem.Media(it) },
                selectionMode = false, selectedIds = emptySet(),
            )
        }
    }

    // ── Custom Albums ──────────────────────────────────────────────────────

    fun selectCustomAlbum(name: String?) {
        if (name == null) {
            val cur = _state.value
            _state.update { it.copy(selectedCustomAlbum = null) }
            rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, null,
                cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
            return
        }
        val album = customAlbumsRepo.getAllAlbums().find { it.name == name } ?: return
        val ids = album.itemIds.toSet()
        val items = _state.value.rawAllMedia.filter { it.id in ids }
        _state.update {
            it.copy(
                selectedCustomAlbum = name,
                selectedFolder = null,
                displayItems = items.sortedByDescending { i -> i.effectiveTimeMs }.map { GalleryListItem.Media(it) },
            )
        }
    }

    fun createCustomAlbum(name: String) {
        customAlbumsRepo.createAlbum(name)
        val cur = _state.value
        val updatedAlbums = customAlbumsRepo.getAllAlbums()
        _state.update { it.copy(customAlbums = updatedAlbums) }
        rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
    }

    fun deleteCustomAlbum(name: String) {
        customAlbumsRepo.deleteAlbum(name)
        val cur = _state.value
        val updatedAlbums = customAlbumsRepo.getAllAlbums()
        _state.update { it.copy(customAlbums = updatedAlbums,
            selectedCustomAlbum = if (cur.selectedCustomAlbum == name) null else cur.selectedCustomAlbum) }
        rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, null,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
    }

    fun addSelectedToCustomAlbum(albumName: String) {
        val ids = _state.value.selectedIds
        customAlbumsRepo.addToAlbum(albumName, ids)
        val updatedAlbums = customAlbumsRepo.getAllAlbums()
        _state.update { it.copy(customAlbums = updatedAlbums) }
        clearSelection()
    }

    // ── Navigation ─────────────────────────────────────────────────────────

    fun setNavigationTab(tab: NavigationTab) {
        val cur = _state.value
        if (tab == cur.navigationTab) return
        val newMode = when (tab) {
            NavigationTab.ALBUMS -> DisplayMode.FOLDER
            NavigationTab.TRASH  -> DisplayMode.FLAT
            else -> if (cur.displayMode == DisplayMode.FOLDER) DisplayMode.FLAT else cur.displayMode
        }
        rebuild(cur.rawAllMedia, cur.mediaFilter, newMode, null,
            tab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
        _state.update { it.copy(selectedCustomAlbum = null) }
        if (tab == NavigationTab.TRASH) loadTrashedMedia()
    }

    fun setMediaFilter(filter: MediaTypeFilter) {
        val cur = _state.value
        if (filter == cur.mediaFilter) return
        rebuild(cur.rawAllMedia, filter, cur.displayMode, null,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
    }

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
        if (cur.navigationTab == NavigationTab.TRASH) {
            val q = query.trim().lowercase()
            val filtered = if (q.isBlank()) cur.trashedMedia
            else cur.trashedMedia.filter { it.name.lowercase().contains(q) }
            _state.update { it.copy(searchQuery = query,
                displayItems = filtered.map { GalleryListItem.Media(it) }) }
            return
        }
        rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, query, cur.favoriteIds, cur.lockedFolderNames)
    }

    // ── Folder lock ────────────────────────────────────────────────────────

    fun lockFolder(folderName: String, password: String) {
        viewModelScope.launch {
            val folderPath = withContext(Dispatchers.IO) { nomediaManager.getFolderPath(folderName) }
            val itemCount = _state.value.rawAllMedia.count { it.folderName.ifBlank { "Khác" } == folderName }
            lockedFoldersRepo.lockFolder(folderName, password, folderPath, itemCount)
            val locked = lockedFoldersRepo.getLockedFolders()
            val cur = _state.value
            rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
                cur.navigationTab, cur.searchQuery, cur.favoriteIds, locked)
            if (folderPath != null && nomediaManager.hasPermission()) {
                withContext(Dispatchers.IO) {
                    val securePath = nomediaManager.moveToSecureStorage(folderPath, folderName)
                    if (securePath != null) lockedFoldersRepo.saveSecurePath(folderName, securePath)
                    nomediaManager.createNomedia(folderPath)
                    nomediaManager.hideFromMediaStore(folderPath)
                    nomediaManager.scanFolderAfterNomedia(folderPath)
                }
                loadMedia()
            }
        }
    }

    fun lockFolderWithBiometric(folderName: String) {
        viewModelScope.launch {
            val folderPath = withContext(Dispatchers.IO) { nomediaManager.getFolderPath(folderName) }
            val itemCount = _state.value.rawAllMedia.count { it.folderName.ifBlank { "Khác" } == folderName }
            lockedFoldersRepo.lockFolderBiometric(folderName, folderPath, itemCount)
            val locked = lockedFoldersRepo.getLockedFolders()
            val cur = _state.value
            rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
                cur.navigationTab, cur.searchQuery, cur.favoriteIds, locked)
            if (folderPath != null && nomediaManager.hasPermission()) {
                withContext(Dispatchers.IO) {
                    val securePath = nomediaManager.moveToSecureStorage(folderPath, folderName)
                    if (securePath != null) lockedFoldersRepo.saveSecurePath(folderName, securePath)
                    nomediaManager.createNomedia(folderPath)
                    nomediaManager.hideFromMediaStore(folderPath)
                    nomediaManager.scanFolderAfterNomedia(folderPath)
                }
                loadMedia()
            }
        }
    }

    fun isBiometricOnly(folderName: String): Boolean =
        lockedFoldersRepo.isBiometricOnly(folderName)

    fun verifyFolderPassword(folderName: String, password: String): Boolean =
        lockedFoldersRepo.verifyPassword(folderName, password)

    fun permanentlyUnlockFolder(folderName: String, password: String): Boolean {
        if (!lockedFoldersRepo.verifyPassword(folderName, password)) return false
        val folderPath = lockedFoldersRepo.getFolderPath(folderName)
        val securePath = lockedFoldersRepo.getSecurePath(folderName)
        lockedFoldersRepo.unlockFolder(folderName)
        // Revoke all per-app permissions for the unlocked folder.
        appPermissionRepo.revokeAllForFolder(folderName)
        val locked = lockedFoldersRepo.getLockedFolders()
        val cur = _state.value
        rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, locked)
        viewModelScope.launch(Dispatchers.IO) {
            if (nomediaManager.hasPermission()) {
                if (securePath != null && folderPath != null) nomediaManager.moveFromSecureStorage(securePath, folderPath)
                if (folderPath != null) { nomediaManager.deleteNomedia(folderPath); nomediaManager.unhideFromMediaStore(folderPath) }
            }
            withContext(Dispatchers.Main) { loadMedia() }
        }
        return true
    }

    fun permanentlyUnlockFolderWithoutPassword(folderName: String) {
        val folderPath = lockedFoldersRepo.getFolderPath(folderName)
        val securePath = lockedFoldersRepo.getSecurePath(folderName)
        lockedFoldersRepo.unlockFolder(folderName)
        appPermissionRepo.revokeAllForFolder(folderName)
        val locked = lockedFoldersRepo.getLockedFolders()
        val cur = _state.value
        rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, locked)
        viewModelScope.launch(Dispatchers.IO) {
            if (nomediaManager.hasPermission()) {
                if (securePath != null && folderPath != null) nomediaManager.moveFromSecureStorage(securePath, folderPath)
                if (folderPath != null) { nomediaManager.deleteNomedia(folderPath); nomediaManager.unhideFromMediaStore(folderPath) }
            }
            withContext(Dispatchers.Main) { loadMedia() }
        }
    }

    fun openLockedFolder(folderName: String) {
        _state.update { it.copy(pendingPasswordFolder = null) }
        val folderPath = lockedFoldersRepo.getSecurePath(folderName) ?: lockedFoldersRepo.getFolderPath(folderName)
        if (folderPath != null) {
            viewModelScope.launch {
                val fsItems = withContext(Dispatchers.IO) { loadItemsFromFilesystem(folderName, folderPath) }
                _state.update { it.copy(lockedFolderFileItems = fsItems) }
                val cur = _state.value
                rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, folderName,
                    cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
            }
        } else {
            val cur = _state.value
            rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, folderName,
                cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames)
        }
    }

    fun clearPendingPasswordFolder() { _state.update { it.copy(pendingPasswordFolder = null) } }

    fun togglePinFolder(folderName: String): Boolean {
        val pinned = pinnedFoldersRepo.togglePin(folderName)
        val cur = _state.value
        rebuild(cur.rawAllMedia, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, cur.searchQuery, cur.favoriteIds, cur.lockedFolderNames, pinned)
        return folderName in pinned
    }

    fun changeFolderPassword(folderName: String, oldPassword: String, newPassword: String): Boolean {
        if (!lockedFoldersRepo.verifyPassword(folderName, oldPassword)) return false
        lockedFoldersRepo.changePassword(folderName, newPassword)
        return true
    }

    // ── App folder permissions ─────────────────────────────────────────────

    /** Returns the set of package names that have been granted access to [folderName]. */
    fun getGrantedAppsForFolder(folderName: String): Set<String> =
        appPermissionRepo.getGrantedApps(folderName)

    /**
     * Grant [packageName] read access to the locked folder [folderName].
     * Persists the grant and issues OS-level URI permissions for all files
     * currently in the folder's secure storage directory.
     */
    fun grantAppFolderPermission(folderName: String, packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appPermissionRepo.grantPermission(folderName, packageName)
        }
    }

    /**
     * Revoke [packageName]'s access to [folderName].
     * Removes the persisted record and revokes OS URI permissions.
     */
    fun revokeAppFolderPermission(folderName: String, packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            appPermissionRepo.revokePermission(folderName, packageName)
        }
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
            .filterIsInstance<GalleryListItem.Media>().map { it.item.id }.toSet()
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
        }
        _state.update { it.copy(favoriteIds = newFavs) }
    }

    fun addSelectedToFavorites() {
        val ids = _state.value.selectedIds
        var favs = _state.value.favoriteIds.toMutableSet()
        ids.forEach { id -> if (id !in favs) { favoritesRepo.toggleFavorite(id); favs.add(id) } }
        _state.update { it.copy(favoriteIds = favs.toSet()) }
        clearSelection()
    }

    // ── Share / Delete / Trash events ──────────────────────────────────────

    fun requestShareSelected() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        viewModelScope.launch { _events.emit(GalleryEvent.RequestShare(items)) }
        clearSelection()
    }

    fun requestDeleteSelected() {
        val items = if (_state.value.navigationTab == NavigationTab.TRASH) getSelectedFromTrash()
        else getSelectedItems()
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
        if (cur.navigationTab == NavigationTab.TRASH) {
            onRestoreCompleted(deletedIds) // reuse to remove from trash list
            return
        }
        val newRaw = cur.rawAllMedia.filter { it.id !in deletedIds }
        val newViewerItems = cur.viewerItems.filter { it.id !in deletedIds }
        val newFavs = favoritesRepo.removeFavorites(deletedIds)
        rebuild(newRaw, cur.mediaFilter, cur.displayMode, cur.selectedFolder,
            cur.navigationTab, cur.searchQuery, newFavs, cur.lockedFolderNames)
        _state.update { it.copy(viewerItems = newViewerItems, selectionMode = false, selectedIds = emptySet()) }
    }

    // ── Viewer ─────────────────────────────────────────────────────────────

    fun setViewerItems(items: List<GalleryItem>) { _state.update { it.copy(viewerItems = items) } }

    fun getCurrentMediaItems(): List<GalleryItem> =
        _state.value.displayItems.filterIsInstance<GalleryListItem.Media>().map { it.item }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun getSelectedItems(): List<GalleryItem> {
        val ids = _state.value.selectedIds
        return _state.value.rawAllMedia.filter { it.id in ids }
    }

    private fun getSelectedFromTrash(): List<GalleryItem> {
        val ids = _state.value.selectedIds
        return _state.value.trashedMedia.filter { it.id in ids }
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
        pinnedFolderNames: Set<String> = _state.value.pinnedFolderNames,
    ) {
        val lockedFsItems = _state.value.lockedFolderFileItems
        val customAlbums = customAlbumsRepo.getAllAlbums()
        val selectedCustomAlbum = _state.value.selectedCustomAlbum

        var filtered = when (tab) {
            NavigationTab.ALBUMS -> raw
            else -> when (filter) {
                MediaTypeFilter.ALL    -> raw
                MediaTypeFilter.IMAGES -> raw.filter { !it.isVideo }
                MediaTypeFilter.VIDEOS -> raw.filter { it.isVideo }
            }
        }
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            filtered = filtered.filter { it.name.lowercase().contains(q) || it.folderName.lowercase().contains(q) }
        }

        var displayItems: List<GalleryListItem> = when (tab) {
            NavigationTab.FAVORITES -> {
                val favs = filtered.filter { it.id in favoriteIds && it.folderName !in lockedFolderNames }
                    .sortedByDescending { it.effectiveTimeMs }
                when (mode) {
                    DisplayMode.DATE  -> groupByDate(favs, DateGrouping.DAY)
                    DisplayMode.MONTH -> groupByDate(favs, DateGrouping.MONTH)
                    DisplayMode.YEAR  -> groupByDate(favs, DateGrouping.YEAR)
                    else -> favs.map { GalleryListItem.Media(it) }
                }
            }
            NavigationTab.ALBUMS -> {
                when {
                    selectedCustomAlbum != null -> {
                        val ids = customAlbums.find { it.name == selectedCustomAlbum }?.itemIds?.toSet() ?: emptySet()
                        val q = searchQuery.trim().lowercase()
                        raw.filter { it.id in ids && (q.isBlank() || it.name.lowercase().contains(q)) }
                            .sortedByDescending { it.effectiveTimeMs }.map { GalleryListItem.Media(it) }
                    }
                    else -> groupByFolder(filtered, selectedFolder, lockedFolderNames, pinnedFolderNames, customAlbums)
                }
            }
            NavigationTab.PHOTOS -> when (mode) {
                DisplayMode.FLAT -> {
                    val items = if (selectedFolder == null) filtered.filter { it.folderName !in lockedFolderNames }
                    else filtered
                    items.sortedByDescending { it.effectiveTimeMs }.map { GalleryListItem.Media(it) }
                }
                DisplayMode.DATE  -> groupByDate(filtered.filter { it.folderName !in lockedFolderNames }, DateGrouping.DAY)
                DisplayMode.MONTH -> groupByDate(filtered.filter { it.folderName !in lockedFolderNames }, DateGrouping.MONTH)
                DisplayMode.YEAR  -> groupByDate(filtered.filter { it.folderName !in lockedFolderNames }, DateGrouping.YEAR)
                DisplayMode.FOLDER -> groupByFolder(filtered, selectedFolder, lockedFolderNames, pinnedFolderNames, customAlbums)
            }
            NavigationTab.MUSIC -> emptyList()
            NavigationTab.TRASH -> _state.value.trashedMedia.map { GalleryListItem.Media(it) }
        }

        if (selectedFolder != null && selectedFolder in lockedFolderNames
            && displayItems.none { it is GalleryListItem.Media } && lockedFsItems.isNotEmpty()) {
            val q = searchQuery.trim().lowercase()
            val visible = if (searchQuery.isNotBlank()) lockedFsItems.filter { it.name.lowercase().contains(q) }
            else lockedFsItems
            displayItems = visible.sortedByDescending { it.effectiveTimeMs }.map { GalleryListItem.Media(it) }
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
                pinnedFolderNames    = pinnedFolderNames,
                selectionMode        = false,
                selectedIds          = emptySet(),
                error                = null,
                customAlbums         = customAlbums,
                lockedFolderFileItems = if (selectedFolder == null) emptyList() else lockedFsItems,
            )
        }
    }

    private enum class DateGrouping { DAY, MONTH, YEAR }

    private fun groupByDate(items: List<GalleryItem>, grouping: DateGrouping = DateGrouping.DAY): List<GalleryListItem> {
        if (items.isEmpty()) return emptyList()
        val sorted = items.sortedByDescending { it.effectiveTimeMs }
        val fmt = when (grouping) {
            DateGrouping.DAY   -> SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
            DateGrouping.MONTH -> SimpleDateFormat("MMMM yyyy", Locale.getDefault())
            DateGrouping.YEAR  -> SimpleDateFormat("yyyy", Locale.getDefault())
        }
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
        pinnedFolderNames: Set<String> = emptySet(),
        customAlbums: List<CustomAlbumInfo> = emptyList(),
    ): List<GalleryListItem> {
        if (selectedFolder != null) {
            val sorted = items.sortedByDescending { it.effectiveTimeMs }
            val grouped = sorted.groupBy { it.folderName.ifBlank { "Khác" } }
            return (grouped[selectedFolder] ?: emptyList()).map { GalleryListItem.Media(it) }
        }
        // Top-level folder view
        val sorted = items.sortedByDescending { it.effectiveTimeMs }
        val grouped = sorted.groupBy { it.folderName.ifBlank { "Khác" } }
        val allFolderNames = (grouped.keys + lockedFolderNames).toSet()
        val sortedFolders = allFolderNames.sortedWith(
            compareByDescending<String> { it in pinnedFolderNames }.thenBy { it.lowercase() }
        )
        val folderCards = sortedFolders.map { folder ->
            val locked = folder in lockedFolderNames
            val group  = grouped[folder] ?: emptyList()
            GalleryListItem.FolderCard(
                folderName   = folder,
                count        = if (locked) lockedFoldersRepo.getItemCount(folder).coerceAtLeast(group.size) else group.size,
                previewItems = if (locked) emptyList() else group.take(4),
                isLocked     = locked,
                isPinned     = folder in pinnedFolderNames,
            )
        }
        // Prepend custom album cards
        val customAlbumCards = customAlbums.map { album ->
            val ids = album.itemIds.toSet()
            val previewItems = items.filter { it.id in ids }.take(4)
            GalleryListItem.CustomAlbumCard(albumName = album.name, count = album.itemIds.size, previewItems = previewItems)
        }
        return customAlbumCards + folderCards
    }

    private fun loadItemsFromFilesystem(folderName: String, folderPath: String): List<GalleryItem> {
        val extensions = setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif","avif",
            "mp4","3gp","mkv","avi","mov","wmv","flv","webm","ts","m4v")
        val app = getApplication<Application>()
        return File(folderPath).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in extensions }
            ?.map { file ->
                // Use FileProvider (content://) URI instead of file:// URI.
                // file:// URIs are blocked by Android 7+ for inter-app sharing, so
                // FLAG_GRANT_READ_URI_PERMISSION has no effect on them.  content://
                // URIs from FileProvider work correctly with that flag and allow apps
                // such as Discord, Telegram, etc. to read the shared files.
                val uri = try {
                    FileProvider.getUriForFile(app, "${app.packageName}.provider", file)
                } catch (_: Exception) {
                    // Fallback: file not covered by FileProvider paths (shouldn't
                    // happen for locked files, but guard against it).
                    @Suppress("DEPRECATION")
                    Uri.fromFile(file)
                }
                GalleryItem(
                    id        = file.absolutePath.hashCode().toLong(),
                    uri       = uri,
                    name      = file.name,
                    dateAdded = file.lastModified() / 1000L,
                    mimeType  = mimeTypeOf(file),
                    folderName = folderName,
                    dateTaken  = file.lastModified(),
                    fileSize   = file.length(),
                )
            }?.sortedByDescending { it.effectiveTimeMs } ?: emptyList()
    }

    private fun mimeTypeOf(file: File): String = when (file.extension.lowercase()) {
        "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"
        "webp" -> "image/webp"; "bmp" -> "image/bmp"; "heic" -> "image/heic"
        "heif" -> "image/heif"; "avif" -> "image/avif"; "mp4" -> "video/mp4"
        "3gp" -> "video/3gpp"; "mkv" -> "video/x-matroska"; "avi" -> "video/avi"
        "mov" -> "video/quicktime"; "wmv" -> "video/x-ms-wmv"; "flv" -> "video/x-flv"
        "webm" -> "video/webm"; "ts" -> "video/mp2ts"; "m4v" -> "video/x-m4v"
        else -> "application/octet-stream"
    }
}
