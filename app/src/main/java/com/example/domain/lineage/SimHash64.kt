package com.example.domain.lineage

import java.util.Locale

/**
 * Pure-Kotlin 64-bit SimHash & normalized token Jaccard calculator supporting Arabic and Latin text.
 */
object SimHash64 {

  private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 14695981039346656037UL.toLong()
  private const val FNV_PRIME = 0x100000001b3L

  fun compute(text: String): Long {
    val tokens = tokenize(text)
    if (tokens.isEmpty()) return 0L

    val counts = IntArray(64)
    val features = buildShingles(tokens)
    for (feature in features) {
      val h = fnv1a64(feature)
      for (bit in 0 until 64) {
        if (((h ushr bit) and 1L) == 1L) {
          counts[bit] += 1
        } else {
          counts[bit] -= 1
        }
      }
    }

    var simHash = 0L
    for (bit in 0 until 64) {
      if (counts[bit] > 0) {
        simHash = simHash or (1L shl bit)
      }
    }
    return simHash
  }

  fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

  fun jaccardSimilarity(aText: String, bText: String): Double {
    val setA = tokenize(aText).toHashSet()
    val setB = tokenize(bText).toHashSet()
    if (setA.isEmpty() && setB.isEmpty()) return 1.0
    if (setA.isEmpty() || setB.isEmpty()) return 0.0
    var intersection = 0
    val smaller = if (setA.size <= setB.size) setA else setB
    val larger = if (setA.size <= setB.size) setB else setA
    for (token in smaller) {
      if (token in larger) intersection++
    }
    val union = setA.size + setB.size - intersection
    return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
  }

  internal fun tokenize(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    val out = ArrayList<String>(32)
    val sb = StringBuilder()
    for (ch in text.lowercase(Locale.ROOT)) {
      if (Character.isLetterOrDigit(ch)) {
        sb.append(ch)
      } else if (sb.isNotEmpty()) {
        out.add(sb.toString())
        sb.setLength(0)
      }
    }
    if (sb.isNotEmpty()) out.add(sb.toString())
    return out
  }

  private fun buildShingles(tokens: List<String>): List<String> {
    if (tokens.size <= 1) return tokens
    val features = ArrayList<String>(tokens.size * 2)
    features.addAll(tokens)
    for (i in 0 until tokens.size - 1) {
      features.add("${tokens[i]}_${tokens[i + 1]}")
    }
    return features
  }

  private fun fnv1a64(input: String): Long {
    var hash = FNV_OFFSET_BASIS
    for (i in input.indices) {
      hash = hash xor input[i].code.toLong()
      hash *= FNV_PRIME
    }
    return hash
  }
}
