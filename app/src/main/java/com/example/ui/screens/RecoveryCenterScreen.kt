package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.CloudRecoveryItem
import com.example.domain.model.ConflictResolution
import com.example.domain.model.ProviderHealthSummary
import com.example.domain.model.RecoveryMatchStatus
import com.example.domain.model.RecoveryScanResult
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.VaultSectionCard
import com.example.ui.components.localizedRelativeTime
import com.example.ui.theme.AppSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecoveryCenterScreen(
  healthSummaries: List<ProviderHealthSummary>,
  scanResult: RecoveryScanResult?,
  selectedRemoteIds: Set<String>,
  conflictResolutions: Map<String, ConflictResolution>,
  isBusy: Boolean,
  onScanAll: () -> Unit,
  onToggleSelect: (String) -> Unit,
  onSelectMissingOnly: () -> Unit,
  onSetConflictResolution: (String, ConflictResolution) -> Unit,
  onRestoreSelected: () -> Unit,
  onRestoreAllMissing: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    LazyColumn(
      modifier =
        Modifier
          .fillMaxSize()
          .widthIn(max = AppSpacing.maxContentWidth)
          .testTag("recovery_lazy_list"),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        VaultSectionCard {
          Text(
            text = stringResource(R.string.nav_cloud_recovery),
            style = MaterialTheme.typography.titleLarge,
          )
          Text(
            text = stringResource(R.string.recovery_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }

          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Button(
              onClick = onScanAll,
              enabled = !isBusy,
              modifier = Modifier.testTag("recovery_scan_btn"),
            ) {
              Icon(Icons.Filled.CloudQueue, null, Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.recovery_scan_all_btn))
            }

            if (scanResult != null && scanResult.items.isNotEmpty()) {
              OutlinedButton(
                onClick = onSelectMissingOnly,
                enabled = !isBusy,
                modifier = Modifier.testTag("recovery_select_missing_btn"),
              ) {
                Text(stringResource(R.string.recovery_select_missing))
              }
              Button(
                onClick = onRestoreSelected,
                enabled = !isBusy && selectedRemoteIds.isNotEmpty(),
                modifier = Modifier.testTag("recovery_restore_selected_btn"),
              ) {
                Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.recovery_restore_selected_btn, selectedRemoteIds.size))
              }
              OutlinedButton(
                onClick = onRestoreAllMissing,
                enabled = !isBusy,
                modifier = Modifier.testTag("recovery_restore_missing_btn"),
              ) {
                Text(stringResource(R.string.recovery_restore_missing_btn))
              }
            }
          }
        }
      }

      // Provider health summaries
      item {
        SectionHeader(title = stringResource(R.string.recovery_health_title))
      }
      items(healthSummaries, key = { it.providerId.id }) { summary ->
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier.fillMaxWidth().testTag("health_card_${summary.providerId.id}"),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(summary.providerId.displayName, style = MaterialTheme.typography.titleMedium)
              Text(
                text = summary.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            StatusPill(
              icon =
                when {
                  summary.isConnected -> Icons.Filled.CheckCircle
                  summary.failedQueueCount > 0 -> Icons.Filled.ErrorOutline
                  else -> Icons.Filled.RadioButtonUnchecked
                },
              label =
                stringResource(
                  when {
                    summary.isConnected -> R.string.sync_status_connected
                    summary.failedQueueCount > 0 -> R.string.sync_status_error
                    else -> R.string.sync_status_not_configured
                  }
                ),
              containerColor =
                if (summary.isConnected) MaterialTheme.colorScheme.tertiaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor =
                if (summary.isConnected) MaterialTheme.colorScheme.onTertiaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      if (scanResult != null && scanResult.errorsByProvider.isNotEmpty()) {
        items(scanResult.errorsByProvider.entries.toList(), key = { "err_${it.key.id}" }) { (prov, err) ->
          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "${prov.displayName}: $err",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(12.dp),
            )
          }
        }
      }

      if (scanResult == null || scanResult.items.isEmpty()) {
        item {
          EmptyStateView(
            title = stringResource(R.string.recovery_empty_title),
            subtitle = stringResource(R.string.recovery_empty_subtitle),
            showArtwork = false,
            icon = Icons.Filled.CloudDownload,
          )
        }
      } else {
        items(scanResult.items, key = { it.key }) { item ->
          RecoveryItemRow(
            item = item,
            isSelected = item.remoteId in selectedRemoteIds,
            resolution = conflictResolutions[item.remoteId] ?: defaultResolutionFor(item.matchStatus),
            onToggleSelect = { onToggleSelect(item.remoteId) },
            onSetResolution = { res -> onSetConflictResolution(item.remoteId, res) },
          )
        }
      }

      item { Spacer(modifier = Modifier.height(76.dp)) }
    }
  }
}

private fun defaultResolutionFor(status: RecoveryMatchStatus): ConflictResolution =
  when (status) {
    RecoveryMatchStatus.LOCAL_NEWER -> ConflictResolution.KEEP_LOCAL
    RecoveryMatchStatus.CONFLICT -> ConflictResolution.KEEP_BOTH
    else -> ConflictResolution.KEEP_BOTH
  }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecoveryItemRow(
  item: CloudRecoveryItem,
  isSelected: Boolean,
  resolution: ConflictResolution,
  onToggleSelect: () -> Unit,
  onSetResolution: (ConflictResolution) -> Unit,
) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color =
      if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
      else MaterialTheme.colorScheme.surface,
    border =
      BorderStroke(
        1.dp,
        if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
      ),
    modifier = Modifier.fillMaxWidth().testTag("recovery_item_${item.contentHash.take(8)}"),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
          Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
          Column {
            Text(
              text = "${item.providerId.displayName} • ${localizedRelativeTime(item.remoteTimestamp)}",
              style = MaterialTheme.typography.labelLarge,
            )
            val statusLabel =
              stringResource(
                when (item.matchStatus) {
                  RecoveryMatchStatus.NEW_REMOTE -> R.string.recovery_status_new
                  RecoveryMatchStatus.ALREADY_SYNCED -> R.string.recovery_status_synced
                  RecoveryMatchStatus.LOCAL_NEWER -> R.string.recovery_status_local_newer
                  RecoveryMatchStatus.CONFLICT -> R.string.recovery_status_conflict
                }
              )
            StatusPill(
              icon =
                when (item.matchStatus) {
                  RecoveryMatchStatus.NEW_REMOTE -> Icons.Filled.CloudDownload
                  RecoveryMatchStatus.ALREADY_SYNCED -> Icons.Filled.CheckCircle
                  RecoveryMatchStatus.LOCAL_NEWER,
                  RecoveryMatchStatus.CONFLICT -> Icons.Filled.WarningAmber
                },
              label = statusLabel,
              containerColor =
                when (item.matchStatus) {
                  RecoveryMatchStatus.NEW_REMOTE -> MaterialTheme.colorScheme.primaryContainer
                  RecoveryMatchStatus.ALREADY_SYNCED -> MaterialTheme.colorScheme.tertiaryContainer
                  else -> MaterialTheme.colorScheme.errorContainer
                },
              contentColor =
                when (item.matchStatus) {
                  RecoveryMatchStatus.NEW_REMOTE -> MaterialTheme.colorScheme.onPrimaryContainer
                  RecoveryMatchStatus.ALREADY_SYNCED -> MaterialTheme.colorScheme.onTertiaryContainer
                  else -> MaterialTheme.colorScheme.onErrorContainer
                },
            )
          }
        }
      }

      if (item.title.isNotBlank()) {
        Text(text = item.title, style = MaterialTheme.typography.titleMedium)
      }
      Text(
        text = item.content,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )

      if (
        item.matchStatus == RecoveryMatchStatus.CONFLICT ||
          item.matchStatus == RecoveryMatchStatus.LOCAL_NEWER
      ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(
            selected = resolution == ConflictResolution.KEEP_LOCAL,
            onClick = { onSetResolution(ConflictResolution.KEEP_LOCAL) },
            label = { Text(stringResource(R.string.recovery_conflict_keep_local)) },
          )
          FilterChip(
            selected = resolution == ConflictResolution.KEEP_BOTH,
            onClick = { onSetResolution(ConflictResolution.KEEP_BOTH) },
            label = { Text(stringResource(R.string.recovery_conflict_keep_both)) },
          )
          FilterChip(
            selected = resolution == ConflictResolution.OVERWRITE_WITH_CLOUD,
            onClick = { onSetResolution(ConflictResolution.OVERWRITE_WITH_CLOUD) },
            label = { Text(stringResource(R.string.recovery_conflict_overwrite)) },
          )
        }
      }
    }
  }
}
