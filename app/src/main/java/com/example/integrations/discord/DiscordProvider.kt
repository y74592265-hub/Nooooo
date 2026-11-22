package com.example.integrations.discord

import com.example.core.preferences.AppPreferencesRepository
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.SyncProviderId
import com.example.domain.repository.CategoryRepository
import com.example.integrations.IntegrationProvider
import com.example.integrations.ProviderResult
import com.example.integrations.PulledClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Discord Integration adhering to official Discord API boundaries:
 * - Outgoing clips use an explicit Webhook URL (or authorized Bot token + channelId).
 * - Incoming channel messages strictly require an authorized Bot Token + Channel ID
 *   (Discord Webhooks cannot read messages).
 */
@Singleton
class DiscordProvider
@Inject
constructor(
  private val api: DiscordApiService,
  private val prefs: AppPreferencesRepository,
  private val categoryRepository: CategoryRepository,
) : IntegrationProvider {

  override val providerId: SyncProviderId = SyncProviderId.DISCORD

  override suspend fun isConfigured(): Boolean {
    val cfg = prefs.getDiscordConfigOnce()
    val hasOutgoing =
      isValidWebhookUrl(cfg.webhookUrl) || (cfg.botToken.isNotBlank() && cfg.channelId.isNotBlank())
    return cfg.enabled && hasOutgoing
  }

  override suspend fun testConnection(): ProviderResult<String> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getDiscordConfigOnce()
      val webhook = cfg.webhookUrl.trim()
      val botToken = cfg.botToken.trim()
      val channelId = cfg.channelId.trim()

      if (webhook.isEmpty() && (botToken.isEmpty() || channelId.isEmpty())) {
        return@withContext ProviderResult.Error(
          "Provide a Discord Webhook URL for sending and/or Bot Token + Channel ID for receiving."
        )
      }

      val statuses = mutableListOf<String>()
      try {
        if (webhook.isNotEmpty()) {
          if (!isValidWebhookUrl(webhook)) {
            return@withContext ProviderResult.Error("Invalid Discord Webhook URL format.")
          }
          val resp = api.getWebhookInfo(webhook)
          if (!resp.isSuccessful) {
            return@withContext ProviderResult.Error("Webhook check failed (HTTP ${resp.code()}).")
          }
          statuses.add("Webhook ready")
        }

        if (botToken.isNotEmpty() && channelId.isNotEmpty()) {
          val chResp = api.getChannel("Bot $botToken", channelId)
          if (!chResp.isSuccessful) {
            return@withContext ProviderResult.Error(
              "Bot channel check failed (HTTP ${chResp.code()}). Verify Bot Token & permissions."
            )
          }
          val chName = chResp.body()?.name ?: channelId
          statuses.add("Bot linked to #$chName")
        }

        val summary = statuses.joinToString(" • ")
        prefs.saveDiscordConfig(cfg.copy(lastStatus = summary))
        ProviderResult.Success(summary)
      } catch (e: IOException) {
        ProviderResult.Error("Network error reaching Discord: ${e.localizedMessage}", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord test failed: ${e.localizedMessage}")
      }
    }

  override suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getDiscordConfigOnce()
      if (!cfg.enabled) return@withContext ProviderResult.Error("Discord integration is disabled.")
      val webhook = cfg.webhookUrl.trim()
      val botToken = cfg.botToken.trim()
      val channelId = cfg.channelId.trim()

      if (!isValidWebhookUrl(webhook) && (botToken.isEmpty() || channelId.isEmpty())) {
        return@withContext ProviderResult.Error("No valid Discord outgoing Webhook or Bot channel configured.")
      }

      val categories = categoryRepository.getAllCategoriesOnce().associateBy { it.id }
      var sentCount = 0
      try {
        val exportable = clips.filter { !it.isSensitive && !it.isTrashed }
        for (clip in exportable) {
          val catName = categories[clip.categoryId]?.name ?: "Personal"
          val messageText = buildDiscordMessage(clip, catName)
          for (chunk in messageText.chunked(1900)) {
            val code =
              if (isValidWebhookUrl(webhook)) {
                api.executeWebhook(webhook, DiscordWebhookPayload(content = chunk)).code()
              } else {
                api
                  .sendChannelMessage(
                    "Bot $botToken",
                    channelId,
                    DiscordMessageRequest(content = chunk),
                  )
                  .code()
              }
            if (code !in 200..299) {
              return@withContext ProviderResult.Error(
                "Discord send failed (HTTP $code)",
                isRetryable = code == 429 || code >= 500,
              )
            }
          }
          sentCount++
        }
        prefs.saveDiscordConfig(
          cfg.copy(lastSyncAt = System.currentTimeMillis(), lastStatus = "Pushed $sentCount clips")
        )
        ProviderResult.Success(sentCount)
      } catch (e: IOException) {
        ProviderResult.Error("Network error sending to Discord", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord push failed: ${e.localizedMessage}")
      }
    }

  override suspend fun pullClips(): ProviderResult<List<PulledClip>> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getDiscordConfigOnce()
      if (!cfg.enabled) return@withContext ProviderResult.Error("Discord integration is disabled.")
      val botToken = cfg.botToken.trim()
      val channelId = cfg.channelId.trim()
      // Webhooks cannot read messages — return empty cleanly if only webhook is configured
      if (botToken.isEmpty() || channelId.isEmpty()) {
        return@withContext ProviderResult.Success(
          emptyList(),
          detail = "Bot Token + Channel ID required for incoming messages",
        )
      }
      try {
        val resp =
          api.getChannelMessages(
            botAuthorization = "Bot $botToken",
            channelId = channelId,
            afterMessageId = cfg.lastMessageId,
            limit = 25,
          )
        if (!resp.isSuccessful) {
          val code = resp.code()
          return@withContext ProviderResult.Error(
            "Discord fetch failed (HTTP $code)",
            isRetryable = code == 429 || code >= 500,
          )
        }
        val messages = resp.body().orEmpty().sortedBy { it.id.toLongOrNull() ?: 0L }
        val pulled =
          messages
            .filter { it.content.isNotBlank() && it.author?.bot != true }
            .map { msg ->
              PulledClip(
                content = msg.content.trim(),
                source = ClipSource.DISCORD,
                remoteId = "discord:${channelId}:${msg.id}",
              )
            }
        val newestId = messages.lastOrNull()?.id ?: cfg.lastMessageId
        prefs.saveDiscordConfig(
          cfg.copy(
            lastMessageId = newestId,
            lastSyncAt = System.currentTimeMillis(),
            lastStatus = "Pulled ${pulled.size} messages",
          )
        )
        ProviderResult.Success(pulled)
      } catch (e: IOException) {
        ProviderResult.Error("Network error pulling Discord messages", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord pull failed: ${e.localizedMessage}")
      }
    }

  private fun isValidWebhookUrl(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("https://discord.com/api/webhooks/") ||
      trimmed.startsWith("https://discordapp.com/api/webhooks/")
  }

  private fun buildDiscordMessage(clip: ClipItem, categoryName: String): String {
    val header =
      buildString {
        append("**[").append(categoryName).append("]** ")
        if (clip.title.isNotBlank()) append(clip.title)
      }.trim()
    return if (header.isNotEmpty()) "$header\n${clip.content}" else clip.content
  }
}
