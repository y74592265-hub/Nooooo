package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.intelligence.ClipSmartTools
import com.example.domain.intelligence.ClipTemplateEngine
import com.example.domain.lineage.LineDiffEngine
import com.example.domain.model.Category
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipRevision
import com.example.domain.model.DiffLineType
import com.example.domain.model.LineDiffResult
import com.example.domain.model.SyncProviderId
import com.example.ui.components.AddEditClipSheet
import com.example.ui.components.ContentTypeBadge
import com.example.ui.components.LtrText
import com.example.ui.components.VaultSectionCard
import com.example.ui.components.localizedCategoryName
import com.example.ui.theme.AppSpacing
import com.example.ui.theme.FavoriteGold
import com.example.ui.theme.JetBrainsMonoFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
  revisions: List<ClipRevision> = emptyList(),
  onRestoreRevision: (Long) -> Unit = {},
  onSplitRevision: (Long) -> Unit = {},
  onApplyContentTransform: (String) -> Unit = {},
  onCopySnippetText: (String) -> Unit = {},
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
  val textToolbar = LocalTextToolbar.current
  val focusManager = LocalFocusManager.current
  var showEditSheet by remember { mutableStateOf(false) }
  var revealSensitive by remember(clip.id, clip.isSensitive) { mutableStateOf(!clip.isSensitive) }
  var showDecodedPreview by remember(clip.id, clip.content) { mutableStateOf(false) }
  var selectedRevisionId by remember(clip.id) { mutableStateOf<Long?>(null) }

  LaunchedEffect(revisions) {
    if (revisions.isNotEmpty() && revisions.none { it.id == selectedRevisionId }) {
      selectedRevisionId = revisions.first().id
    }
  }

  val selectedRevision = revisions.find { it.id == selectedRevisionId }
  val diffResult by
    produceState<LineDiffResult?>(
      initialValue = null,
      key1 = clip.content,
      key2 = selectedRevision?.id,
    ) {
      value =
        if (selectedRevision != null && !clip.isSensitive) {
          withContext(Dispatchers.Default) {
            LineDiffEngine.diff(previousText = selectedRevision.content, currentText = clip.content)
          }
        } else {
          null
        }
    }

  val category = categories.find { it.id == clip.categoryId }
  val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
  val shareTitle = stringResource(R.string.details_share_title)

  val cleanedUrl = remember(clip.content, clip.isSensitive) {
    if (clip.isSensitive) null else ClipSmartTools.stripTrackingParamsIfChanged(clip.content)
  }
  val prettyJson = remember(clip.content, clip.isSensitive) {
    if (clip.isSensitive) null else ClipSmartTools.prettyJsonOrNull(clip.content)
  }
  val minifiedJson = remember(clip.content, clip.isSensitive) {
    if (clip.isSensitive) null else ClipSmartTools.minifyJsonOrNull(clip.content)
  }
  val hasArabicDigits = remember(clip.content, clip.isSensitive) {
    !clip.isSensitive && ClipSmartTools.hasArabicIndicDigits(clip.content)
  }
  val decodedPreview = remember(clip.content, clip.isSensitive) {
    if (clip.isSensitive) null else ClipSmartTools.decodeJwtOrBase64Preview(clip.content)
  }
  val extractedEntities = remember(clip.content, clip.isSensitive) {
    if (clip.isSensitive) emptyList() else ClipSmartTools.extractActionableEntities(clip.content)
  }
  val hasTemplateTokens = remember(clip.content, clip.isSensitive) {
    !clip.isSensitive && ClipTemplateEngine.hasTemplateTokens(clip.content)
  }
  val templatePreviewText = remember(clip.content, hasTemplateTokens) {
    if (hasTemplateTokens) ClipTemplateEngine.expand(clip.content) else null
  }

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
          if (clip.versionNumber > 1) {
            AssistChip(
              onClick = {},
              label = { Text(stringResource(R.string.details_version_badge, clip.versionNumber)) },
            )
          }
          AssistChip(
            onClick = {
              textToolbar.hide()
              focusManager.clearFocus()
              showEditSheet = true
            },
            label = { Text(localizedCategoryName(category)) },
          )
          if (clip.isSensitive) {
            AssistChip(
              onClick = {
                textToolbar.hide()
                revealSensitive = !revealSensitive
              },
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

      // Smart Zero-Dependency Instant Tools
      val hasSmartTransforms =
        cleanedUrl != null || prettyJson != null || minifiedJson != null || hasArabicDigits || decodedPreview != null
      if (!clip.isSensitive && (hasTemplateTokens || hasSmartTransforms || extractedEntities.size > 1 || (extractedEntities.size == 1 && extractedEntities.first().value != clip.content.trim()))) {
        VaultSectionCard(modifier = Modifier.testTag("details_smart_tools_card")) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.AutoFixHigh,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = stringResource(R.string.details_smart_tools_title),
              style = MaterialTheme.typography.titleMedium,
            )
          }

          if (hasSmartTransforms) {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              if (cleanedUrl != null) {
                AssistChip(
                  onClick = { onApplyContentTransform(cleanedUrl) },
                  label = { Text(stringResource(R.string.tool_clean_url)) },
                  modifier = Modifier.testTag("tool_clean_url_chip"),
                )
              }
              if (prettyJson != null) {
                AssistChip(
                  onClick = { onApplyContentTransform(prettyJson) },
                  label = { Text(stringResource(R.string.tool_format_json)) },
                  modifier = Modifier.testTag("tool_format_json_chip"),
                )
              }
              if (minifiedJson != null) {
                AssistChip(
                  onClick = { onApplyContentTransform(minifiedJson) },
                  label = { Text(stringResource(R.string.tool_minify_json)) },
                  modifier = Modifier.testTag("tool_minify_json_chip"),
                )
              }
              if (hasArabicDigits) {
                AssistChip(
                  onClick = { onApplyContentTransform(ClipSmartTools.toWesternDigits(clip.content)) },
                  label = { Text(stringResource(R.string.tool_western_digits)) },
                  modifier = Modifier.testTag("tool_digits_chip"),
                )
              }
              if (decodedPreview != null) {
                AssistChip(
                  onClick = { showDecodedPreview = !showDecodedPreview },
                  label = { Text(stringResource(R.string.tool_decode_jwt_b64)) },
                  modifier = Modifier.testTag("tool_decode_chip"),
                )
              }
            }
          }

          if (showDecodedPreview && decodedPreview != null) {
            Surface(
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              modifier = Modifier.fillMaxWidth().testTag("tool_decoded_preview"),
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                  text = stringResource(R.string.tool_decoded_preview_title),
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                )
                SelectionContainer {
                  Text(
                    text = decodedPreview,
                    style =
                      MaterialTheme.typography.bodySmall.copy(
                        fontFamily = JetBrainsMonoFamily,
                        textDirection = TextDirection.Ltr,
                      ),
                  )
                }
              }
            }
          }

          if (templatePreviewText != null) {
            Surface(
              shape = MaterialTheme.shapes.small,
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
              modifier = Modifier.fillMaxWidth().testTag("tool_template_preview"),
            ) {
              Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = stringResource(R.string.template_preview_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  AssistChip(
                    onClick = { onCopySnippetText(ClipTemplateEngine.expand(clip.content)) },
                    label = { Text(stringResource(R.string.template_copy_filled)) },
                    modifier = Modifier.testTag("tool_copy_template_chip"),
                  )
                }
                Text(
                  text = templatePreviewText,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }

          if (extractedEntities.isNotEmpty() && (extractedEntities.size > 1 || extractedEntities.first().value != clip.content.trim())) {
            Text(
              text = stringResource(R.string.tool_extracted_entities_title),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              extractedEntities.forEachIndexed { idx, entity ->
                AssistChip(
                  onClick = { onCopySnippetText(entity.value) },
                  leadingIcon = {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                  },
                  label = {
                    Text(
                      text = entity.value.take(36),
                      maxLines = 1,
                    )
                  },
                  modifier = Modifier.testTag("extracted_entity_$idx"),
                )
              }
            }
          }
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
          onClick = {
            textToolbar.hide()
            onCopy(clip)
          },
          modifier = Modifier.weight(1f).testTag("details_copy_btn"),
        ) {
          Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(stringResource(R.string.action_copy))
        }
        OutlinedButton(
          onClick = {
            textToolbar.hide()
            focusManager.clearFocus()
            showEditSheet = true
          },
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
            textToolbar.hide()
            focusManager.clearFocus()
            val sendIntent =
              Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, clip.content)
              }
            val chooser = Intent.createChooser(sendIntent, shareTitle)
            runCatching { startActivitySafely(context, chooser) }
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
              runCatching { startActivitySafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
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

      // Smart Clipboard Lineage & Diff Section
      if (!clip.isSensitive && revisions.isNotEmpty()) {
        VaultSectionCard(modifier = Modifier.testTag("details_lineage_card")) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
              Text(
                text = stringResource(R.string.details_lineage_title, revisions.size + 1),
                style = MaterialTheme.typography.titleMedium,
              )
            }
            diffResult?.let { diff ->
              Text(
                text = diff.summaryBadge,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.primary,
              )
            }
          }
          Text(
            text = stringResource(R.string.details_lineage_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            revisions.forEach { rev ->
              FilterChip(
                selected = selectedRevision?.id == rev.id,
                onClick = { selectedRevisionId = rev.id },
                label = { Text("v${rev.versionNumber} (${rev.diffSummary.ifBlank { "~" }})") },
                modifier = Modifier.testTag("revision_chip_${rev.id}"),
              )
            }
          }

          diffResult?.let { diff ->
            Column(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(10.dp))
                  .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                  .padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              diff.lines.take(80).forEach { line ->
                val (prefix, bgColor, textColor) =
                  when (line.type) {
                    DiffLineType.ADDED ->
                      Triple(
                        "+ ",
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.onPrimaryContainer,
                      )
                    DiffLineType.REMOVED ->
                      Triple(
                        "- ",
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.onErrorContainer,
                      )
                    DiffLineType.UNCHANGED ->
                      Triple(
                        "  ",
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                  }
                Text(
                  text = "$prefix${line.text}",
                  style =
                    MaterialTheme.typography.bodySmall.copy(
                      fontFamily = JetBrainsMonoFamily,
                      textDirection = TextDirection.Ltr,
                    ),
                  color = textColor,
                  modifier =
                    Modifier
                      .fillMaxWidth()
                      .background(bgColor)
                      .padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
              if (diff.wasTruncated) {
                Text(
                  text = stringResource(R.string.details_lineage_truncated),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          selectedRevision?.let { rev ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedButton(
                onClick = { onRestoreRevision(rev.id) },
                modifier = Modifier.weight(1f).testTag("restore_revision_btn"),
              ) {
                Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.details_lineage_restore_btn))
              }
              OutlinedButton(
                onClick = { onSplitRevision(rev.id) },
                modifier = Modifier.weight(1f).testTag("split_revision_btn"),
              ) {
                Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.details_lineage_split_btn))
              }
            }
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
        onClick = {
          textToolbar.hide()
          focusManager.clearFocus()
          onDelete(clip.id)
        },
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

private fun startActivitySafely(context: Context, intent: Intent) {
  var cur: Context? = context
  while (cur is ContextWrapper) {
    if (cur is Activity) {
      cur.startActivity(intent)
      return
    }
    cur = cur.baseContext
  }
  intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  context.startActivity(intent)
}
