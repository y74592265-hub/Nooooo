package com.example.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class ClipRevision(
  val id: Long = 0L,
  val clipId: Long,
  val versionNumber: Int,
  val content: String,
  val contentHash: String,
  val diffSummary: String = "",
  val createdAt: Long = System.currentTimeMillis(),
)

enum class FieldIntent {
  URL,
  EMAIL,
  PHONE_OR_NUMBER,
  CODE_OR_MULTILINE,
  SEARCH,
  GENERAL,
}

@Immutable
data class FieldContextSignal(
  val intent: FieldIntent = FieldIntent.GENERAL,
  val isSecretField: Boolean = false,
  val noPersonalizedLearning: Boolean = false,
  val isMultiline: Boolean = false,
  val targetPackage: String? = null,
)

enum class PredictionReason {
  NEXT_IN_SEQUENCE,
  MATCHES_FIELD,
  PINNED,
  FREQUENT,
  RECENT,
}

@Immutable
data class ImeClipCandidate(
  val id: Long,
  val title: String,
  val preview: String,
  val contentType: ClipContentType,
  val categoryId: Long,
  val isPinned: Boolean,
  val isFavorite: Boolean,
  val useCount: Int,
  val lastPastedAt: Long?,
  val updatedAt: Long,
  val versionNumber: Int,
)

@Immutable
data class ClipTransitionEdge(
  val fromClipId: Long,
  val toClipId: Long,
  val weight: Float,
  val hitCount: Int,
  val lastTriggeredAt: Long,
)

@Immutable
data class PredictedClipChip(
  val candidate: ImeClipCandidate,
  val score: Float,
  val reason: PredictionReason,
)

enum class DiffLineType {
  UNCHANGED,
  ADDED,
  REMOVED,
}

@Immutable
data class DiffLine(
  val type: DiffLineType,
  val text: String,
)

@Immutable
data class LineDiffResult(
  val lines: List<DiffLine>,
  val addedCount: Int,
  val removedCount: Int,
  val wasTruncated: Boolean = false,
) {
  val summaryBadge: String
    get() = "+$addedCount -$removedCount"
}
