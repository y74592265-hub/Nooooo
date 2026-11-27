package com.example.domain.intelligence

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

enum class ExtractedEntityKind {
  URL,
  EMAIL,
  PHONE,
}

data class ExtractedEntity(
  val kind: ExtractedEntityKind,
  val value: String,
)

/**
 * Zero-dependency offline developer & productivity micro-tools executed locally inside ClipDetails:
 * - Clean tracking parameters from URLs (utm_*, fbclid, gclid, igshid, mc_cid, etc.)
 * - Pretty-print or minify JSON payloads
 * - Safe JWT & Base64 UTF-8 preview decoder
 * - Convert Arabic-Indic <-> Western digits
 * - Extract clickable URLs, Emails, and Phone numbers inside long clips
 */
object ClipSmartTools {

  private val TRACKING_PARAM_PREFIXES = listOf("utm_", "mc_", "yclid", "_hs")
  private val TRACKING_EXACT_PARAMS =
    setOf("fbclid", "gclid", "dclid", "msclkid", "igshid", "si", "ref_src", "spm")

  private val URL_EXTRACT_REGEX = Regex("""(?i)\bhttps?://[^\s<>"'\]\)]+""")
  private val EMAIL_EXTRACT_REGEX = Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""")
  private val PHONE_EXTRACT_REGEX = Regex("""(?<!\d)(?:\+?\d[\d\s\-()]{6,17}\d)(?!\d)""")
  private val BASE64_REGEX = Regex("""^[A-Za-z0-9+/\-_]{8,}={0,2}$""")

  fun stripTrackingParamsIfChanged(text: String): String? {
    val trimmed = text.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
      return null
    }
    if (trimmed.contains(' ') || trimmed.contains('\n')) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val rawQuery = uri.rawQuery ?: return null
    if (rawQuery.isBlank()) return null

    val keptParams =
      rawQuery.split('&').filter { pair ->
        val key = pair.substringBefore('=').lowercase()
        val isTracking =
          key in TRACKING_EXACT_PARAMS || TRACKING_PARAM_PREFIXES.any { key.startsWith(it) }
        !isTracking && pair.isNotBlank()
      }

    val rebuilt =
      URI(
          uri.scheme,
          uri.rawUserInfo,
          uri.host,
          uri.port,
          uri.rawPath,
          if (keptParams.isEmpty()) null else keptParams.joinToString("&"),
          uri.rawFragment,
        )
        .toASCIIString()
    return rebuilt.takeIf { it != trimmed }
  }

  fun prettyJsonOrNull(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.length < 2 || trimmed.length > 64_000) return null
    return runCatching {
      when {
        trimmed.startsWith("{") && trimmed.endsWith("}") -> JSONObject(trimmed).toString(2)
        trimmed.startsWith("[") && trimmed.endsWith("]") -> JSONArray(trimmed).toString(2)
        else -> null
      }
    }
      .getOrNull()
      ?.takeIf { it != trimmed }
  }

  fun minifyJsonOrNull(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.length < 2 || trimmed.length > 64_000 || !trimmed.contains('\n')) return null
    return runCatching {
      when {
        trimmed.startsWith("{") && trimmed.endsWith("}") -> JSONObject(trimmed).toString()
        trimmed.startsWith("[") && trimmed.endsWith("]") -> JSONArray(trimmed).toString()
        else -> null
      }
    }
      .getOrNull()
      ?.takeIf { it != trimmed }
  }

  fun hasArabicIndicDigits(text: String): Boolean =
    text.any { it in '\u0660'..'\u0669' || it in '\u06F0'..'\u06F9' }

  fun toWesternDigits(text: String): String {
    val sb = StringBuilder(text.length)
    for (ch in text) {
      when (ch) {
        in '\u0660'..'\u0669' -> sb.append(('0'.code + (ch.code - '\u0660'.code)).toChar())
        in '\u06F0'..'\u06F9' -> sb.append(('0'.code + (ch.code - '\u06F0'.code)).toChar())
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }

  fun decodeJwtOrBase64Preview(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.contains(' ') || trimmed.contains('\n')) return null
    val jwtParts = trimmed.split('.')
    if (jwtParts.size == 3 && jwtParts[0].length >= 4 && jwtParts[1].length >= 4) {
      val header = decodeBase64Utf8(jwtParts[0])
      val payload = decodeBase64Utf8(jwtParts[1])
      if (header != null && payload != null && payload.trimStart().startsWith("{")) {
        val prettyHeader = prettyJsonOrNull(header) ?: header
        val prettyPayload = prettyJsonOrNull(payload) ?: payload
        return "HEADER:\n$prettyHeader\n\nPAYLOAD:\n$prettyPayload"
      }
    }
    if (BASE64_REGEX.matches(trimmed) && trimmed.length <= 8_000) {
      val decoded = decodeBase64Utf8(trimmed) ?: return null
      if (decoded.any { it.code < 9 || (it.code in 14..31) }) return null
      return prettyJsonOrNull(decoded) ?: decoded
    }
    return null
  }

  fun extractActionableEntities(text: String, maxPerKind: Int = 4): List<ExtractedEntity> {
    if (text.isBlank()) return emptyList()
    val sample = text.take(12_000)
    val result = ArrayList<ExtractedEntity>()
    val seen = HashSet<String>()

    URL_EXTRACT_REGEX.findAll(sample).take(maxPerKind).forEach { match ->
      val v = match.value.trimEnd('.', ',', ';', ')', ']')
      if (seen.add("U:$v")) result.add(ExtractedEntity(ExtractedEntityKind.URL, v))
    }
    EMAIL_EXTRACT_REGEX.findAll(sample).take(maxPerKind).forEach { match ->
      val v = match.value
      if (seen.add("E:$v")) result.add(ExtractedEntity(ExtractedEntityKind.EMAIL, v))
    }
    PHONE_EXTRACT_REGEX.findAll(sample).take(maxPerKind).forEach { match ->
      val v = match.value.trim()
      if (v.count { it.isDigit() } >= 7 && seen.add("P:$v")) {
        result.add(ExtractedEntity(ExtractedEntityKind.PHONE, v))
      }
    }
    return result
  }

  private fun decodeBase64Utf8(segment: String): String? {
    return runCatching {
      val normalized = segment.replace('-', '+').replace('_', '/')
      val padLen = (4 - normalized.length % 4) % 4
      val padded = normalized + "=".repeat(padLen)
      val bytes = Base64.decode(padded, Base64.DEFAULT)
      if (bytes.isEmpty()) return null
      val decoded = String(bytes, Charsets.UTF_8)
      if (decoded.contains('\uFFFD')) null else decoded
    }
      .getOrNull()
  }
}
