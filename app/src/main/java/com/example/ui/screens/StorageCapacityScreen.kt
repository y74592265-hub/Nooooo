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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.preferences.UserSettings
import com.example.domain.model.ClipSyncState
import com.example.domain.model.StorageCapacityStats
import com.example.domain.model.StorageThresholdLevel
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.VaultSectionCard
import com.example.ui.components.formatBytesHuman
import com.example.ui.theme.AppSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorageCapacityScreen(
  stats: StorageCapacityStats?,
  settings: UserSettings,
  onUpdateQuotaMb: (Int) -> Unit,
  onUpdateThresholds: (Int, Int, Int) -> Unit,
  onDeleteSelected: (ids: List<Long>, includeUnsynced: Boolean) -> Unit,
  onEmptyTrash: () -> Unit,
  onBackupNow: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
  var showForceUnsyncedConfirm by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    LazyColumn(
      modifier =
        Modifier
          .fillMaxSize()
          .widthIn(max = AppSpacing.maxContentWidth)
          .testTag("storage_lazy_list"),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      item {
        VaultSectionCard {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
              text = stringResource(R.string.nav_storage_capacity),
              style = MaterialTheme.typography.titleLarge,
            )
          }
          Text(
            text = stringResource(R.string.storage_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (stats != null) {
            val usedBytes = maxOf(stats.databaseFileBytes, stats.totalContentBytes)
            val progress = (stats.usagePercent.toFloat() / 100f).coerceIn(0f, 1f)
            LinearProgressIndicator(
              progress = { progress },
              color =
                when (stats.thresholdLevel) {
                  StorageThresholdLevel.CRITICAL_95 -> MaterialTheme.colorScheme.error
                  StorageThresholdLevel.HIGH_85 -> MaterialTheme.colorScheme.secondary
                  else -> MaterialTheme.colorScheme.primary
                },
              modifier = Modifier.fillMaxWidth().testTag("storage_progress_bar"),
            )
            Text(
              text =
                stringResource(
                  R.string.storage_used_format,
                  formatBytesHuman(usedBytes),
                  formatBytesHuman(stats.softQuotaBytes),
                  stats.usagePercent,
                ),
              style = MaterialTheme.typography.labelLarge,
            )
            Text(
              text =
                stringResource(
                  R.string.storage_active_clips,
                  stats.activeClipsCount,
                  formatBytesHuman(stats.activeContentBytes),
                ),
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              text =
                stringResource(
                  R.string.storage_trash_clips,
                  stats.trashedClipsCount,
                  formatBytesHuman(stats.trashedBytes),
                ),
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              text = stringResource(R.string.storage_db_size, formatBytesHuman(stats.databaseFileBytes)),
              style = MaterialTheme.typography.bodySmall,
            )
            if (stats.deviceTotalBytes > 0L) {
              Text(
                text =
                  stringResource(
                    R.string.storage_device_free,
                    formatBytesHuman(stats.deviceAvailableBytes),
                  ),
                style = MaterialTheme.typography.bodySmall,
              )
            }
            val unsyncedTotal = stats.pendingSyncCount + stats.failedSyncCount
            if (unsyncedTotal > 0) {
              Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.padding(10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Icon(Icons.Filled.WarningAmber, null, Modifier.size(18.dp))
                  Spacer(Modifier.width(8.dp))
                  Text(
                    text = stringResource(R.string.storage_unsynced_warning, unsyncedTotal),
                    style = MaterialTheme.typography.bodySmall,
                  )
                }
              }
            }
          }

          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBackupNow, modifier = Modifier.testTag("storage_backup_btn")) {
              Text(stringResource(R.string.storage_action_backup))
            }
            if ((stats?.trashedClipsCount ?: 0) > 0) {
              OutlinedButton(
                onClick = onEmptyTrash,
                modifier = Modifier.testTag("storage_empty_trash_btn"),
              ) {
                Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.trash_empty_btn))
              }
            }
          }
        }
      }

      // Thresholds & Quota Configurator
      item {
        VaultSectionCard {
          Text(
            text = stringResource(R.string.storage_thresholds_title),
            style = MaterialTheme.typography.titleMedium,
          )
          Text(
            text = stringResource(R.string.storage_quota_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 25, 50, 100).forEach { mb ->
              FilterChip(
                selected = settings.storageSoftQuotaMb == mb,
                onClick = { onUpdateQuotaMb(mb) },
                label = { Text("$mb MB") },
              )
            }
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
              selected = settings.storageWarnThreshold1 == 70,
              onClick = { onUpdateThresholds(70, 85, 95) },
              label = { Text("70% / 85% / 95%") },
            )
            FilterChip(
              selected = settings.storageWarnThreshold1 == 60,
              onClick = { onUpdateThresholds(60, 80, 90) },
              label = { Text("60% / 80% / 90%") },
            )
          }
        }
      }

      // Largest Clips Review List
      if (stats != null && stats.largestClips.isNotEmpty()) {
        item {
          SectionHeader(title = stringResource(R.string.storage_largest_clips_title))
        }
        if (selectedIds.isNotEmpty()) {
          item {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(
                onClick = {
                  onDeleteSelected(selectedIds.toList(), false)
                  selectedIds = emptySet()
                },
                modifier = Modifier.testTag("storage_delete_safe_btn"),
              ) {
                Icon(Icons.Filled.DeleteOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.storage_delete_selected_safe, selectedIds.size))
              }
              OutlinedButton(
                onClick = { showForceUnsyncedConfirm = true },
                colors =
                  ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                  ),
                modifier = Modifier.testTag("storage_delete_force_btn"),
              ) {
                Text(stringResource(R.string.storage_delete_selected_force, selectedIds.size))
              }
            }
          }
        }
        items(stats.largestClips, key = { it.id }) { clip ->
          val checked = clip.id in selectedIds
          val isSynced = clip.syncStatus == ClipSyncState.SYNCED
          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().testTag("storage_large_clip_${clip.id}"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(
                checked = checked,
                onCheckedChange = {
                  selectedIds = if (checked) selectedIds - clip.id else selectedIds + clip.id
                },
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text =
                    if (clip.isSensitive) stringResource(R.string.label_sensitive_masked)
                    else clip.title.ifBlank { clip.content },
                  style = MaterialTheme.typography.bodyMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = formatBytesHuman(clip.contentSizeBytes),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              StatusPill(
                icon = if (isSynced) Icons.Filled.CloudDone else Icons.Filled.WarningAmber,
                label =
                  stringResource(
                    if (isSynced) R.string.sync_badge_synced else R.string.sync_badge_local
                  ),
                containerColor =
                  if (isSynced) MaterialTheme.colorScheme.tertiaryContainer
                  else MaterialTheme.colorScheme.secondaryContainer,
                contentColor =
                  if (isSynced) MaterialTheme.colorScheme.onTertiaryContainer
                  else MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }
        }
      }

      item { Spacer(modifier = Modifier.height(76.dp)) }
    }
  }

  if (showForceUnsyncedConfirm) {
    AlertDialog(
      onDismissRequest = { showForceUnsyncedConfirm = false },
      title = { Text(stringResource(R.string.storage_include_unsynced_confirm_title)) },
      text = { Text(stringResource(R.string.storage_include_unsynced_confirm_msg)) },
      confirmButton = {
        TextButton(
          onClick = {
            showForceUnsyncedConfirm = false
            onDeleteSelected(selectedIds.toList(), true)
            selectedIds = emptySet()
          }
        ) {
          Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = { showForceUnsyncedConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}
