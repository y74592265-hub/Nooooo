package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentPasteGo
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.clipboard.QuickCaptureNotificationHelper
import com.example.core.clipboard.QuickTileInstaller
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.ThemeMode
import com.example.core.preferences.UserSettings
import com.example.ui.components.VaultSectionCard
import com.example.ui.components.rememberNotificationPermissionRequester
import com.example.ui.theme.AppSpacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  settings: UserSettings,
  onThemeChange: (ThemeMode) -> Unit,
  onLanguageChange: (AppLanguage) -> Unit,
  onAppLockToggle: (Boolean) -> Unit,
  onAutoCaptureToggle: (Boolean) -> Unit,
  onRetentionDaysChange: (Int) -> Unit,
  onClearSystemClipboard: () -> Unit,
  onWipeAllData: () -> Unit,
  onInstantAutoSaveToggle: (Boolean) -> Unit = {},
  onQuickCaptureNotificationToggle: (Boolean) -> Unit = {},
  onAutoDetectSensitiveToggle: (Boolean) -> Unit = {},
  onBlockSensitiveSyncToggle: (Boolean) -> Unit = {},
  onSkipAutoSaveSensitiveToggle: (Boolean) -> Unit = {},
  onSaveExcludedAppsCsv: (String) -> Unit = {},
  onDeleteCloudToggle: (Boolean) -> Unit = {},
  onOpenStorageManager: () -> Unit = {},
  onShowMessage: (String) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val requestNotificationPermission =
    rememberNotificationPermissionRequester(
      onGranted = { onQuickCaptureNotificationToggle(true) },
      onDenied = { onShowMessage(context.getString(R.string.quick_notif_permission_denied)) },
    )
  var showWipeConfirm by remember { mutableStateOf(false) }
  var excludedInput by remember { mutableStateOf(settings.excludedSourceAppsCsv) }

  LaunchedEffect(settings.excludedSourceAppsCsv) {
    if (excludedInput != settings.excludedSourceAppsCsv && excludedInput.isEmpty()) {
      excludedInput = settings.excludedSourceAppsCsv
    }
  }

  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .widthIn(max = AppSpacing.maxContentWidth)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Group 1: Appearance & Language
      VaultSectionCard {
        SettingsGroupHeader(
          icon = Icons.Filled.Palette,
          title = stringResource(R.string.settings_group_appearance),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
          text = stringResource(R.string.settings_theme_label),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          ThemeMode.entries.forEach { mode ->
            val labelRes =
              when (mode) {
                ThemeMode.SYSTEM -> R.string.settings_theme_system
                ThemeMode.LIGHT -> R.string.settings_theme_light
                ThemeMode.DARK -> R.string.settings_theme_dark
              }
            FilterChip(
              selected = settings.themeMode == mode,
              onClick = { onThemeChange(mode) },
              label = { Text(stringResource(labelRes)) },
              modifier = Modifier.testTag("theme_${mode.name.lowercase()}"),
            )
          }
        }

        Text(
          text = stringResource(R.string.settings_language_label),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          listOf(AppLanguage.ARABIC, AppLanguage.ENGLISH, AppLanguage.SYSTEM).forEach { lang ->
            val labelRes =
              when (lang) {
                AppLanguage.SYSTEM -> R.string.settings_lang_system
                AppLanguage.ENGLISH -> R.string.settings_lang_english
                AppLanguage.ARABIC -> R.string.settings_lang_arabic
              }
            FilterChip(
              selected = settings.language == lang,
              onClick = { onLanguageChange(lang) },
              label = { Text(stringResource(labelRes)) },
              modifier = Modifier.testTag("lang_${lang.name.lowercase()}"),
            )
          }
        }
      }

      // Group 2: Autonomous Capture & Optional Companion IME
      VaultSectionCard {
        SettingsGroupHeader(
          icon = Icons.Filled.ContentPasteGo,
          title = stringResource(R.string.settings_group_capture_ime),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsToggleRow(
          title = stringResource(R.string.settings_autocapture_title),
          subtitle = stringResource(R.string.settings_autocapture_subtitle),
          checked = settings.autoCaptureOnForeground,
          onCheckedChange = onAutoCaptureToggle,
          testTag = "auto_capture_switch",
        )

        SettingsToggleRow(
          title = stringResource(R.string.settings_instant_autosave_title),
          subtitle = stringResource(R.string.settings_instant_autosave_subtitle),
          checked = settings.instantAutoSave,
          onCheckedChange = onInstantAutoSaveToggle,
          testTag = "instant_autosave_switch",
        )

        SettingsToggleRow(
          title = stringResource(R.string.settings_quick_notif_title),
          subtitle = stringResource(R.string.settings_quick_notif_subtitle),
          checked = settings.quickCaptureNotificationEnabled,
          onCheckedChange = { enable ->
            if (!enable) {
              onQuickCaptureNotificationToggle(false)
            } else if (
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !QuickCaptureNotificationHelper.hasNotificationPermission(context)
            ) {
              requestNotificationPermission()
            } else {
              onQuickCaptureNotificationToggle(true)
            }
          },
          testTag = "quick_capture_notif_switch",
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
          text = stringResource(R.string.settings_multicopy_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
          onClick = { QuickTileInstaller.requestAddCaptureTile(context, onShowMessage) },
          modifier = Modifier.fillMaxWidth().testTag("add_qs_tile_btn"),
        ) {
          Icon(Icons.Filled.TouchApp, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.multicopy_add_tile_btn))
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Keyboard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
          )
          Text(
            text = stringResource(R.string.settings_ime_card_title),
            style = MaterialTheme.typography.titleMedium,
          )
        }
        Text(
          text = stringResource(R.string.settings_ime_card_subtitle),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = {
              runCatching {
                val intent =
                  Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                  }
                context.startActivity(intent)
              }
            },
            modifier = Modifier.weight(1f).testTag("open_ime_settings_btn"),
          ) {
            Text(stringResource(R.string.settings_ime_open_btn))
          }
          OutlinedButton(
            onClick = {
              runCatching {
                val imm =
                  context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
              }
            },
            modifier = Modifier.weight(1f).testTag("open_ime_picker_btn"),
          ) {
            Text(stringResource(R.string.settings_ime_picker_btn))
          }
        }
      }

      // Group 3: Security, Sensitive Detection & Exclusions
      VaultSectionCard {
        SettingsGroupHeader(
          icon = Icons.Filled.Security,
          title = stringResource(R.string.settings_group_security),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SettingsToggleRow(
          title = stringResource(R.string.settings_applock_title),
          subtitle = stringResource(R.string.settings_applock_subtitle),
          checked = settings.appLockEnabled,
          onCheckedChange = onAppLockToggle,
          testTag = "app_lock_switch",
        )

        SettingsToggleRow(
          title = stringResource(R.string.settings_detect_sensitive_title),
          subtitle = stringResource(R.string.settings_detect_sensitive_subtitle),
          checked = settings.autoDetectSensitivePatterns,
          onCheckedChange = onAutoDetectSensitiveToggle,
          testTag = "detect_sensitive_switch",
        )

        SettingsToggleRow(
          title = stringResource(R.string.settings_block_sensitive_sync_title),
          subtitle = stringResource(R.string.settings_block_sensitive_sync_subtitle),
          checked = settings.blockSensitiveCloudSync,
          onCheckedChange = onBlockSensitiveSyncToggle,
          testTag = "block_sensitive_sync_switch",
        )

        SettingsToggleRow(
          title = stringResource(R.string.settings_skip_sensitive_save_title),
          subtitle = stringResource(R.string.settings_skip_sensitive_save_subtitle),
          checked = settings.skipAutoSaveSensitive,
          onCheckedChange = onSkipAutoSaveSensitiveToggle,
          testTag = "skip_sensitive_save_switch",
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        OutlinedTextField(
          value = excludedInput,
          onValueChange = { excludedInput = it },
          label = { Text(stringResource(R.string.settings_excluded_apps_label)) },
          placeholder = { Text(stringResource(R.string.settings_excluded_apps_hint)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("excluded_apps_input"),
        )
        OutlinedButton(
          onClick = { onSaveExcludedAppsCsv(excludedInput) },
          modifier = Modifier.fillMaxWidth().testTag("save_exclusions_btn"),
        ) {
          Text(stringResource(R.string.settings_excluded_save_btn))
        }

        SettingsToggleRow(
          title = stringResource(R.string.settings_delete_cloud_title),
          subtitle = stringResource(R.string.settings_delete_cloud_subtitle),
          checked = settings.deleteCloudOnLocalDelete,
          onCheckedChange = onDeleteCloudToggle,
          testTag = "delete_cloud_switch",
        )

        OutlinedButton(
          onClick = onClearSystemClipboard,
          modifier = Modifier.fillMaxWidth().testTag("clear_sys_clipboard_btn"),
        ) {
          Icon(Icons.Filled.CleaningServices, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.settings_clear_sys_clipboard))
        }
      }

      // Group 4: History Retention, Storage Capacity & Data Control
      VaultSectionCard {
        SettingsGroupHeader(
          icon = Icons.Filled.Storage,
          title = stringResource(R.string.settings_group_data),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        OutlinedButton(
          onClick = onOpenStorageManager,
          modifier = Modifier.fillMaxWidth().testTag("settings_storage_btn"),
        ) {
          Icon(Icons.Filled.Storage, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.settings_open_storage_btn))
        }

        Text(
          text = stringResource(R.string.settings_retention_label),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          listOf(0, 7, 30, 90).forEach { days ->
            val label =
              if (days == 0) stringResource(R.string.settings_retention_forever)
              else stringResource(R.string.settings_retention_days, days)
            FilterChip(
              selected = settings.historyRetentionDays == days,
              onClick = { onRetentionDaysChange(days) },
              label = { Text(label) },
            )
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
          text = stringResource(R.string.settings_wipe_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
          onClick = { showWipeConfirm = true },
          colors =
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.fillMaxWidth().testTag("wipe_all_data_btn"),
        ) {
          Icon(Icons.Filled.DeleteForever, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.settings_wipe_btn))
        }
      }

      Spacer(modifier = Modifier.height(32.dp))
    }
  }

  if (showWipeConfirm) {
    AlertDialog(
      onDismissRequest = { showWipeConfirm = false },
      title = { Text(stringResource(R.string.settings_wipe_confirm_title)) },
      text = { Text(stringResource(R.string.settings_wipe_confirm_msg)) },
      confirmButton = {
        TextButton(
          onClick = {
            showWipeConfirm = false
            onWipeAllData()
          },
          modifier = Modifier.testTag("confirm_wipe_btn"),
        ) {
          Text(
            stringResource(R.string.settings_wipe_confirm_btn),
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
      dismissButton = {
        TextButton(onClick = { showWipeConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}

@Composable
private fun SettingsToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(text = title, style = MaterialTheme.typography.titleMedium)
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag(testTag),
    )
  }
}

@Composable
private fun SettingsGroupHeader(icon: ImageVector, title: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(text = title, style = MaterialTheme.typography.titleLarge)
  }
}

