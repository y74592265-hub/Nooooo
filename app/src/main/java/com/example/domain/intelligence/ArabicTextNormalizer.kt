package com.example.domain.intelligence

import java.util.Locale

/**
 * Pure-Kotlin Arabic & developer-token text normalizer for high-recall instant search.
 * Normalizes:
 * - Hamza/Alef forms (أ إ آ ٱ -> ا)
 * - Taa Marbuta (ة -> ه) & Alef Maksura (ى -> ي)
 * - Strips Arabic diacritics (Tashkeel) and Tatweel (ـ)
 * - Converts Eastern Arabic-Indic digits (٠-٩) to Western digits (0-9)
 * - Splits camelCase and snake_case identifiers for programmer search
 */
object ArabicTextNormalizer {

  private val CAMEL_SPLIT_REGEX = Regex("(?<=[a-z0-9])(?=[A-Z])")

  fun normalize(input: String): String {
    if (input.isEmpty()) return ""
    val spaced = input.replace(CAMEL_SPLIT_REGEX, " ").replace('_', ' ')
    val sb = StringBuilder(spaced.length)
    for (ch in spaced) {
      when {
        ch in '\u064B'..'\u065F' || ch == '\u0670' || ch == '\u0640' -> Unit // drop Tashkeel & Tatweel
        ch == 'أ' || ch == 'إ' || ch == 'آ' || ch == 'ٱ' -> sb.append('ا')
        ch == 'ة' -> sb.append('ه')
        ch == 'ى' -> sb.append('ي')
        ch == 'ؤ' -> sb.append('و')
        ch == 'ئ' -> sb.append('ي')
        ch in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
        ch in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
        else -> sb.append(ch.lowercaseChar())
      }
    }
    return sb.toString().trim().replace(Regex("\\s+"), " ")
  }

  fun normalizedContains(haystack: String, rawQuery: String): Boolean {
    val queryNorm = normalize(rawQuery)
    if (queryNorm.isEmpty()) return true
    if (haystack.contains(rawQuery, ignoreCase = true)) return true
    val hayNorm = normalize(haystack)
    if (hayNorm.contains(queryNorm)) return true
    val tokens = queryNorm.split(' ').filter { it.isNotEmpty() }
    return tokens.size > 1 && tokens.all { hayNorm.contains(it) }
  }

  /**
   * Produces normalized search tokens suitable for FTS prefix expressions.
   */
  fun tokenizeForSearch(rawQuery: String): List<String> =
    normalize(rawQuery)
      .split(' ')
      .map { it.lowercase(Locale.ROOT).replace(Regex("""["'*():^~]"""), "").trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .take(6)
}
