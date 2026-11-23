package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.clipboard.CaptureOutcome
import com.example.core.clipboard.ClipboardCaptureCoordinator
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.KeystoreCryptoManager
import com.example.core.sync.SyncEngine
import com.example.domain.model.Category
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipSyncState
import com.example.domain.model.StorageCapacityStats
import com.example.domain.model.SyncProviderId
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipRepository
import com.example.domain.usecase.DateFilter
import com.example.domain.usecase.SaveClipUseCase
import com.example.domain.usecase.SearchFilter
import com.example.domain.usecase.SearchSortOrder
import com.example.domain.usecase.SmartSearchUseCase
import com.example.domain.usecase.StorageCapacityUseCase
import com.example.integrations.ProviderResult
import com.example.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AutoCapturedBannerState(
  val clip: ClipItem,
  val autoMarkedSensitive: Boolean,
  val sensitiveReason: String?,
  val queuedProviders: List<SyncProviderId>,
)

@HiltViewModel
class ClipboardViewModel
@Inject
constructor(
  private val clipRepository: ClipRepository,
  private val categoryRepository: CategoryRepository,
  private val saveClipUseCase: SaveClipUseCase,
  private val smartSearchUseCase: SmartSearchUseCase,
  private val clipboardHelper: SystemClipboardHelper,
  private val captureCoordinator: ClipboardCaptureCoordinator,
  private val storageCapacityUseCase: StorageCapacityUseCase,
  private val cryptoManager: KeystoreCryptoManager,
  private val prefs: AppPreferencesRepository,
  private val syncEngine: SyncEngine,
) : ViewModel() {

  companion object {
    const val PAGE_INCREMENT = 50
  }

  private val _storageStats = MutableStateFlow<StorageCapacityStats?>(null)
  val storageStats: StateFlow<StorageCapacityStats?> = _storageStats.asStateFlow()

  private val _pagedLimit = MutableStateFlow(PAGE_INCREMENT)
  val pagedLimit: StateFlow<Int> = _pagedLimit.asStateFlow()

  init {
    viewModelScope.launch {
      categoryRepository.ensureDefaultCategories()
      val settings = prefs.userSettings.first()
      if (settings.historyRetentionDays > 0) {
        clipRepository.purgeExpiredHistory(settings.historyRetentionDays)
      }
      refreshStorageStats()
    }
  }

  val activeClips: StateFlow<List<ClipItem>> =
    clipRepository
      .observeActiveClips()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val pagedActiveClips: StateFlow<List<ClipItem>> =
    _pagedLimit
      .flatMapLatest { limit -> clipRepository.observeActiveClipsPaged(limit) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val totalActiveClipCount: StateFlow<Int> =
    clipRepository
      .observeTotalActiveCount()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  val favoriteClips: StateFlow<List<ClipItem>> =
    clipRepository
      .observeFavoriteClips()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val trashedClips: StateFlow<List<ClipItem>> =
    clipRepository
      .observeTrashedClips()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val categories: StateFlow<List<Category>> =
    categoryRepository
      .observeCategories()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _searchFilter = MutableStateFlow(SearchFilter())
  val searchFilter: StateFlow<SearchFilter> = _searchFilter.asStateFlow()

  @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
  val searchResults: StateFlow<List<ClipItem>> =
    combine(
        _searchFilter.debounce { if (it.query.isNotBlank()) 120L else 0L },
        totalActiveClipCount,
      ) { filter, _ -> filter }
      .mapLatest { filter ->
        val candidates = clipRepository.searchActiveClipsFts(filter = filter, limit = 200)
        smartSearchUseCase(candidates, filter)
      }
      .flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _selectedClipIds = MutableStateFlow<Set<Long>>(emptySet())
  val selectedClipIds: StateFlow<Set<Long>> = _selectedClipIds.asStateFlow()

  private val _lastAutoCapturedBanner = MutableStateFlow<AutoCapturedBannerState?>(null)
  val lastAutoCapturedBanner: StateFlow<AutoCapturedBannerState?> =
    _lastAutoCapturedBanner.asStateFlow()

  private val _snackbarEvents = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
  val snackbarEvents: SharedFlow<UiText> = _snackbarEvents.asSharedFlow()

  fun observeClip(clipId: Long) = clipRepository.observeClipById(clipId)

  fun readSystemClipboardNow(): String? = clipboardHelper.readCurrentClipboardText()

  fun loadMorePagedClips() {
    _pagedLimit.value += PAGE_INCREMENT
  }

  fun refreshStorageStats() {
    viewModelScope.launch {
      _storageStats.value = runCatching { storageCapacityUseCase.computeStats() }.getOrNull()
    }
  }

  /**
   * Autonomous zero-prompt foreground capture (replaces old blocking Save confirmation prompt).
   */
  fun onAppWindowFocused() {
    viewModelScope.launch {
      when (
        val outcome =
          captureCoordinator.captureFromSystemClipboard(source = ClipSource.CLIPBOARD)
      ) {
        is CaptureOutcome.Saved -> {
          _lastAutoCapturedBanner.value =
            AutoCapturedBannerState(
              clip = outcome.clip,
              autoMarkedSensitive = outcome.autoMarkedSensitive,
              sensitiveReason = outcome.sensitiveReason,
              queuedProviders = outcome.queuedProviders,
            )
          refreshStorageStats()
        }
        is CaptureOutcome.SkippedSensitive -> {
          _snackbarEvents.emit(UiText.Resource(R.string.msg_sensitive_skipped))
        }
        else -> Unit
      }
    }
  }

  fun dismissAutoCapturedBanner() {
    _lastAutoCapturedBanner.value = null
  }

  fun undoAutoCapturedClip() {
    val banner = _lastAutoCapturedBanner.value ?: return
    _lastAutoCapturedBanner.value = null
    moveToTrash(listOf(banner.clip.id))
  }

  fun markBannerClipSensitive() {
    val banner = _lastAutoCapturedBanner.value ?: return
    _lastAutoCapturedBanner.value = null
    viewModelScope.launch {
      clipRepository.updateClip(
        banner.clip.copy(isSensitive = true, syncStatus = ClipSyncState.LOCAL_ONLY)
      )
      _snackbarEvents.emit(UiText.Resource(R.string.msg_marked_sensitive_local))
    }
  }

  fun saveClip(
    title: String,
    content: String,
    categoryId: Long?,
    tags: List<String>,
    isSensitive: Boolean,
    contentType: ClipContentType? = null,
    source: ClipSource = ClipSource.MANUAL,
  ) {
    viewModelScope.launch {
      val settings = prefs.userSettings.first()
      val initialStatus =
        if (isSensitive && settings.blockSensitiveCloudSync) ClipSyncState.LOCAL_ONLY
        else ClipSyncState.PENDING
      val result =
        saveClipUseCase(
          title = title,
          content = content,
          contentType = contentType,
          categoryId = categoryId,
          tags = tags,
          isSensitive = isSensitive,
          source = source,
          syncStatus = initialStatus,
        )
      if (result != null) {
        prefs.updateLastDetectedClipHash(cryptoManager.sha256(content))
        if (!isSensitive || !settings.blockSensitiveCloudSync) {
          captureCoordinator.enqueueClipForSync(result.clipId)
        }
        refreshStorageStats()
        _snackbarEvents.emit(
          UiText.Resource(
            if (result.wasDuplicateMerged) R.string.msg_clip_merged else R.string.msg_clip_saved
          )
        )
      }
    }
  }

  fun updateClip(clip: ClipItem) {
    viewModelScope.launch {
      clipRepository.updateClip(clip)
      if (!clip.isSensitive) {
        captureCoordinator.enqueueClipForSync(clip.id)
      }
      refreshStorageStats()
      _snackbarEvents.emit(UiText.Resource(R.string.msg_clip_updated))
    }
  }

  fun copyClip(clip: ClipItem) {
    viewModelScope.launch {
      prefs.updateLastDetectedClipHash(clip.contentHash)
      clipboardHelper.copyToClipboard(clip.title.ifBlank { "Clip" }, clip.content, clip.isSensitive)
      _snackbarEvents.emit(UiText.Resource(R.string.action_copied))
    }
  }

  fun togglePin(clip: ClipItem) {
    viewModelScope.launch { clipRepository.setPinned(clip.id, !clip.isPinned) }
  }

  fun toggleFavorite(clip: ClipItem) {
    viewModelScope.launch { clipRepository.setFavorite(clip.id, !clip.isFavorite) }
  }

  fun moveToTrash(ids: List<Long>) {
    viewModelScope.launch {
      clipRepository.moveToTrash(ids)
      _selectedClipIds.value = emptySet()
      refreshStorageStats()
      _snackbarEvents.emit(UiText.Resource(R.string.msg_moved_to_trash, listOf(ids.size)))
    }
  }

  fun deleteClipsFromStorage(ids: List<Long>, includeUnsynced: Boolean = false) {
    viewModelScope.launch {
      val victims = ids.mapNotNull { clipRepository.getClipById(it) }
      val allowed =
        if (includeUnsynced) victims
        else victims.filter { storageCapacityUseCase.isSafeToDeleteWithoutCloudLoss(it) }
      if (allowed.isEmpty()) {
        _snackbarEvents.emit(UiText.Resource(R.string.storage_unsynced_protected))
        return@launch
      }
      allowed.forEach { syncEngine.enqueueDeleteForClip(it) }
      clipRepository.moveToTrash(allowed.map { it.id })
      refreshStorageStats()
      _snackbarEvents.emit(UiText.Resource(R.string.msg_moved_to_trash, listOf(allowed.size)))
    }
  }

  fun restoreFromTrash(ids: List<Long>) {
    viewModelScope.launch {
      clipRepository.restoreFromTrash(ids)
      refreshStorageStats()
      _snackbarEvents.emit(UiText.Resource(R.string.msg_restored_items, listOf(ids.size)))
    }
  }

  fun deleteForever(ids: List<Long>) {
    viewModelScope.launch {
      for (id in ids) {
        clipRepository.getClipById(id)?.let { syncEngine.enqueueDeleteForClip(it) }
      }
      clipRepository.deleteForever(ids)
      refreshStorageStats()
      _snackbarEvents.emit(UiText.Resource(R.string.msg_deleted_forever, listOf(ids.size)))
    }
  }

  fun emptyTrash() {
    viewModelScope.launch {
      clipRepository.emptyTrash()
      refreshStorageStats()
      _snackbarEvents.emit(UiText.Resource(R.string.msg_trash_emptied))
    }
  }

  fun toggleSelection(id: Long) {
    _selectedClipIds.value =
      _selectedClipIds.value.let { current ->
        if (current.contains(id)) current - id else current + id
      }
  }

  fun selectAll(ids: List<Long>) {
    _selectedClipIds.value = ids.toSet()
  }

  fun clearSelection() {
    _selectedClipIds.value = emptySet()
  }

  fun moveSelectedToCategory(categoryId: Long) {
    val ids = _selectedClipIds.value.toList()
    if (ids.isEmpty()) return
    viewModelScope.launch {
      clipRepository.moveClipsToCategory(ids, categoryId)
      _selectedClipIds.value = emptySet()
      _snackbarEvents.emit(UiText.Resource(R.string.msg_moved_items, listOf(ids.size)))
    }
  }

  fun moveClipToCategory(clipId: Long, categoryId: Long) {
    viewModelScope.launch {
      clipRepository.moveClipsToCategory(listOf(clipId), categoryId)
      _snackbarEvents.emit(UiText.Resource(R.string.msg_moved_to_collection))
    }
  }

  fun createCategory(name: String, colorHex: Long) {
    if (name.isBlank()) return
    viewModelScope.launch {
      categoryRepository.createCategory(name, colorHex)
      _snackbarEvents.emit(UiText.Resource(R.string.msg_collection_created, listOf(name)))
    }
  }

  fun renameCategory(id: Long, newName: String, colorHex: Long? = null) {
    if (newName.isBlank()) return
    viewModelScope.launch {
      categoryRepository.renameCategory(id, newName, colorHex)
      _snackbarEvents.emit(UiText.Resource(R.string.msg_collection_updated))
    }
  }

  fun deleteCustomCategory(id: Long) {
    viewModelScope.launch {
      categoryRepository.deleteCustomCategory(id)
      _snackbarEvents.emit(UiText.Resource(R.string.msg_collection_deleted))
    }
  }

  // Search filter setters
  fun updateSearchQuery(q: String) {
    _searchFilter.value = _searchFilter.value.copy(query = q)
  }

  fun updateSearchCategory(categoryId: Long?) {
    _searchFilter.value = _searchFilter.value.copy(categoryId = categoryId)
  }

  fun updateSearchType(type: ClipContentType?) {
    _searchFilter.value = _searchFilter.value.copy(contentType = type)
  }

  fun updateSearchDateFilter(dateFilter: DateFilter) {
    _searchFilter.value = _searchFilter.value.copy(dateFilter = dateFilter)
  }

  fun updateSearchSortOrder(order: SearchSortOrder) {
    _searchFilter.value = _searchFilter.value.copy(sortOrder = order)
  }

  fun pushSingleClipToProvider(clip: ClipItem, providerId: SyncProviderId) {
    viewModelScope.launch {
      try {
        when (val res = syncEngine.pushSingleClip(clip, providerId)) {
          is ProviderResult.Success ->
            _snackbarEvents.emit(UiText.Dynamic("${providerId.displayName}: ${res.data}"))
          is ProviderResult.Error -> _snackbarEvents.emit(UiText.Dynamic(res.message))
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        _snackbarEvents.tryEmit(UiText.Dynamic(e.localizedMessage ?: "Push failed"))
      }
    }
  }
}
