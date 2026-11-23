package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.preferences.UserSettings
import com.example.core.sync.SyncEngineState
import com.example.domain.model.DiscordConfig
import com.example.domain.model.GoogleDriveConfig
import com.example.domain.model.QueueSummary
import com.example.domain.model.SyncLog
import com.example.domain.model.SyncStatus
import com.example.domain.model.TelegramConfig
import com.example.domain.usecase.ExportFormat
import com.example.integrations.gdrive.DriveFileDto
import com.example.ui.components.ProvideSafeTextToolbar
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.components.VaultSectionCard
import com.example.ui.components.localizedRelativeTime
import com.example.ui.theme.AppSpacing
import com.example.ui.viewmodel.SyncIntegrationsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSyncScreen(
  viewModel: SyncIntegrationsViewModel,
  syncState: SyncEngineState,
  queueSummary: QueueSummary = QueueSummary(),
  syncLogs: List<SyncLog>,
  userSettings: UserSettings,
  telegramConfig: TelegramConfig,
  discordConfig: DiscordConfig,
  driveConfig: GoogleDriveConfig,
  driveBackups: List<DriveFileDto>,
  lastGlobalSyncAt: Long?,
  isBusy: Boolean,
  onOpenIntegrations: () -> Unit,
  onOpenRecoveryCenter: () -> Unit = {},
  onOpenStorageManager: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var pendingExportFormat by remember { mutableStateOf<ExportFormat?>(null) }
  var pendingPassphrase by remember { mutableStateOf<String?>(null) }
  var showPassphraseDialogFor by remember { mutableStateOf<String?>(null) }
  var pendingRawLocalImport by remember { mutableStateOf<String?>(null) }

  val createDocumentLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
      val fmt = pendingExportFormat ?: return@rememberLauncherForActivityResult
      if (uri == null) return@rememberLauncherForActivityResult
      scope.launch {
        runCatching {
          val text = viewModel.buildLocalExportContent(fmt, pendingPassphrase)
          context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
        }
        pendingPassphrase = null
      }
    }

  val openDocumentLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri == null) return@rememberLauncherForActivityResult
      scope.launch {
        val raw =
          context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: return@launch
        if (raw.contains("SMART_CLIPBOARD_ENC_V1")) {
          pendingRawLocalImport = raw
          showPassphraseDialogFor = "restore_local"
        } else {
          viewModel.restoreLocalFile(raw, null)
        }
      }
    }

  val tgReady =
    telegramConfig.enabled && telegramConfig.botToken.isNotBlank() && telegramConfig.chatId.isNotBlank()
  val dcReady =
    discordConfig.enabled &&
      (discordConfig.webhookUrl.isNotBlank() ||
        (discordConfig.botToken.isNotBlank() && discordConfig.channelId.isNotBlank()))

  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = AppSpacing.maxContentWidth),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // SECTION 1: Cloud Sync Engine & Provider Status & Persistent Queue
      item {
        VaultSectionCard {
          Image(
            painter = painterResource(id = R.drawable.img_sync_cloud_banner),
            contentDescription = stringResource(R.string.sync_section_cloud),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(124.dp).clip(MaterialTheme.shapes.small),
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
              Text(
                text = stringResource(R.string.sync_section_cloud),
                style = MaterialTheme.typography.titleLarge,
              )
              val lastSyncLabel =
                lastGlobalSyncAt?.let {
                  stringResource(R.string.sync_last_synced, localizedRelativeTime(it))
                } ?: stringResource(R.string.sync_never_synced)
              Text(
                text = syncState.lastMessage.ifBlank { lastSyncLabel },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Button(
              onClick = { viewModel.syncAllNow() },
              enabled = !syncState.isSyncing && !isBusy,
              modifier = Modifier.testTag("sync_all_now_btn"),
            ) {
              Icon(Icons.Filled.Sync, null, Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.action_sync_now))
            }
          }

          if (syncState.isSyncing || isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
          }

          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            ProviderStatusChip("Telegram", tgReady)
            ProviderStatusChip("Discord", dcReady)
            ProviderStatusChip("Google Drive", driveConfig.enabled)
          }

          // Persistent Sync Queue summary strip
          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().testTag("sync_queue_card"),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = stringResource(R.string.sync_queue_summary_title),
                  style = MaterialTheme.typography.labelLarge,
                )
                Text(
                  text =
                    stringResource(
                      R.string.sync_queue_counts,
                      queueSummary.pendingCount,
                      queueSummary.uploadingCount,
                      queueSummary.failedCount,
                    ),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              if (queueSummary.failedCount > 0) {
                TextButton(
                  onClick = { viewModel.retryFailedQueue() },
                  modifier = Modifier.testTag("retry_failed_queue_btn"),
                ) {
                  Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text(stringResource(R.string.sync_queue_retry_btn))
                }
              }
            }
          }

          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
              Text(
                text = stringResource(R.string.sync_auto_title),
                style = MaterialTheme.typography.titleMedium,
              )
              Text(
                text = stringResource(R.string.sync_auto_subtitle, userSettings.autoSyncIntervalHours),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Switch(
              checked = userSettings.autoSyncEnabled,
              onCheckedChange = { viewModel.toggleAutoSync(it, userSettings.autoSyncIntervalHours) },
              modifier = Modifier.testTag("auto_sync_switch"),
            )
          }

          OutlinedButton(
            onClick = onOpenIntegrations,
            modifier = Modifier.fillMaxWidth().testTag("open_integrations_btn"),
          ) {
            Icon(Icons.Filled.Hub, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sync_configure_providers))
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
              onClick = onOpenRecoveryCenter,
              modifier = Modifier.weight(1f).testTag("open_recovery_center_btn"),
            ) {
              Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.sync_open_recovery_btn))
            }
            OutlinedButton(
              onClick = onOpenStorageManager,
              modifier = Modifier.weight(1f).testTag("open_storage_manager_btn"),
            ) {
              Icon(Icons.Filled.Storage, null, Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.sync_open_storage_btn))
            }
          }
        }
      }

      // SECTION 2: Local Export & Restore
      item {
        VaultSectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
              text = stringResource(R.string.sync_section_local),
              style = MaterialTheme.typography.titleLarge,
            )
          }
          Text(
            text = stringResource(R.string.sync_local_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = {
                pendingExportFormat = ExportFormat.JSON
                pendingPassphrase = null
                createDocumentLauncher.launch("smart_clipboard_backup.json")
              },
              modifier = Modifier.testTag("export_json_btn"),
            ) {
              Text(stringResource(R.string.sync_export_json))
            }
            OutlinedButton(
              onClick = {
                pendingExportFormat = ExportFormat.CSV
                pendingPassphrase = null
                createDocumentLauncher.launch("smart_clipboard_export.csv")
              },
              modifier = Modifier.testTag("export_csv_btn"),
            ) {
              Text(stringResource(R.string.sync_export_csv))
            }
            OutlinedButton(
              onClick = {
                pendingExportFormat = ExportFormat.TXT
                pendingPassphrase = null
                createDocumentLauncher.launch("smart_clipboard_export.txt")
              },
              modifier = Modifier.testTag("export_txt_btn"),
            ) {
              Text(stringResource(R.string.sync_export_txt))
            }
            Button(
              onClick = { showPassphraseDialogFor = "export_enc" },
              modifier = Modifier.testTag("export_encrypted_btn"),
            ) {
              Icon(Icons.Filled.Lock, null, Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text(stringResource(R.string.sync_export_encrypted))
            }
          }

          OutlinedButton(
            onClick = { openDocumentLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth().testTag("import_local_btn"),
          ) {
            Icon(Icons.Filled.UploadFile, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.sync_restore_local))
          }
        }
      }

      // SECTION 3: Google Drive Cloud Backups
      item {
        VaultSectionCard {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CloudSync, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            Text(
              text = stringResource(R.string.sync_section_drive),
              style = MaterialTheme.typography.titleLarge,
            )
          }
          if (!driveConfig.enabled) {
            Text(
              text = stringResource(R.string.sync_drive_not_connected),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Button(
                onClick = {
                  if (driveConfig.encryptBackups) {
                    showPassphraseDialogFor = "drive_backup"
                  } else {
                    viewModel.backupToDriveNow(null, incrementalIfPossible = false)
                  }
                },
                enabled = !isBusy,
                modifier = Modifier.testTag("drive_backup_now_btn"),
              ) {
                Icon(Icons.Filled.Backup, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sync_drive_backup_now))
              }
              OutlinedButton(
                onClick = {
                  if (driveConfig.encryptBackups) {
                    showPassphraseDialogFor = "drive_delta"
                  } else {
                    viewModel.backupToDriveNow(null, incrementalIfPossible = true)
                  }
                },
                enabled = !isBusy && driveConfig.lastBackupAt != null,
                modifier = Modifier.testTag("drive_delta_backup_btn"),
              ) {
                Text(stringResource(R.string.drive_backup_delta_now))
              }
              OutlinedButton(
                onClick = { viewModel.refreshDriveBackups() },
                enabled = !isBusy,
              ) {
                Text(stringResource(R.string.sync_drive_refresh))
              }
            }

            driveBackups.forEach { file ->
              Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                      file.modifiedTime ?: "",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  TextButton(
                    onClick = {
                      if (file.name.endsWith(".scbak")) {
                        showPassphraseDialogFor = "restore_drive:${file.id}"
                      } else {
                        viewModel.restoreFromDrive(file.id, null)
                      }
                    }
                  ) {
                    Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_restore))
                  }
                }
              }
            }
          }
        }
      }

      // SECTION 4: Sync Activity Logs
      item {
        SectionHeader(
          title = stringResource(R.string.sync_section_logs),
          actionLabel = if (syncLogs.isNotEmpty()) stringResource(R.string.action_clear) else null,
          onAction = if (syncLogs.isNotEmpty()) viewModel::clearSyncLogs else null,
        )
      }

      if (syncLogs.isEmpty()) {
        item {
          Text(
            text = stringResource(R.string.sync_logs_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        items(syncLogs, key = { it.id }) { log ->
          val isError = log.status == SyncStatus.ERROR
          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
              ) {
                Icon(
                  imageVector =
                    if (isError) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                  contentDescription =
                    stringResource(
                      if (isError) R.string.sync_status_error else R.string.sync_status_success
                    ),
                  tint =
                    if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                  Text(
                    text = "${log.provider.displayName} • ${log.direction.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color =
                      if (isError) MaterialTheme.colorScheme.error
                      else MaterialTheme.colorScheme.onSurface,
                  )
                  Text(
                    text = log.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Text(
                text = localizedRelativeTime(log.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      item { Spacer(modifier = Modifier.height(76.dp)) }
    }
  }

  showPassphraseDialogFor?.let { action ->
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
      onDismissRequest = { showPassphraseDialogFor = null },
      title = { Text(stringResource(R.string.passphrase_title)) },
      text = {
        ProvideSafeTextToolbar {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.passphrase_hint))
            OutlinedTextField(
              value = passphrase,
              onValueChange = { passphrase = it },
              label = { Text(stringResource(R.string.passphrase_label)) },
              visualTransformation = PasswordVisualTransformation(),
              singleLine = true,
              modifier = Modifier.fillMaxWidth().testTag("backup_passphrase_input"),
            )
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val currentAction = action
            showPassphraseDialogFor = null
            when {
              currentAction == "export_enc" -> {
                pendingExportFormat = ExportFormat.ENCRYPTED_BACKUP
                pendingPassphrase = passphrase
                createDocumentLauncher.launch("smart_clipboard_encrypted.scbak")
              }
              currentAction == "drive_backup" -> viewModel.backupToDriveNow(passphrase, false)
              currentAction == "drive_delta" -> viewModel.backupToDriveNow(passphrase, true)
              currentAction == "restore_local" -> {
                pendingRawLocalImport?.let { viewModel.restoreLocalFile(it, passphrase) }
                pendingRawLocalImport = null
              }
              currentAction.startsWith("restore_drive:") -> {
                val fileId = currentAction.removePrefix("restore_drive:")
                viewModel.restoreFromDrive(fileId, passphrase)
              }
            }
          },
          enabled = passphrase.length >= 6,
          modifier = Modifier.testTag("confirm_passphrase_btn"),
        ) {
          Text(stringResource(R.string.action_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { showPassphraseDialogFor = null }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}

@Composable
private fun ProviderStatusChip(name: String, connected: Boolean) {
  StatusPill(
    icon = if (connected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
    label =
      "$name: " +
        stringResource(
          if (connected) R.string.sync_status_connected else R.string.sync_status_not_configured
        ),
    containerColor =
      if (connected) MaterialTheme.colorScheme.tertiaryContainer
      else MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor =
      if (connected) MaterialTheme.colorScheme.onTertiaryContainer
      else MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
