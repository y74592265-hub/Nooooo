package com.example.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Category
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.SyncProviderId
import com.example.ui.components.AddEditClipSheet
import com.example.ui.components.ContentTypeBadge
import com.example.ui.components.LtrText
import com.example.ui.components.VaultSectionCard
import com.example.ui.components.localizedCategoryName
import com.example.ui.theme.AppSpacing
import com.example.ui.theme.FavoriteGold
import com.example.ui.theme.JetBrainsMonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipDetailsScreen(
  clip: ClipItem?,
  categories: List<Category>,
  onCopy: (ClipItem) -> Unit,
  onUpdateClip: (ClipItem) -> Unit,
  onTogglePin: (ClipItem) -> Unit,
  onToggleFavorite: (ClipItem) -> Unit,
  onDelete: (Long) -> Unit,
  onPushToProvider: (ClipItem, SyncProviderId) -> Unit,
  onPasteSystemClipboard: () -> String?,
  modifier: Modifier = Modifier,
) {
  if (clip == null) {
    Column(
      modifier = modifier.fillMaxSize().padding(32.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text(stringResource(R.string.details_not_found), style = MaterialTheme.typography.titleLarge)
    }
    return
  }

  val context = LocalContext.current
  var showEditSheet by remember { mutableStateOf(false) }
  var revealSensitive by remember(clip.id, clip.isSensitive) { mutableStateOf(!clip.isSensitive) }
  val category = categories.find { it.id == clip.categoryId }
  val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
  val shareTitle = stringResource(R.string.details_share_title)

  Column(
    modifier = modifier.fillMaxSize(),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .widthIn(max = AppSpacing.maxContentWidth)
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Top metadata row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        FlowRow(
          verticalArrangement = Arrangement.spacedBy(6.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          ContentTypeBadge(clip.contentType)
          AssistChip(
            onClick = { showEditSheet = true },
            label = { Text(localizedCategoryName(category)) },
          )
          if (clip.isSensitive) {
            AssistChip(
              onClick = { revealSensitive = !revealSensitive },
              leadingIcon = {
                Icon(
                  if (revealSensitive) Icons.Filled.LockOpen else Icons.Filled.Lock,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              label = {
                Text(
                  stringResource(
                    if (revealSensitive) R.string.details_hide else R.string.details_reveal
                  )
                )
              },
            )
          }
        }
        Row {
          IconButton(
            onClick = { onTogglePin(clip) },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("details_pin_btn"),
          ) {
            Icon(
              imageVector = if (clip.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
              contentDescription =
                stringResource(if (clip.isPinned) R.string.action_unpin else R.string.action_pin),
              tint =
                if (clip.isPinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(
            onClick = { onToggleFavorite(clip) },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("details_fav_btn"),
          ) {
            Icon(
              imageVector = if (clip.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
              contentDescription =
                stringResource(
                  if (clip.isFavorite) R.string.action_unfavorite else R.string.action_favorite
                ),
              tint =
                if (clip.isFavorite) FavoriteGold else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      if (clip.title.isNotBlank()) {
        Text(text = clip.title, style = MaterialTheme.typography.headlineSmall)
      }

      VaultSectionCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        val isTechnicalLtr =
          revealSensitive &&
            (clip.contentType == ClipContentType.CODE || clip.contentType == ClipContentType.LINK)
        SelectionContainer {
          Text(
            text =
              if (clip.isSensitive && !revealSensitive) {
                stringResource(R.string.label_sensitive_details_masked)
              } else {
                clip.content
              },
            style =
              when {
                clip.contentType == ClipContentType.CODE && revealSensitive ->
                  MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = JetBrainsMonoFamily,
                    textDirection = TextDirection.Ltr,
                  )
                isTechnicalLtr ->
                  MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr)
                else -> MaterialTheme.typography.bodyLarge
              },
            color =
              if (clip.isSensitive && !revealSensitive) MaterialTheme.colorScheme.tertiary
              else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      if (clip.tags.isNotEmpty()) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          clip.tags.forEach { tag ->
            Surface(
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Text(
                text = "#$tag",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              )
            }
          }
        }
      }

      // Primary actions
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Button(
          onClick = { onCopy(clip) },
          modifier = Modifier.weight(1f).testTag("details_copy_btn"),
        ) {
          Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_copy))
        }
        OutlinedButton(
          onClick = { showEditSheet = true },
          modifier = Modifier.weight(1f).testTag("details_edit_btn"),
        ) {
          Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_edit))
        }
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = {
            val sendIntent =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, clip.content)
              }
            context.startActivity(Intent.createChooser(sendIntent, shareTitle))
          },
          modifier = Modifier.testTag("details_share_btn"),
        ) {
          Icon(Icons.Filled.Share, null, Modifier.size(16.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_share))
        }

        if (clip.contentType == ClipContentType.LINK) {
          OutlinedButton(
            onClick = {
              val url =
                if (clip.content.startsWith("http")) clip.content.trim()
                else "https://${clip.content.trim()}"
              runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            }
          ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_open_url))
          }
        }

        if (!clip.isSensitive) {
          OutlinedButton(onClick = { onPushToProvider(clip, SyncProviderId.TELEGRAM) }) {
            Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.details_sync_telegram))
          }
          OutlinedButton(onClick = { onPushToProvider(clip, SyncProviderId.DISCORD) }) {
            Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.details_sync_discord))
          }
        }
      }

      VaultSectionCard {
        Text(
          text = stringResource(R.string.details_metadata_title),
          style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MetadataRow(
          label = stringResource(R.string.details_created),
          value = dateFmt.format(Date(clip.createdAt)),
        )
        MetadataRow(
          label = stringResource(R.string.details_updated),
          value = dateFmt.format(Date(clip.updatedAt)),
        )
        MetadataRow(
          label = stringResource(R.string.details_source),
          value = clip.source.name,
        )
        MetadataRow(
          label = stringResource(R.string.details_hash),
          value = "${clip.contentHash.take(16)}…",
          mono = true,
        )
      }

      OutlinedButton(
        onClick = { onDelete(clip.id) },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().testTag("details_delete_btn"),
      ) {
        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.details_delete_to_trash))
      }

      Spacer(modifier = Modifier.height(24.dp))
    }
  }

  if (showEditSheet) {
    AddEditClipSheet(
      categories = categories,
      initialTitle = clip.title,
      initialContent = clip.content,
      initialCategoryId = clip.categoryId,
      initialTags = clip.tags.joinToString(", "),
      initialSensitive = clip.isSensitive,
      initialType = clip.contentType,
      isEditing = true,
      onPasteSystemClipboard = onPasteSystemClipboard,
      onDismiss = { showEditSheet = false },
      onSave = { title, content, catId, tags, isSens, type ->
        showEditSheet = false
        onUpdateClip(
          clip.copy(
            title = title,
            content = content,
            categoryId = catId,
            tags = tags,
            isSensitive = isSens,
            contentType = type ?: clip.contentType,
          )
        )
      },
    )
  }
}

@Composable
private fun MetadataRow(label: String, value: String, mono: Boolean = false) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LtrText(
      text = value,
      style =
        if (mono) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}
