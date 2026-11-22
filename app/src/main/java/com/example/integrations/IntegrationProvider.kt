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
)

sealed interface ProviderResult<out T> {
  data class Success<T>(val data: T, val detail: String = "") : ProviderResult<T>

  data class Error(val message: String, val isRetryable: Boolean = false) : ProviderResult<Nothing>
}

interface IntegrationProvider {
  val providerId: SyncProviderId

  suspend fun isConfigured(): Boolean

  suspend fun testConnection(): ProviderResult<String>

  suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int>

  suspend fun pullClips(): ProviderResult<List<PulledClip>>
}
