package com.example.domain.intelligence

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Zero-dependency deterministic snippet template engine.
 * Expands lightweight placeholders on non-sensitive clips when copied or pasted:
 * - {{date}} -> yyyy-MM-dd
 * - {{time}} -> HH:mm
 * - {{day}}  -> localized weekday name
 * - {{uuid}} -> random RFC-4122 UUID
 */
object ClipTemplateEngine {

  const val TOKEN_DATE = "{{date}}"
  const val TOKEN_TIME = "{{time}}"
  const val TOKEN_DAY = "{{day}}"
  const val TOKEN_UUID = "{{uuid}}"

  val AVAILABLE_TOKENS: List<String> = listOf(TOKEN_DATE, TOKEN_TIME, TOKEN_DAY, TOKEN_UUID)

  fun hasTemplateTokens(text: String): Boolean {
    if (!text.contains("{{")) return false
    return AVAILABLE_TOKENS.any { text.contains(it, ignoreCase = true) }
  }

  fun expand(
    text: String,
    nowMillis: Long = System.currentTimeMillis(),
    locale: Locale = Locale.getDefault(),
    uuidProvider: () -> String = { UUID.randomUUID().toString() },
  ): String {
    if (!hasTemplateTokens(text)) return text
    val dateObj = Date(nowMillis)
    var result = text

    if (result.contains(TOKEN_DATE, ignoreCase = true)) {
      val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dateObj)
      result = replaceIgnoreCase(result, TOKEN_DATE, dateStr)
    }
    if (result.contains(TOKEN_TIME, ignoreCase = true)) {
      val timeStr = SimpleDateFormat("HH:mm", Locale.US).format(dateObj)
      result = replaceIgnoreCase(result, TOKEN_TIME, timeStr)
    }
    if (result.contains(TOKEN_DAY, ignoreCase = true)) {
      val dayStr = SimpleDateFormat("EEEE", locale).format(dateObj)
      result = replaceIgnoreCase(result, TOKEN_DAY, dayStr)
    }
    if (result.contains(TOKEN_UUID, ignoreCase = true)) {
      result = replaceIgnoreCase(result, TOKEN_UUID) { uuidProvider() }
    }
    return result
  }

  private fun replaceIgnoreCase(input: String, token: String, replacement: String): String =
    replaceIgnoreCase(input, token) { replacement }

  private inline fun replaceIgnoreCase(
    input: String,
    token: String,
    replacementProvider: () -> String,
  ): String {
    val sb = StringBuilder(input.length)
    var cursor = 0
    while (cursor < input.length) {
      val idx = input.indexOf(token, startIndex = cursor, ignoreCase = true)
      if (idx < 0) {
        sb.append(input, cursor, input.length)
        break
      }
      sb.append(input, cursor, idx)
      sb.append(replacementProvider())
      cursor = idx + token.length
    }
    return sb.toString()
  }
}
