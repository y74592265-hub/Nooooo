package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.KeystoreCryptoManager
import com.example.core.sync.SyncEngine
import com.example.domain.model.Category
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.SyncProviderId
import com.example.domain.repository.CategoryRepository
import com.example.domain.repository.ClipRepository
import com.example.domain.usecase.DateFilter
import com.example.domain.usecase.SaveClipUseCase
import com.example.domain.usecase.SearchFilter
import com.example.domain.usecase.SearchSortOrder
import com.example.domain.usecase.SmartSearchUseCase
import com.example.integrations.ProviderResult
import com.example.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ClipboardViewModel
@Inject
constructor(
  private val clipRepository: ClipRepository,
  private val categoryRepository: CategoryRepository,
  private val saveClipUseCase: SaveClipUseCase,
  private val smartSearchUseCase: SmartSearchUseCase,
  private val clipboardHelper: SystemClipboardHelper,
  private val cryptoManager: KeystoreCryptoManager,
  private val prefs: AppPreferencesRepository,
  private val syncEngine: SyncEngine,
) : ViewModel() {

  init {
    viewModelScope.launch {
      categoryRepository.ensureDefaultCategories()
      val settings = prefs.userSettings.first()
      if (settings.historyRetentionDays > 0) {
        clipRepository.purgeExpiredHistory(settings.historyRetentionDays)
      }
    }
  }

  val activeClips: StateFlow<List<ClipItem>> =
    clipRepository
      .observeActiveClips()
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

  @OptIn(FlowPreview::class)
  val searchResults: StateFlow<List<ClipItem>> =
    combine(
        activeClips,
        _searchFilter.debounce { if (it.query.isNotBlank()) 120L else 0L },
      ) { clips, filter ->
        smartSearchUseCase(clips, filter)
      }
      .flowOn(Dispatchers.Default)
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _selectedClipIds = MutableStateFlow<Set<Long>>(emptySet())
  val selectedClipIds: StateFlow<Set<Long>> = _selectedClipIds.asStateFlow()

  private val _detectedClipboardText = MutableStateFlow<String?>(null)
  val detectedClipboardText: StateFlow<String?> = _detectedClipboardText.asStateFlow()

  private val _snackbarEvents = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
  val snackbarEvents: SharedFlow<UiText> = _snackbarEvents.asSharedFlow()

  @Volatile private var lastObservedClipboardText: String? = null

  fun observeClip(clipId: Long) = clipRepository.observeClipById(clipId)

  fun readSystemClipboardNow(): String? = clipboardHelper.readCurrentClipboardText()

  fun onAppWindowFocused() {
    val currentText = clipboardHelper.readCurrentClipboardText() ?: return
    if (currentText == lastObservedClipboardText) return
    lastObservedClipboardText = currentText
    viewModelScope.launch {
      val settings = prefs.userSettings.first()
      if (!settings.autoCaptureOnForeground) return@launch
      val hash = withContext(Dispatchers.Default) { cryptoManager.sha256(currentText) }
      if (hash != settings.lastDetectedClipHash) {
        _detectedClipboardText.value = currentText
      }
    }
  }

  fun dismissDetectedClipboard() {
    viewModelScope.launch {
      _detectedClipboardText.value?.let { text ->
        prefs.updateLastDetectedClipHash(cryptoManager.sha256(text))
      }
      _detectedClipboardText.value = null
    }
  }

  fun saveDetectedClipboard() {
    val text = _detectedClipboardText.value ?: return
    viewModelScope.launch {
      prefs.updateLastDetectedClipHash(cryptoManager.sha256(text))
      _detectedClipboardText.value = null
      saveClip(
        title = "",
        content = text,
        categoryId = null,
        tags = emptyList(),
        isSensitive = false,
        source = ClipSource.CLIPBOARD,
      )
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
      val result =
        saveClipUseCase(
          title = title,
          content = content,
          contentType = contentType,
          categoryId = categoryId,
          tags = tags,
          isSensitive = isSensitive,
          source = source,
        )
      if (result != null) {
        prefs.updateLastDetectedClipHash(cryptoManager.sha256(content))
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
      _snackbarEvents.emit(UiText.Resource(R.string.msg_moved_to_trash, listOf(ids.size)))
    }
  }

  fun restoreFromTrash(ids: List<Long>) {
    viewModelScope.launch {
      clipRepository.restoreFromTrash(ids)
      _snackbarEvents.emit(UiText.Resource(R.string.msg_restored_items, listOf(ids.size)))
    }
  }

  fun deleteForever(ids: List<Long>) {
    viewModelScope.launch {
      clipRepository.deleteForever(ids)
      _snackbarEvents.emit(UiText.Resource(R.string.msg_deleted_forever, listOf(ids.size)))
    }
  }

  fun emptyTrash() {
    viewModelScope.launch {
      clipRepository.emptyTrash()
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
