package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.domain.model.ConflictResolution
import com.example.domain.model.ProviderHealthSummary
import com.example.domain.model.RecoveryMatchStatus
import com.example.domain.model.RecoveryScanResult
import com.example.domain.model.SyncProviderId
import com.example.domain.usecase.CloudRecoveryUseCase
import com.example.ui.components.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecoveryViewModel
@Inject
constructor(
  private val cloudRecoveryUseCase: CloudRecoveryUseCase,
) : ViewModel() {

  private val _healthSummaries = MutableStateFlow<List<ProviderHealthSummary>>(emptyList())
  val healthSummaries: StateFlow<List<ProviderHealthSummary>> = _healthSummaries.asStateFlow()

  private val _scanResult = MutableStateFlow<RecoveryScanResult?>(null)
  val scanResult: StateFlow<RecoveryScanResult?> = _scanResult.asStateFlow()

  private val _selectedRemoteIds = MutableStateFlow<Set<String>>(emptySet())
  val selectedRemoteIds: StateFlow<Set<String>> = _selectedRemoteIds.asStateFlow()

  private val _conflictResolutions = MutableStateFlow<Map<String, ConflictResolution>>(emptyMap())
  val conflictResolutions: StateFlow<Map<String, ConflictResolution>> =
    _conflictResolutions.asStateFlow()

  private val _isBusy = MutableStateFlow(false)
  val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

  private val _statusMessages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
  val statusMessages: SharedFlow<UiText> = _statusMessages.asSharedFlow()

  init {
    refreshHealth(runLiveTest = false)
  }

  fun refreshHealth(runLiveTest: Boolean = false) {
    viewModelScope.launch {
      _healthSummaries.value =
        runCatching { cloudRecoveryUseCase.getProviderHealthSummaries(runLiveTest) }
          .getOrDefault(emptyList())
    }
  }

  fun scanProviders(targetProviders: Set<SyncProviderId> = SyncProviderId.entries.toSet()) {
    if (_isBusy.value) return
    viewModelScope.launch {
      _isBusy.value = true
      try {
        val result = cloudRecoveryUseCase.scanProviders(targetProviders)
        _scanResult.value = result
        if (result.healthSummaries.isNotEmpty()) {
          _healthSummaries.value = result.healthSummaries
        }
        _selectedRemoteIds.value =
          result.items
            .filter { it.matchStatus == RecoveryMatchStatus.NEW_REMOTE }
            .map { it.remoteId }
            .toSet()
        _statusMessages.emit(
          UiText.Resource(R.string.recovery_scan_complete, listOf(result.items.size))
        )
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        _statusMessages.tryEmit(UiText.Dynamic(e.localizedMessage ?: "Cloud scan failed"))
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun toggleItemSelection(remoteId: String) {
    _selectedRemoteIds.value =
      _selectedRemoteIds.value.let { cur ->
        if (remoteId in cur) cur - remoteId else cur + remoteId
      }
  }

  fun selectAllMissing() {
    val result = _scanResult.value ?: return
    _selectedRemoteIds.value =
      result.items
        .filter { it.matchStatus == RecoveryMatchStatus.NEW_REMOTE }
        .map { it.remoteId }
        .toSet()
  }

  fun setConflictResolution(remoteId: String, resolution: ConflictResolution) {
    _conflictResolutions.value = _conflictResolutions.value + (remoteId to resolution)
  }

  fun restoreSelected() {
    val result = _scanResult.value ?: return
    val selected = result.items.filter { it.remoteId in _selectedRemoteIds.value }
    if (selected.isEmpty() || _isBusy.value) return
    viewModelScope.launch {
      _isBusy.value = true
      try {
        val summary = cloudRecoveryUseCase.applyRecovery(selected, _conflictResolutions.value)
        _statusMessages.emit(
          UiText.Resource(
            R.string.recovery_apply_summary,
            listOf(summary.restoredCount, summary.skippedDuplicatesCount + summary.skippedLocalNewerCount),
          )
        )
        // Re-scan or refresh match states cleanly
        val refreshed = cloudRecoveryUseCase.scanProviders()
        _scanResult.value = refreshed
        _selectedRemoteIds.value = emptySet()
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        _statusMessages.tryEmit(UiText.Dynamic(e.localizedMessage ?: "Restore failed"))
      } finally {
        _isBusy.value = false
      }
    }
  }

  fun restoreAllMissing() {
    selectAllMissing()
    restoreSelected()
  }
}
