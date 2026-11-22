package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import com.example.domain.model.ClipItem
import com.example.ui.components.EmptyStateView
import com.example.ui.components.localizedRelativeTime
import com.example.ui.theme.AppSpacing

@Composable
fun TrashScreen(
  trashedClips: List<ClipItem>,
  onRestore: (List<Long>) -> Unit,
  onDeleteForever: (List<Long>) -> Unit,
  onEmptyTrash: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var showEmptyConfirm by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = AppSpacing.maxContentWidth)) {
      if (trashedClips.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.trash_items_count, trashedClips.size),
            style = MaterialTheme.typography.titleMedium,
          )
          OutlinedButton(
            onClick = { showEmptyConfirm = true },
            colors =
              ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("empty_trash_btn"),
          ) {
            Text(stringResource(R.string.trash_empty_btn))
          }
        }
      }

      if (trashedClips.isEmpty()) {
        EmptyStateView(
          title = stringResource(R.string.empty_trash_title),
          subtitle = stringResource(R.string.empty_trash_subtitle),
          showArtwork = false,
          icon = Icons.Filled.DeleteOutline,
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(trashedClips, key = { it.id }) { clip ->
            Surface(
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surface,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
              modifier = Modifier.fillMaxWidth().testTag("trash_item_${clip.id}"),
            ) {
              Column(modifier = Modifier.padding(16.dp)) {
                if (clip.title.isNotBlank()) {
                  Text(
                    text = clip.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                  text =
                    if (clip.isSensitive) stringResource(R.string.label_sensitive_masked)
                    else clip.content,
                  style = MaterialTheme.typography.bodyMedium,
                  maxLines = 3,
                  overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text =
                    stringResource(
                      R.string.trash_deleted_at,
                      localizedRelativeTime(clip.trashedAt ?: clip.updatedAt),
                    ),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.End,
                ) {
                  TextButton(
                    onClick = { onRestore(listOf(clip.id)) },
                    modifier = Modifier.testTag("restore_clip_${clip.id}"),
                  ) {
                    Icon(Icons.Filled.RestoreFromTrash, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.action_restore))
                  }
                  TextButton(
                    onClick = { onDeleteForever(listOf(clip.id)) },
                    modifier = Modifier.testTag("delete_forever_${clip.id}"),
                  ) {
                    Icon(
                      Icons.Filled.DeleteForever,
                      null,
                      tint = MaterialTheme.colorScheme.error,
                      modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                      stringResource(R.string.action_delete_forever),
                      color = MaterialTheme.colorScheme.error,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  if (showEmptyConfirm) {
    AlertDialog(
      onDismissRequest = { showEmptyConfirm = false },
      title = { Text(stringResource(R.string.trash_empty_confirm_title)) },
      text = { Text(stringResource(R.string.trash_empty_confirm_msg, trashedClips.size)) },
      confirmButton = {
        TextButton(
          onClick = {
            showEmptyConfirm = false
            onEmptyTrash()
          }
        ) {
          Text(
            stringResource(R.string.action_delete_forever),
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
      dismissButton = {
        TextButton(onClick = { showEmptyConfirm = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}
