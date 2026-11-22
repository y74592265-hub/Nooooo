package com.example.core.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import com.example.domain.model.ClipContentType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Policy-compliant Android ClipboardManager wrapper.
 * - Reads system clipboard only when app is in foreground with window focus (Android 10+ rule).
 * - Flags sensitive copied clips with EXTRA_IS_SENSITIVE (Android 13+ preview masking).
 * - Auto-classifies text into LINK, CODE, ADDRESS, NOTE, or TEXT.
 */
@Singleton
class SystemClipboardHelper @Inject constructor(@ApplicationContext private val context: Context) {

  private val clipboardManager: ClipboardManager?
    get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

  fun copyToClipboard(label: String, text: String, isSensitive: Boolean = false) {
    val cm = clipboardManager ?: return
    val clip = ClipData.newPlainText(label.ifBlank { "Smart Clipboard" }, text)
    if (isSensitive) {
      clip.description.extras =
        PersistableBundle().apply {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
          } else {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
          }
        }
    }
    cm.setPrimaryClip(clip)
  }

  fun readCurrentClipboardText(): String? {
    val cm = clipboardManager ?: return null
    if (!cm.hasPrimaryClip()) return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
    return text?.takeIf { it.isNotEmpty() }
  }

  fun clearSystemClipboard() {
    val cm = clipboardManager ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      cm.clearPrimaryClip()
    } else {
      cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
  }

  fun classifyContent(content: String): ClipContentType {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return ClipContentType.TEXT

    val urlPattern = Regex("""^(https?://|www\.)\S+$""", RegexOption.IGNORE_CASE)
    if (urlPattern.matches(trimmed) || trimmed.lines().size == 1 && trimmed.contains("https://")) {
      return ClipContentType.LINK
    }

    val codeIndicators =
      listOf(
        "fun ",
        "class ",
        "val ",
        "var ",
        "import ",
        "package ",
        "def ",
        "function ",
        "const ",
        "let ",
        "SELECT ",
        "INSERT ",
        "UPDATE ",
        "DELETE ",
        "CREATE TABLE",
        "=>",
        "{\n",
        "}\n",
        "#!/",
        "```",
        "</",
      )
    if (
      codeIndicators.any { trimmed.contains(it) } ||
        (trimmed.contains("{") && trimmed.contains("}") && trimmed.contains(";"))
    ) {
      return ClipContentType.CODE
    }

    val addressHints =
      Regex(
        """\b(\d{1,5}\s+\w+\s+(St|Street|Ave|Avenue|Rd|Road|Blvd|Drive|Dr|Lane|Ln|Way)|P\.O\.\s*Box|\bشارع\b|\bحي\b|\bطريق\b)\b""",
        RegexOption.IGNORE_CASE,
      )
    if (addressHints.containsMatchIn(trimmed)) {
      return ClipContentType.ADDRESS
    }

    if (trimmed.length > 220 || trimmed.lines().size >= 4) {
      return ClipContentType.NOTE
    }

    return ClipContentType.TEXT
  }
}
