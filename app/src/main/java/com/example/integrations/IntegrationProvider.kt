package com.example.integrations

import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.SyncProviderId

data class PulledClip(
  val content: String,
  val title: String = "",
  val categoryHint: String? = null,
  val tags: List<String> = emptyList(),
  val source: ClipSource,
  val remoteId: String,
  val remoteTimestamp: Long = System.currentTimeMillis(),
)

sealed interface ProviderResult<out T> {
  data class Success<T>(val data: T, val detail: String = "") : ProviderResult<T>

  data class Error(
    val message: String,
    val isRetryable: Boolean = false,
    val retryAfterSeconds: Long? = null,
  ) : ProviderResult<Nothing>
}

interface IntegrationProvider {
  val providerId: SyncProviderId

  suspend fun isConfigured(): Boolean

  suspend fun testConnection(): ProviderResult<String>

  suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int>

  suspend fun pullClips(): ProviderResult<List<PulledClip>>

  /**
   * Pushes a single clip and returns its concrete remote message/file ID for persistent linking.
   */
  suspend fun pushSingleClipAndReturnRemoteId(clip: ClipItem): ProviderResult<String> {
    return when (val res = pushClips(listOf(clip))) {
      is ProviderResult.Success ->
        ProviderResult.Success(
          res.detail.ifBlank { "pushed:${providerId.id}:${clip.contentHash.take(8)}" },
          res.detail,
        )
      is ProviderResult.Error -> res
    }
  }

  /**
   * Fetches available remote history for Cloud Recovery Center inspection & diff comparison.
   */
  suspend fun fetchRecoveryItems(maxItems: Int = 200): ProviderResult<List<PulledClip>> = pullClips()

  /**
   * Optionally deletes a remotely stored message/backup when user deletes a clip from cloud.
   */
  suspend fun deleteRemoteClip(remoteId: String): ProviderResult<Unit> =
    ProviderResult.Success(Unit)
}
