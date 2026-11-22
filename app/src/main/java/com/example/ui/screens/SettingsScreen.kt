package com.example.ui.screens

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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.ThemeMode
import com.example.core.preferences.UserSettings
import com.example.ui.components.VaultSectionCard
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
  modifier: Modifier = Modifier,
) {
  var showWipeConfirm by remember { mutableStateOf(false) }

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

      // Group 2: Security & Privacy
      VaultSectionCard {
        SettingsGroupHeader(
          icon = Icons.Filled.Security,
          title = stringResource(R.string.settings_group_security),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              text = stringResource(R.string.settings_applock_title),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = stringResource(R.string.settings_applock_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = settings.appLockEnabled,
            onCheckedChange = onAppLockToggle,
            modifier = Modifier.testTag("app_lock_switch"),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
              text = stringResource(R.string.settings_autocapture_title),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = stringResource(R.string.settings_autocapture_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = settings.autoCaptureOnForeground,
            onCheckedChange = onAutoCaptureToggle,
            modifier = Modifier.testTag("auto_capture_switch"),
          )
        }

        OutlinedButton(
          onClick = onClearSystemClipboard,
          modifier = Modifier.fillMaxWidth().testTag("clear_sys_clipboard_btn"),
        ) {
          Icon(Icons.Filled.CleaningServices, contentDescription = null)
          Spacer(Modifier.width(8.dp))
          Text(stringResource(R.string.settings_clear_sys_clipboard))
        }
      }

      // Group 3: History Retention & Data Control
      VaultSectionCard {
        SettingsGroupHeader(
          icon = Icons.Filled.Storage,
          title = stringResource(R.string.settings_group_data),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

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
