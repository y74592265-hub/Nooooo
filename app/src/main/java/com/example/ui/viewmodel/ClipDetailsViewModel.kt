package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.clipboard.SystemClipboardHelper
import com.example.domain.intelligence.UsageLearningEngine
import com.example.domain.lineage.LineDiffEngine
import com.example.domain.model.ClipRevision
import com.example.domain.model.LineDiffResult
import com.example.domain.repository.ClipLineageRepository
import com.example.domain.repository.ClipRepository
import com.example.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ClipDetailsViewModel
@Inject
constructor(
  private val lineageRepository: ClipLineageRepository,
  private val clipRepository: ClipRepository,
  private val clipboardHelper: SystemClipboardHelper,
  private val usageLearningEngine: UsageLearningEngine,
) : ViewModel() {

  private val activeClipId = MutableStateFlow<Long?>(null)

  @OptIn(ExperimentalCoroutinesApi::class)
  val revisions: StateFlow<List<ClipRevision>> =
    activeClipId
      .flatMapLatest { id ->
        if (id == null || id <= 0L) flowOf(emptyList()) else lineageRepository.observeRevisions(id)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _events = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
  val events: SharedFlow<UiText> = _events.asSharedFlow()

  fun bindClip(clipId: Long) {
    if (activeClipId.value != clipId) {
      activeClipId.value = clipId
    }
  }

  suspend fun computeDiff(previousText: String, currentText: String): LineDiffResult =
    withContext(Dispatchers.Default) { LineDiffEngine.diff(previousText, currentText) }

  fun restoreRevision(clipId: Long, revisionId: Long) {
    viewModelScope.launch {
      val ok = lineageRepository.restoreRevision(clipId, revisionId)
      if (ok) {
        _events.emit(UiText.Resource(R.string.msg_revision_restored))
      }
    }
  }

  fun splitRevision(clipId: Long, revisionId: Long, onSplitCreated: (Long) -> Unit = {}) {
    viewModelScope.launch {
      val newClipId = lineageRepository.splitRevisionAsStandalone(clipId, revisionId)
      if (newClipId != null) {
        _events.emit(UiText.Resource(R.string.msg_revision_split))
        onSplitCreated(newClipId)
      }
    }
  }

  fun copyExtractedSnippet(snippet: String) {
    clipboardHelper.copyToClipboard("Smart Clipboard", snippet, isSensitive = false)
    viewModelScope.launch { _events.emit(UiText.Resource(R.string.action_copied)) }
  }

  fun applyContentTransform(clipId: Long, transformedContent: String) {
    viewModelScope.launch {
      val existing = clipRepository.getClipById(clipId) ?: return@launch
      if (existing.isSensitive || transformedContent == existing.content) return@launch
      clipRepository.updateClip(existing.copy(content = transformedContent))
      usageLearningEngine.recordUsage(clipId = clipId, isSensitive = false)
      _events.emit(UiText.Resource(R.string.msg_clip_updated))
    }
  }
}
