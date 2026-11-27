package com.example.domain.lineage

import com.example.domain.model.DiffLine
import com.example.domain.model.DiffLineType
import com.example.domain.model.LineDiffResult

/**
 * Bounded line-level diff calculator using LCS dynamic programming.
 * Strictly capped to prevent UI/RAM spikes on huge pasted documents.
 */
object LineDiffEngine {

  const val MAX_DIFF_LINES = 400
  const val MAX_DIFF_CHARS = 24_000

  fun diff(previousText: String, currentText: String): LineDiffResult {
    val prevBounded = previousText.take(MAX_DIFF_CHARS)
    val currBounded = currentText.take(MAX_DIFF_CHARS)
    val prevLinesRaw = prevBounded.lines()
    val currLinesRaw = currBounded.lines()

    val truncated =
      previousText.length > MAX_DIFF_CHARS ||
        currentText.length > MAX_DIFF_CHARS ||
        prevLinesRaw.size > MAX_DIFF_LINES ||
        currLinesRaw.size > MAX_DIFF_LINES

    val a = prevLinesRaw.take(MAX_DIFF_LINES)
    val b = currLinesRaw.take(MAX_DIFF_LINES)
    val m = a.size
    val n = b.size

    // Guard DP table size cleanly (max 401 * 401 ints ~ 640KB)
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
      for (j in n - 1 downTo 0) {
        dp[i][j] =
          if (a[i] == b[j]) {
            1 + dp[i + 1][j + 1]
          } else {
            maxOf(dp[i + 1][j], dp[i][j + 1])
          }
      }
    }

    val diffLines = ArrayList<DiffLine>(m + n)
    var added = 0
    var removed = 0
    var i = 0
    var j = 0
    while (i < m && j < n) {
      when {
        a[i] == b[j] -> {
          diffLines.add(DiffLine(DiffLineType.UNCHANGED, a[i]))
          i++
          j++
        }
        dp[i + 1][j] >= dp[i][j + 1] -> {
          diffLines.add(DiffLine(DiffLineType.REMOVED, a[i]))
          removed++
          i++
        }
        else -> {
          diffLines.add(DiffLine(DiffLineType.ADDED, b[j]))
          added++
          j++
        }
      }
    }
    while (i < m) {
      diffLines.add(DiffLine(DiffLineType.REMOVED, a[i]))
      removed++
      i++
    }
    while (j < n) {
      diffLines.add(DiffLine(DiffLineType.ADDED, b[j]))
      added++
      j++
    }

    return LineDiffResult(
      lines = diffLines,
      addedCount = added,
      removedCount = removed,
      wasTruncated = truncated,
    )
  }
}
