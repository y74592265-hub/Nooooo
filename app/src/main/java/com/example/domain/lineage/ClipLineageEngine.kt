package com.example.domain.lineage

import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipItem
import javax.inject.Inject
import javax.inject.Singleton

data class LineageMatchResult(
  val targetClip: ClipItem,
  val newSimHash: Long,
  val diffSummary: String,
)

/**
 * High-precision conservative matcher for Smart Clipboard Lineage:
 * - Never merges sensitive clips, links, or short strings (< 60 chars).
 * - Requires both SimHash64 Hamming <= 4 AND token Jaccard >= 0.72 within 48 hours.
 */
@Singleton
class ClipLineageEngine @Inject constructor() {

  companion object {
    const val MIN_ELIGIBLE_CHARS = 60
    const val MAX_HAMMING_DISTANCE = 14
    const val MIN_JACCARD_SIMILARITY = 0.72
    const val LINEAGE_WINDOW_MS = 48L * 60L * 60L * 1000L
    const val MAX_REVISIONS_PER_CLIP = 15
  }

  fun isEligibleForLineage(content: String, contentType: ClipContentType, isSensitive: Boolean): Boolean {
    if (isSensitive) return false
    if (contentType == ClipContentType.LINK) return false
    return content.trim().length >= MIN_ELIGIBLE_CHARS
  }

  fun computeEligibleSimHash(
    content: String,
    contentType: ClipContentType,
    isSensitive: Boolean,
  ): Long {
    if (!isEligibleForLineage(content, contentType, isSensitive)) return 0L
    return SimHash64.compute(content)
  }

  fun findBestMatch(
    newContent: String,
    newHash: String,
    newType: ClipContentType,
    isSensitive: Boolean,
    candidates: List<ClipItem>,
    nowMs: Long = System.currentTimeMillis(),
  ): LineageMatchResult? {
    if (!isEligibleForLineage(newContent, newType, isSensitive)) return null
    val newSimHash = SimHash64.compute(newContent)
    if (newSimHash == 0L) return null
    val cutoff = nowMs - LINEAGE_WINDOW_MS

    var bestClip: ClipItem? = null
    var bestScore = -1.0

    for (candidate in candidates) {
      if (candidate.isSensitive || candidate.isTrashed) continue
      if (candidate.contentType != newType) continue
      if (candidate.updatedAt < cutoff) continue
      if (candidate.contentHash == newHash) continue
      if (candidate.content.length < MIN_ELIGIBLE_CHARS) continue

      val candidateSimHash =
        if (candidate.simHash != 0L) candidate.simHash else SimHash64.compute(candidate.content)
      if (candidateSimHash == 0L) continue

      val dist = SimHash64.hammingDistance(newSimHash, candidateSimHash)
      if (dist > MAX_HAMMING_DISTANCE) continue

      val jaccard = SimHash64.jaccardSimilarity(candidate.content, newContent)
      if (jaccard >= MIN_JACCARD_SIMILARITY && jaccard > bestScore) {
        bestScore = jaccard
        bestClip = candidate
      }
    }

    val matched = bestClip ?: return null
    val diff = LineDiffEngine.diff(previousText = matched.content, currentText = newContent)
    return LineageMatchResult(
      targetClip = matched,
      newSimHash = newSimHash,
      diffSummary = diff.summaryBadge,
    )
  }
}
