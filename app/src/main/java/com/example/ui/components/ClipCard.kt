package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Category
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSyncState
import com.example.ui.theme.FavoriteGold
import com.example.ui.theme.JetBrainsMonoFamily

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ClipCard(
  clip: ClipItem,
  category: Category?,
  isSelected: Boolean = false,
  inSelectionMode: Boolean = false,
  onClick: () -> Unit,
  onLongClick: () -> Unit = {},
  onCopyClick: () -> Unit,
  onPinToggle: () -> Unit,
  onFavoriteToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cardShape = MaterialTheme.shapes.medium
  val containerColor =
    when {
      isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
      clip.isPinned -> MaterialTheme.colorScheme.surfaceContainerLow
      else -> MaterialTheme.colorScheme.surface
    }

  val border =
    when {
      isSelected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
      clip.isPinned -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
      else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    }

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .testTag("clip_card_${clip.id}")
        .clip(cardShape)
        .background(containerColor, cardShape)
        .border(border, cardShape)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(16.dp)
  ) {
    // Header row: Category dot + localized name + type badge + encrypted lock + relative time
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f),
      ) {
        if (isSelected) {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
        } else {
          Box(
            modifier =
              Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color(category?.colorHex ?: 0xFF3B82F6))
          )
          Spacer(modifier = Modifier.width(8.dp))
        }

        Text(
          text = localizedCategoryName(category),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.width(8.dp))
        ContentTypeBadge(contentType = clip.contentType)

        if (clip.isSensitive) {
          Spacer(modifier = Modifier.width(6.dp))
          Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = stringResource(R.string.label_sensitive),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(15.dp),
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = localizedRelativeTime(clip.updatedAt),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    if (clip.title.isNotBlank()) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = clip.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    val previewText =
      if (clip.isSensitive) {
        stringResource(R.string.label_sensitive_chars, clip.content.length)
      } else {
        clip.content
      }

    val isTechnicalLtr =
      !clip.isSensitive &&
        (clip.contentType == ClipContentType.CODE || clip.contentType == ClipContentType.LINK)

    Text(
      text = previewText,
      style =
        when {
          clip.contentType == ClipContentType.CODE && !clip.isSensitive ->
            MaterialTheme.typography.bodyMedium.copy(
              fontFamily = JetBrainsMonoFamily,
              textDirection = TextDirection.Ltr,
            )
          isTechnicalLtr ->
            MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr)
          else -> MaterialTheme.typography.bodyLarge
        },
      color =
        if (clip.isSensitive) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
      maxLines = 4,
      overflow = TextOverflow.Ellipsis,
    )

    if (clip.tags.isNotEmpty()) {
      Spacer(modifier = Modifier.height(10.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        val tagShape = RoundedCornerShape(8.dp)
        clip.tags.take(4).forEach { tag ->
          Text(
            text = "#$tag",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
              Modifier
                .clip(tagShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 8.dp, vertical = 3.dp),
          )
        }
      }
    }

    if (!inSelectionMode) {
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        SyncStateBadge(syncStatus = clip.syncStatus)

        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onPinToggle,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("pin_button_${clip.id}"),
          ) {
            Icon(
              imageVector = if (clip.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
              contentDescription =
                stringResource(if (clip.isPinned) R.string.action_unpin else R.string.action_pin),
              tint =
                if (clip.isPinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          }
          IconButton(
            onClick = onFavoriteToggle,
            modifier =
              Modifier.minimumInteractiveComponentSize().testTag("favorite_button_${clip.id}"),
          ) {
            Icon(
              imageVector = if (clip.isFavorite) Icons.Filled.Star else Icons.Outlined.StarOutline,
              contentDescription =
                stringResource(
                  if (clip.isFavorite) R.string.action_unfavorite else R.string.action_favorite
                ),
              tint =
                if (clip.isFavorite) FavoriteGold else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          }
          Spacer(modifier = Modifier.width(4.dp))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
              Modifier
                .minimumInteractiveComponentSize()
                .testTag("copy_button_${clip.id}")
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(role = Role.Button, onClick = onCopyClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.ContentCopy,
              contentDescription = stringResource(R.string.action_copy),
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = stringResource(R.string.action_copy),
              style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
              color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }
    }
  }
}

@Composable
fun SyncStateBadge(syncStatus: ClipSyncState) {
  val (icon, labelRes, bg, fg) =
    when (syncStatus) {
      ClipSyncState.LOCAL_ONLY ->
        Tuple4(
          Icons.Filled.PhoneAndroid,
          R.string.sync_badge_local,
          MaterialTheme.colorScheme.surfaceContainerHigh,
          MaterialTheme.colorScheme.onSurfaceVariant,
        )
      ClipSyncState.PENDING ->
        Tuple4(
          Icons.Filled.Schedule,
          R.string.sync_badge_pending,
          MaterialTheme.colorScheme.secondaryContainer,
          MaterialTheme.colorScheme.onSecondaryContainer,
        )
      ClipSyncState.UPLOADING ->
        Tuple4(
          Icons.Filled.CloudUpload,
          R.string.sync_badge_uploading,
          MaterialTheme.colorScheme.primaryContainer,
          MaterialTheme.colorScheme.onPrimaryContainer,
        )
      ClipSyncState.SYNCED ->
        Tuple4(
          Icons.Filled.CloudDone,
          R.string.sync_badge_synced,
          MaterialTheme.colorScheme.tertiaryContainer,
          MaterialTheme.colorScheme.onTertiaryContainer,
        )
      ClipSyncState.FAILED ->
        Tuple4(
          Icons.Filled.ErrorOutline,
          R.string.sync_badge_failed,
          MaterialTheme.colorScheme.errorContainer,
          MaterialTheme.colorScheme.onErrorContainer,
        )
      ClipSyncState.CONFLICT ->
        Tuple4(
          Icons.Filled.WarningAmber,
          R.string.sync_badge_conflict,
          MaterialTheme.colorScheme.errorContainer,
          MaterialTheme.colorScheme.onErrorContainer,
        )
    }

  StatusPill(
    icon = icon,
    label = stringResource(labelRes),
    containerColor = bg,
    contentColor = fg,
  )
}

private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
fun ContentTypeBadge(contentType: ClipContentType) {
  val icon =
    when (contentType) {
      ClipContentType.LINK -> Icons.Filled.Link
      ClipContentType.CODE -> Icons.Filled.Code
      ClipContentType.ADDRESS -> Icons.Filled.Place
      ClipContentType.NOTE -> Icons.Filled.Notes
      ClipContentType.TEXT -> null
    }
  val label = localizedContentTypeLabel(contentType)

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier =
      Modifier
        .clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer)
        .padding(horizontal = 7.dp, vertical = 2.dp),
  ) {
    if (icon != null) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(12.dp),
      )
      Spacer(modifier = Modifier.width(3.dp))
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
  }
}
