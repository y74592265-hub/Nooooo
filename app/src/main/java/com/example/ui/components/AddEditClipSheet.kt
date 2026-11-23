package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.Category
import com.example.domain.model.CategoryDefaults
import com.example.domain.model.ClipContentType

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditClipSheet(
  categories: List<Category>,
  initialTitle: String = "",
  initialContent: String = "",
  initialCategoryId: Long = CategoryDefaults.PERSONAL_ID,
  initialTags: String = "",
  initialSensitive: Boolean = false,
  initialType: ClipContentType? = null,
  isEditing: Boolean = false,
  onPasteSystemClipboard: () -> String?,
  onDismiss: () -> Unit,
  onSave: (
    title: String,
    content: String,
    categoryId: Long,
    tags: List<String>,
    isSensitive: Boolean,
    contentType: ClipContentType?,
  ) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var title by remember(initialTitle) { mutableStateOf(initialTitle) }
  var content by remember(initialContent) { mutableStateOf(initialContent) }
  var selectedCategoryId by remember(initialCategoryId) { mutableLongStateOf(initialCategoryId) }
  var tagsText by remember(initialTags) { mutableStateOf(initialTags) }
  var isSensitive by remember(initialSensitive) { mutableStateOf(initialSensitive) }
  var selectedType by remember(initialType) { mutableStateOf(initialType) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    ProvideSafeTextToolbar {
      val focusManager = LocalFocusManager.current
      val textToolbar = LocalTextToolbar.current
      Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp)
          .padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text =
            if (isEditing) stringResource(R.string.action_edit)
            else stringResource(R.string.action_add_clip),
          style = MaterialTheme.typography.headlineSmall,
        )
        if (!isEditing) {
          TextButton(
            onClick = {
              val pasted = onPasteSystemClipboard()
              if (!pasted.isNullOrBlank()) content = pasted
            },
            modifier = Modifier.testTag("sheet_paste_button"),
          ) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.action_paste_from_clipboard))
          }
        }
      }

      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text(stringResource(R.string.label_title_optional)) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().testTag("clip_title_input"),
      )

      OutlinedTextField(
        value = content,
        onValueChange = { content = it },
        label = { Text(stringResource(R.string.label_content)) },
        minLines = 4,
        maxLines = 10,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().testTag("clip_content_input"),
      )

      Text(
        text = stringResource(R.string.label_category),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        categories.forEach { cat ->
          FilterChip(
            selected = selectedCategoryId == cat.id,
            onClick = { selectedCategoryId = cat.id },
            label = { Text(localizedCategoryName(cat)) },
            modifier = Modifier.testTag("cat_chip_${cat.id}"),
          )
        }
      }

      OutlinedTextField(
        value = tagsText,
        onValueChange = { tagsText = it },
        label = { Text(stringResource(R.string.label_tags)) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().testTag("clip_tags_input"),
      )

      Text(
        text = stringResource(R.string.label_content_type),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        ClipContentType.entries.forEach { type ->
          FilterChip(
            selected = selectedType == type,
            onClick = { selectedType = if (selectedType == type) null else type },
            label = { Text(localizedContentTypeLabel(type)) },
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f).padding(end = 12.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
          )
          Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
              text = stringResource(R.string.label_sensitive),
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              text = stringResource(R.string.label_sensitive_hint),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        Switch(
          checked = isSensitive,
          onCheckedChange = { isSensitive = it },
          modifier = Modifier.testTag("clip_sensitive_switch"),
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        OutlinedButton(
          onClick = {
            textToolbar.hide()
            focusManager.clearFocus()
            onDismiss()
          },
          modifier = Modifier.weight(1f).testTag("cancel_clip_button"),
        ) {
          Text(stringResource(R.string.action_cancel))
        }
        Button(
          onClick = {
            textToolbar.hide()
            focusManager.clearFocus()
            val tags =
              tagsText.split(",", "،").map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
            onSave(title, content, selectedCategoryId, tags, isSensitive, selectedType)
          },
          enabled = content.isNotBlank(),
          modifier = Modifier.weight(1f).testTag("save_clip_button"),
        ) {
          Text(stringResource(R.string.action_save))
        }
      }
    }
    }
  }
}
