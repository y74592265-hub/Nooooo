package com.example.integrations.telegram

import com.example.core.preferences.AppPreferencesRepository
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.model.SyncProviderId
import com.example.domain.repository.CategoryRepository
import com.example.integrations.IntegrationProvider
import com.example.integrations.ProviderResult
import com.example.integrations.PulledClip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramProvider
@Inject
constructor(
  private val api: TelegramApiService,
  private val prefs: AppPreferencesRepository,
  private val categoryRepository: CategoryRepository,
) : IntegrationProvider {

  override val providerId: SyncProviderId = SyncProviderId.TELEGRAM

  private fun sanitizeToken(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return null
    return trimmed
  }

  override suspend fun isConfigured(): Boolean {
    val cfg = prefs.getTelegramConfigOnce()
    return cfg.enabled && sanitizeToken(cfg.botToken) != null && cfg.chatId.isNotBlank()
  }

  override suspend fun testConnection(): ProviderResult<String> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getTelegramConfigOnce()
      val token = sanitizeToken(cfg.botToken)
      if (token == null) {
        return@withContext ProviderResult.Error(
          if (cfg.botToken.isBlank()) "Telegram Bot Token is empty."
          else "Telegram Bot Token contains invalid spaces or control characters."
        )
      }
      try {
        val meResp = api.getMe(token)
        if (!meResp.isSuccessful || meResp.body()?.ok != true) {
          return@withContext toTelegramError(meResp, "Telegram API error")
        }
        val botUsername = meResp.body()?.result?.username ?: "Bot"
        var resolvedChatId = cfg.chatId.trim()
        if (resolvedChatId.isEmpty()) {
          val updatesResp = api.getUpdates(token = token, limit = 20)
          val latestChat =
            updatesResp.body()?.result?.lastOrNull { it.message != null }?.message?.chat
          if (latestChat != null) {
            resolvedChatId = latestChat.id.toString()
            prefs.saveTelegramConfig(
              cfg.copy(chatId = resolvedChatId, lastStatus = "Connected to @$botUsername")
            )
          }
        }
        if (resolvedChatId.isEmpty()) {
          return@withContext ProviderResult.Success(
            "@$botUsername verified. Open Telegram, start your bot (/start), then tap Detect Chat ID or enter Chat ID.",
            detail = botUsername,
          )
        }
        prefs.saveTelegramConfig(cfg.copy(lastStatus = "Connected to @$botUsername"))
        ProviderResult.Success("Connected to @$botUsername (Chat ID: $resolvedChatId)", botUsername)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error reaching Telegram: ${e.localizedMessage}", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Telegram connection failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  suspend fun detectChatIdFromUpdates(botToken: String): ProviderResult<String> =
    withContext(Dispatchers.IO) {
      val token = sanitizeToken(botToken)
      if (token == null) {
        return@withContext ProviderResult.Error(
          if (botToken.isBlank()) "Enter Bot Token first."
          else "Bot Token contains invalid characters or spaces."
        )
      }
      try {
        val resp = api.getUpdates(token = token, limit = 30)
        if (!resp.isSuccessful || resp.body()?.ok != true) {
          return@withContext toTelegramError(resp, "Telegram getUpdates error")
        }
        val chatId =
          resp.body()?.result?.lastOrNull { it.message != null }?.message?.chat?.id?.toString()
        if (chatId.isNullOrBlank()) {
          ProviderResult.Error(
            "No recent message found. Open your bot in Telegram and send /start first."
          )
        } else {
          ProviderResult.Success(chatId)
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error: ${e.localizedMessage}", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Telegram update check failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun pushSingleClipAndReturnRemoteId(clip: ClipItem): ProviderResult<String> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getTelegramConfigOnce()
      val token = sanitizeToken(cfg.botToken)
      val chatId = cfg.chatId.trim()
      if (!cfg.enabled || token == null || chatId.isEmpty()) {
        return@withContext ProviderResult.Error("Telegram integration is not configured.")
      }
      if (clip.isSensitive || clip.isTrashed) {
        return@withContext ProviderResult.Error("Sensitive or trashed clip cannot be pushed.")
      }
      try {
        val categories = categoryRepository.getAllCategoriesOnce().associateBy { it.id }
        val categoryName = categories[clip.categoryId]?.name ?: "Personal"
        val formatted = buildTelegramText(clip, categoryName)
        val rawChunks = formatted.chunked(3700)
        val chunks =
          if (rawChunks.size > 1) {
            rawChunks.mapIndexed { idx, part -> "[${idx + 1}/${rawChunks.size}]\n$part" }
          } else {
            rawChunks
          }

        var firstMessageId: Long? = null
        for (chunk in chunks) {
          val resp =
            api.sendMessage(
              token = token,
              body = TelegramSendMessageRequest(chatId = chatId, text = chunk),
            )
          if (!resp.isSuccessful || resp.body()?.ok != true) {
            return@withContext toTelegramError(resp, "Telegram send failed")
          }
          if (firstMessageId == null) {
            firstMessageId = resp.body()?.result?.messageId
          }
        }
        val remoteId = "tg:$chatId:${firstMessageId ?: clip.contentHash.take(8)}"
        prefs.saveTelegramConfig(
          cfg.copy(lastSyncAt = System.currentTimeMillis(), lastStatus = "Synced clip")
        )
        ProviderResult.Success(remoteId, detail = "${chunks.size} part(s)")
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error sending to Telegram", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Telegram push failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getTelegramConfigOnce()
      val token = sanitizeToken(cfg.botToken)
      val chatId = cfg.chatId.trim()
      if (!cfg.enabled || token == null || chatId.isEmpty()) {
        return@withContext ProviderResult.Error("Telegram integration is not configured.")
      }
      val categories = categoryRepository.getAllCategoriesOnce().associateBy { it.id }
      var sentCount = 0
      try {
        val exportable = clips.filter { !it.isSensitive && !it.isTrashed }
        for (clip in exportable) {
          val categoryName = categories[clip.categoryId]?.name ?: "Personal"
          val formatted = buildTelegramText(clip, categoryName)
          val rawChunks = formatted.chunked(3700)
          val chunks =
            if (rawChunks.size > 1) {
              rawChunks.mapIndexed { idx, part -> "[${idx + 1}/${rawChunks.size}]\n$part" }
            } else {
              rawChunks
            }
          for (chunk in chunks) {
            val resp =
              api.sendMessage(
                token = token,
                body = TelegramSendMessageRequest(chatId = chatId, text = chunk),
              )
            if (!resp.isSuccessful || resp.body()?.ok != true) {
              return@withContext toTelegramError(resp, "Telegram send failed")
            }
          }
          sentCount++
        }
        prefs.saveTelegramConfig(
          cfg.copy(lastSyncAt = System.currentTimeMillis(), lastStatus = "Pushed $sentCount clips")
        )
        ProviderResult.Success(sentCount)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error sending to Telegram", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Telegram push failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun pullClips(): ProviderResult<List<PulledClip>> =
    pullUpdatesInternal(advanceOffset = true, maxItems = 50)

  override suspend fun fetchRecoveryItems(maxItems: Int): ProviderResult<List<PulledClip>> =
    pullUpdatesInternal(advanceOffset = false, maxItems = maxItems.coerceIn(1, 100))

  private suspend fun pullUpdatesInternal(
    advanceOffset: Boolean,
    maxItems: Int,
  ): ProviderResult<List<PulledClip>> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getTelegramConfigOnce()
      val token = sanitizeToken(cfg.botToken)
      val chatId = cfg.chatId.trim()
      if (!cfg.enabled || token == null || chatId.isEmpty()) {
        return@withContext ProviderResult.Error("Telegram integration is not configured.")
      }
      try {
        val offset =
          if (advanceOffset && cfg.lastUpdateOffset > 0L) cfg.lastUpdateOffset + 1L else null
        val resp = api.getUpdates(token = token, offset = offset, limit = maxItems)
        if (!resp.isSuccessful || resp.body()?.ok != true) {
          return@withContext toTelegramError(resp, "Telegram pull failed")
        }
        val updates = resp.body()?.result.orEmpty()
        var maxUpdateId = cfg.lastUpdateOffset
        val pulled = mutableListOf<PulledClip>()

        for (upd in updates) {
          if (upd.updateId > maxUpdateId) maxUpdateId = upd.updateId
          val msg = upd.message ?: continue
          if (msg.chat.id.toString() != chatId) continue
          val rawText = (msg.text ?: msg.caption)?.trim().orEmpty()
          if (rawText.isEmpty() || rawText.startsWith("/start")) continue

          val hashtags =
            Regex("""#([A-Za-z0-9_\u0600-\u06FF]+)""")
              .findAll(rawText)
              .map { it.groupValues[1] }
              .toList()
          val remoteTs = if (msg.date > 0L) msg.date * 1000L else System.currentTimeMillis()
          pulled.add(
            PulledClip(
              content = rawText,
              categoryHint = hashtags.firstOrNull(),
              tags = hashtags,
              source = ClipSource.TELEGRAM,
              remoteId = "tg:${msg.chat.id}:${msg.messageId}",
              remoteTimestamp = remoteTs,
            )
          )
        }

        if (advanceOffset) {
          prefs.saveTelegramConfig(
            cfg.copy(
              lastUpdateOffset = maxUpdateId,
              lastSyncAt = System.currentTimeMillis(),
              lastStatus = "Pulled ${pulled.size} messages",
            )
          )
        }
        ProviderResult.Success(pulled)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error pulling Telegram updates", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Telegram pull failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun deleteRemoteClip(remoteId: String): ProviderResult<Unit> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getTelegramConfigOnce()
      val token = sanitizeToken(cfg.botToken)
        ?: return@withContext ProviderResult.Error("Telegram Bot Token missing.")
      val parsed = parseTelegramRemoteId(remoteId, cfg.chatId.trim())
        ?: return@withContext ProviderResult.Success(Unit)
      try {
        val resp =
          api.deleteMessage(
            token = token,
            body = TelegramDeleteMessageRequest(chatId = parsed.first, messageId = parsed.second),
          )
        if (resp.isSuccessful && resp.body()?.ok == true) {
          ProviderResult.Success(Unit)
        } else {
          val code = resp.code()
          val desc = resp.body()?.description.orEmpty()
          // Message older than 48h or already deleted should not leave permanent failure on local delete
          if (code == 400 && (desc.contains("not found", true) || desc.contains("can't be deleted", true))) {
            ProviderResult.Success(Unit)
          } else {
            toTelegramError(resp, "Telegram delete failed")
          }
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error deleting Telegram message", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Telegram delete failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  private fun parseTelegramRemoteId(remoteId: String, fallbackChatId: String): Pair<String, Long>? {
    val parts = remoteId.split(":")
    return when {
      parts.size == 3 && parts[0] == "tg" -> {
        val msgId = parts[2].toLongOrNull() ?: return null
        parts[1] to msgId
      }
      remoteId.toLongOrNull() != null && fallbackChatId.isNotBlank() ->
        fallbackChatId to remoteId.toLong()
      else -> null
    }
  }

  private fun <T> toTelegramError(resp: Response<TelegramResponse<T>>, prefix: String): ProviderResult.Error {
    val code = resp.code()
    var retryAfter = resp.body()?.parameters?.retryAfter ?: resp.headers()["Retry-After"]?.toLongOrNull()
    var desc = resp.body()?.description
    if (desc == null || retryAfter == null) {
      val rawErr = runCatching { resp.errorBody()?.string() }.getOrNull()
      if (!rawErr.isNullOrBlank() && rawErr.trimStart().startsWith("{")) {
        runCatching {
          val obj = JSONObject(rawErr)
          if (desc == null && obj.has("description")) desc = obj.optString("description")
          if (retryAfter == null && obj.has("parameters")) {
            val sec = obj.optJSONObject("parameters")?.optLong("retry_after", -1L) ?: -1L
            if (sec > 0L) retryAfter = sec
          }
        }
      }
    }
    val retryable = code == 429 || code >= 500
    val detail = desc?.takeIf { it.isNotBlank() } ?: "HTTP $code"
    return ProviderResult.Error(
      message = "$prefix: $detail",
      isRetryable = retryable,
      retryAfterSeconds = retryAfter,
    )
  }

  private fun buildTelegramText(clip: ClipItem, categoryName: String): String {
    val sb = StringBuilder()
    if (clip.title.isNotBlank()) sb.appendLine(clip.title)
    sb.appendLine(clip.content)
    val tagLine =
      (listOf(categoryName.replace(" ", "_")) + clip.tags.map { it.replace(" ", "_") })
        .distinct()
        .joinToString(" ") { "#$it" }
    sb.appendLine()
    sb.append(tagLine)
    return sb.toString()
  }
}
