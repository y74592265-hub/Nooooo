package com.example.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.clipboard.QuickCaptureNotificationHelper
import com.example.domain.model.Category
import com.example.domain.model.ClipItem
import com.example.domain.model.StorageCapacityStats
import com.example.domain.model.StorageThresholdLevel
import com.example.ui.components.ClipCard
import com.example.ui.components.EmptyStateView
import com.example.ui.components.SectionHeader
import com.example.ui.components.StorageCapacityBanner
import com.example.ui.components.localizedCategoryName
import com.example.ui.components.rememberNotificationPermissionRequester
import com.example.ui.theme.AppSpacing
import com.example.ui.theme.FavoriteGold
import com.example.ui.viewmodel.AutoCapturedBannerState

@Composable
fun HomeScreen(
  clips: List<ClipItem>,
  totalActiveCount: Int = clips.size,
  categories: List<Category>,
  autoCapturedBanner: AutoCapturedBannerState?,
  storageStats: StorageCapacityStats? = null,
  onCaptureClipboardNow: () -> Unit,
  onUndoAutoCaptured: () -> Unit,
  onMarkBannerSensitive: () -> Unit,
  onDismissAutoCapturedBanner: () -> Unit,
  onAddNewClip: () -> Unit,
  onOpenClipDetails: (Long) -> Unit,
  onCopyClip: (ClipItem) -> Unit,
  onTogglePin: (ClipItem) -> Unit,
  onToggleFavorite: (ClipItem) -> Unit,
  onOpenFavorites: () -> Unit,
  onOpenIntegrations: () -> Unit,
  onOpenRecovery: () -> Unit = {},
  onOpenStorage: () -> Unit = {},
  onOpenTrash: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenBackupSync: () -> Unit,
  showMultiCopyGuide: Boolean = false,
  quickCaptureNotificationEnabled: Boolean = false,
  onToggleQuickCaptureNotification: (Boolean) -> Unit = {},
  onShowMessage: (String) -> Unit = {},
  onAddQuickCaptureTile: () -> Unit = {},
  onOpenImeSettings: () -> Unit = {},
  onDismissMultiCopyGuide: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val requestNotificationPermission =
    rememberNotificationPermissionRequester(
      onGranted = { onToggleQuickCaptureNotification(true) },
      onDenied = { onShowMessage(context.getString(R.string.quick_notif_permission_denied)) },
    )
  val categoryMap = remember(categories) { categories.associateBy { it.id } }
  val pinnedClips = remember(clips) { clips.filter { it.isPinned } }
  val favoriteCount = remember(clips) { clips.count { it.isFavorite } }
  val recentClips = remember(clips) { clips.take(25) }

  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = AppSpacing.maxContentWidth),
      contentPadding = PaddingValues(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Compact Vault Overview Strip
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          VaultStatTile(
            icon = Icons.Filled.Inventory2,
            label = stringResource(R.string.home_stat_total),
            value = "$totalActiveCount",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
          )
          VaultStatTile(
            icon = Icons.Filled.PushPin,
            label = stringResource(R.string.home_stat_pinned),
            value = "${pinnedClips.size}",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
          )
          VaultStatTile(
            icon = Icons.Filled.Star,
            label = stringResource(R.string.home_stat_favorites),
            value = "$favoriteCount",
            tint = FavoriteGold,
            modifier = Modifier.weight(1f),
          )
        }
      }

      // Quick Actions Strip
      item {
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          AssistChip(
            onClick = onCaptureClipboardNow,
            modifier = Modifier.testTag("home_capture_chip"),
            leadingIcon = { Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.action_paste_from_clipboard)) },
            colors =
              AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
              ),
          )
          AssistChip(
            onClick = onOpenRecovery,
            modifier = Modifier.testTag("home_recovery_chip"),
            leadingIcon = { Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.home_recovery_chip)) },
          )
          AssistChip(
            onClick = onOpenStorage,
            modifier = Modifier.testTag("home_storage_chip"),
            leadingIcon = { Icon(Icons.Filled.Storage, null, Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.home_storage_chip)) },
          )
          AssistChip(
            onClick = onOpenFavorites,
            modifier = Modifier.testTag("home_favorites_chip"),
            leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.nav_favorites)) },
          )
          AssistChip(
            onClick = onOpenIntegrations,
            modifier = Modifier.testTag("home_integrations_chip"),
            leadingIcon = { Icon(Icons.Filled.Hub, null, Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.nav_integrations)) },
          )
          AssistChip(
            onClick = onOpenBackupSync,
            modifier = Modifier.testTag("home_sync_chip"),
            leadingIcon = { Icon(Icons.Filled.CloudSync, null, Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.nav_backup_sync)) },
          )
          AssistChip(
            onClick = onOpenTrash,
            modifier = Modifier.testTag("home_trash_chip"),
            leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.nav_trash)) },
          )
          AssistChip(
            onClick = onOpenSettings,
            modifier = Modifier.testTag("home_settings_chip"),
            leadingIcon = { Icon(Icons.Filled.Settings, null, Modifier.size(18.dp)) },
            label = { Text(stringResource(R.string.nav_settings)) },
          )
        }
      }

      // Non-intrusive Storage Threshold Warning Banner
      if (storageStats != null && storageStats.thresholdLevel != StorageThresholdLevel.NORMAL) {
        item {
          StorageCapacityBanner(
            stats = storageStats,
            onBackupNow = onOpenBackupSync,
            onManageStorage = onOpenStorage,
            modifier = Modifier.testTag("home_storage_banner"),
          )
        }
      }

      // Multi-Copy Guide Card (explains Android 10+ background rule & 1-tap QS Tile / Quick Save)
      if (showMultiCopyGuide) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth().testTag("multicopy_guide_card"),
          ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                  Icon(
                    imageVector = Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(Modifier.width(8.dp))
                  Text(
                    text = stringResource(R.string.multicopy_guide_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                  )
                }
                TextButton(
                  onClick = onDismissMultiCopyGuide,
                  modifier = Modifier.testTag("dismiss_multicopy_guide_btn"),
                ) {
                  Text(stringResource(R.string.multicopy_dismiss_btn))
                }
              }
              Text(
                text = stringResource(R.string.multicopy_guide_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.92f),
              )
              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                OutlinedButton(
                  onClick = {
                    val enable = !quickCaptureNotificationEnabled
                    if (!enable) {
                      onToggleQuickCaptureNotification(false)
                    } else if (
                      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !QuickCaptureNotificationHelper.hasNotificationPermission(context)
                    ) {
                      requestNotificationPermission()
                    } else {
                      onToggleQuickCaptureNotification(true)
                    }
                  },
                  modifier = Modifier.testTag("home_toggle_notif_btn"),
                ) {
                  Text(
                    if (quickCaptureNotificationEnabled) stringResource(R.string.quick_notif_action)
                    else stringResource(R.string.settings_quick_notif_title)
                  )
                }
                OutlinedButton(
                  onClick = onAddQuickCaptureTile,
                  modifier = Modifier.testTag("home_add_qs_tile_btn"),
                ) {
                  Text(stringResource(R.string.multicopy_add_tile_btn))
                }
                OutlinedButton(
                  onClick = onOpenImeSettings,
                  modifier = Modifier.testTag("home_open_ime_btn"),
                ) {
                  Text(stringResource(R.string.settings_ime_open_btn))
                }
              }
            }
          }
        }
      }

      // Non-Blocking Instant Auto-Captured Status Indicator (replaces manual confirmation dialog)
      item {
        AnimatedVisibility(
          visible = autoCapturedBanner != null,
          enter = fadeIn() + expandVertically(),
          exit = fadeOut() + shrinkVertically(),
        ) {
          val banner = autoCapturedBanner ?: return@AnimatedVisibility
          Surface(
            color =
              if (banner.autoMarkedSensitive) MaterialTheme.colorScheme.tertiaryContainer
              else MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth().testTag("auto_captured_banner"),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                  Icon(
                    imageVector =
                      if (banner.autoMarkedSensitive) Icons.Filled.Lock else Icons.Filled.Done,
                    contentDescription = null,
                    tint =
                      if (banner.autoMarkedSensitive) MaterialTheme.colorScheme.onTertiaryContainer
                      else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                  )
                  Spacer(Modifier.width(8.dp))
                  Text(
                    text =
                      if (banner.autoMarkedSensitive) {
                        stringResource(
                          R.string.home_auto_saved_sensitive,
                          banner.sensitiveReason ?: "Protected",
                        )
                      } else {
                        stringResource(R.string.home_auto_saved_title)
                      },
                    style = MaterialTheme.typography.labelLarge,
                    color =
                      if (banner.autoMarkedSensitive) MaterialTheme.colorScheme.onTertiaryContainer
                      else MaterialTheme.colorScheme.onPrimaryContainer,
                  )
                }
                TextButton(
                  onClick = onDismissAutoCapturedBanner,
                  modifier = Modifier.testTag("dismiss_auto_captured_btn"),
                ) {
                  Text(stringResource(R.string.action_close))
                }
              }
              if (!banner.autoMarkedSensitive) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = banner.clip.content,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              Spacer(modifier = Modifier.height(8.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                  onClick = onUndoAutoCaptured,
                  modifier = Modifier.testTag("undo_auto_captured_btn"),
                ) {
                  Text(stringResource(R.string.home_action_undo))
                }
                if (!banner.autoMarkedSensitive) {
                  TextButton(
                    onClick = onMarkBannerSensitive,
                    modifier = Modifier.testTag("mark_sensitive_banner_btn"),
                  ) {
                    Text(stringResource(R.string.home_action_mark_sensitive))
                  }
                }
              }
            }
          }
        }
      }

      // Pinned Carousel
      if (pinnedClips.isNotEmpty()) {
        item {
          SectionHeader(
            title = stringResource(R.string.home_pinned_clips),
            trailingText = "${pinnedClips.size}",
          )
        }
        item {
          LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(pinnedClips, key = { "pin_${it.id}" }, contentType = { "pinned_card" }) { clip ->
              Surface(
                onClick = { onOpenClipDetails(clip.id) },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.width(236.dp).testTag("pinned_card_${clip.id}"),
              ) {
                Column(modifier = Modifier.padding(14.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Text(
                      text =
                        clip.title.ifBlank { localizedCategoryName(categoryMap[clip.categoryId]) },
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.primary,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f),
                    )
                    Icon(
                      imageVector = Icons.Filled.PushPin,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(14.dp),
                    )
                  }
                  Spacer(modifier = Modifier.height(6.dp))
                  Text(
                    text =
                      if (clip.isSensitive) stringResource(R.string.label_sensitive_masked)
                      else clip.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Spacer(modifier = Modifier.height(10.dp))
                  OutlinedButton(
                    onClick = { onCopyClip(clip) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                  ) {
                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_copy), style = MaterialTheme.typography.labelMedium)
                  }
                }
              }
            }
          }
        }
      }

      // Recent Section Header
      item {
        SectionHeader(
          title = stringResource(R.string.home_recent_clips),
          trailingText = "$totalActiveCount",
        )
      }

      if (recentClips.isEmpty()) {
        item {
          EmptyStateView(
            title = stringResource(R.string.empty_clips_title),
            subtitle = stringResource(R.string.empty_clips_subtitle),
            actionLabel = stringResource(R.string.action_add_clip),
            onAction = onAddNewClip,
          )
        }
      } else {
        items(recentClips, key = { it.id }, contentType = { "clip_card" }) { clip ->
          val onOpen = remember(clip.id) { { onOpenClipDetails(clip.id) } }
          val onCopy = remember(clip) { { onCopyClip(clip) } }
          val onPin = remember(clip) { { onTogglePin(clip) } }
          val onFav = remember(clip) { { onToggleFavorite(clip) } }
          ClipCard(
            clip = clip,
            category = categoryMap[clip.categoryId],
            onClick = onOpen,
            onCopyClick = onCopy,
            onPinToggle = onPin,
            onFavoriteToggle = onFav,
          )
        }
      }

      item { Spacer(modifier = Modifier.height(76.dp)) }
    }
  }
}

@Composable
private fun VaultStatTile(
  icon: ImageVector,
  label: String,
  value: String,
  tint: androidx.compose.ui.graphics.Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainerLow,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = tint,
          modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = label,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = value,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
