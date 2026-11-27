package com.example.domain.intelligence

import com.example.core.preferences.UserSettings
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipTransitionEdge
import com.example.domain.model.FieldContextSignal
import com.example.domain.model.FieldIntent
import com.example.domain.model.ImeClipCandidate
import com.example.domain.model.PredictedClipChip
import com.example.domain.model.PredictionReason
import kotlin.math.ln

/**
 * Deterministic low-latency scoring engine ranking pre-projected non-sensitive IME clips
 * by active input field intent, next-clip transition probability, frequency, recency, and pin state.
 */
object ClipPredictionEngine {

  private const val MIN_NEXT_CLIP_HITS = 2
  private const val MIN_NEXT_CLIP_WEIGHT = 1.5f
  private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
  private val URL_REGEX = Regex("""(?i)(https?://|www\.)\S+""")
  private val PHONE_OR_NUMERIC_REGEX = Regex("""^\+?[\d\s\-().]{4,24}$""")

  fun rank(
    candidates: List<ImeClipCandidate>,
    signal: FieldContextSignal,
    lastUsedClipId: Long?,
    transitionsFromLast: List<ClipTransitionEdge>,
    settings: UserSettings,
    nowMs: Long = System.currentTimeMillis(),
    limit: Int = 10,
  ): List<PredictedClipChip> {
    if (candidates.isEmpty() || signal.isSecretField) return emptyList()

    val allowNextClip =
      settings.usageLearningEnabled && !signal.noPersonalizedLearning && lastUsedClipId != null
    val transitionByTarget =
      if (allowNextClip) {
        transitionsFromLast
          .filter {
            it.fromClipId == lastUsedClipId &&
              it.hitCount >= MIN_NEXT_CLIP_HITS &&
              it.weight >= MIN_NEXT_CLIP_WEIGHT
          }
          .associateBy { it.toClipId }
      } else {
        emptyMap()
      }

    val scored =
      candidates.map { candidate ->
        var score = 0f
        var primaryReason = PredictionReason.RECENT

        // 1. Recency score (exponential smooth drop over 7 days)
        val ageHours = ((nowMs - candidate.updatedAt).coerceAtLeast(0L) / 3_600_000.0).toFloat()
        val recencyScore = 18f / (1f + ageHours / 12f)
        score += recencyScore

        // 2. Pin boost
        if (candidate.isPinned) {
          score += 22f
          primaryReason = PredictionReason.PINNED
        }

        // 3. Usage frequency boost (bounded logarithmic growth)
        if (settings.usageLearningEnabled && candidate.useCount > 0) {
          val freqBoost = (ln(1.0 + candidate.useCount.toDouble()) * 8.0).toFloat()
          score += freqBoost
          if (!candidate.isPinned && freqBoost >= 10f) {
            primaryReason = PredictionReason.FREQUENT
          }
        }

        // 4. Field context match (Adaptive Field-Aware Keyboard)
        if (settings.adaptiveImeEnabled) {
          val matchBoost = computeFieldMatchBoost(candidate, signal.intent)
          if (matchBoost > 0f) {
            score += matchBoost
            primaryReason = PredictionReason.MATCHES_FIELD
          }
        }

        // 5. Conservative Next-Clip Predictor
        val edge = transitionByTarget[candidate.id]
        if (edge != null) {
          val nextBoost = 40f + (edge.weight.coerceAtMost(10f) * 4f)
          score += nextBoost
          primaryReason = PredictionReason.NEXT_IN_SEQUENCE
        }

        // Slight de-prioritization of the clip that was *just* pasted so user sees what comes next
        if (lastUsedClipId != null && candidate.id == lastUsedClipId && transitionsFromLast.isNotEmpty()) {
          score -= 12f
        }

        PredictedClipChip(
          candidate = candidate,
          score = score,
          reason = primaryReason,
        )
      }

    return scored
      .sortedWith(
        compareByDescending<PredictedClipChip> { it.score }
          .thenByDescending { it.candidate.isPinned }
          .thenByDescending { it.candidate.updatedAt }
      )
      .take(limit)
  }

  internal fun computeFieldMatchBoost(candidate: ImeClipCandidate, intent: FieldIntent): Float {
    val text = candidate.preview.trim()
    return when (intent) {
      FieldIntent.URL ->
        when {
          candidate.contentType == ClipContentType.LINK -> 36f
          URL_REGEX.containsMatchIn(text) -> 28f
          else -> 0f
        }
      FieldIntent.EMAIL ->
        when {
          EMAIL_REGEX.matches(text) -> 42f
          EMAIL_REGEX.containsMatchIn(text) -> 26f
          else -> 0f
        }
      FieldIntent.PHONE_OR_NUMBER ->
        when {
          PHONE_OR_NUMERIC_REGEX.matches(text) -> 40f
          text.count { it.isDigit() } >= 4 && text.length <= 42 -> 24f
          else -> 0f
        }
      FieldIntent.CODE_OR_MULTILINE ->
        when {
          candidate.contentType == ClipContentType.CODE -> 30f
          candidate.contentType == ClipContentType.NOTE || text.contains('\n') -> 16f
          else -> 0f
        }
      FieldIntent.SEARCH ->
        if (text.length <= 80 && !text.contains('\n')) 12f else 0f
      FieldIntent.GENERAL -> 0f
    }
  }
}
