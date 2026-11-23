package com.example.integrations.discord

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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

data class DiscordGuildSummary(
  val id: String,
  val name: String,
  val canManageChannels: Boolean,
  val isOwner: Boolean,
)

data class DiscordVaultVerificationResult(
  val botUsername: String,
  val botUserId: String,
  val guilds: List<DiscordGuildSummary>,
)

/**
 * Discord Integration adhering strictly to official Discord Bot API & Webhook boundaries:
 * - Zero User-Token / Self-Bot usage (explicitly rejected).
 * - Dedicated Private Vault Channel creation using permission_overwrites:
 *   @everyone denied VIEW_CHANNEL (0x400), Bot + optional user/role granted VIEW_CHANNEL | SEND_MESSAGES | READ_MESSAGE_HISTORY.
 * - Outgoing messages via Bot API (preferred for real message_id tracking) or Webhook fallback.
 * - Paginated history recovery and remote deletion via Bot API.
 */
@Singleton
class DiscordProvider
@Inject
constructor(
  private val api: DiscordApiService,
  private val prefs: AppPreferencesRepository,
  private val categoryRepository: CategoryRepository,
) : IntegrationProvider {

  companion object {
    const val PERMISSION_ADMINISTRATOR = 0x8L
    const val PERMISSION_MANAGE_CHANNELS = 0x10L
    const val PERMISSION_VIEW_CHANNEL = 0x400L
    const val PERMISSION_SEND_MESSAGES = 0x800L
    const val PERMISSION_READ_MESSAGE_HISTORY = 0x10000L
    const val OVERWRITE_TYPE_ROLE = 0
    const val OVERWRITE_TYPE_MEMBER = 1
    const val VAULT_ALLOW_MASK =
      PERMISSION_VIEW_CHANNEL or PERMISSION_SEND_MESSAGES or PERMISSION_READ_MESSAGE_HISTORY
  }

  override val providerId: SyncProviderId = SyncProviderId.DISCORD

  override suspend fun isConfigured(): Boolean {
    val cfg = prefs.getDiscordConfigOnce()
    val hasOutgoing =
      isValidWebhookUrl(cfg.webhookUrl) ||
        (sanitizeBotToken(cfg.botToken) != null && cfg.channelId.isNotBlank())
    return cfg.enabled && hasOutgoing
  }

  fun sanitizeBotToken(raw: String): String? {
    val trimmed = raw.trim().removePrefix("Bot ").removePrefix("bot ").trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("mfa.", ignoreCase = true) || trimmed.startsWith("Bearer ", ignoreCase = true)) {
      return null
    }
    if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code >= 0x7F }) return null
    return trimmed
  }

  fun hasManageChannelsPermission(permissionsBitmask: String?, isOwner: Boolean = false): Boolean {
    if (isOwner) return true
    val bits = permissionsBitmask?.toLongOrNull() ?: return false
    return (bits and (PERMISSION_ADMINISTRATOR or PERMISSION_MANAGE_CHANNELS)) != 0L
  }

  suspend fun verifyBotAndFetchGuilds(rawBotToken: String): ProviderResult<DiscordVaultVerificationResult> =
    withContext(Dispatchers.IO) {
      val token = sanitizeBotToken(rawBotToken)
        ?: return@withContext ProviderResult.Error(
          "Invalid Discord Bot Token. Only official Bot Tokens are allowed (User Tokens are rejected)."
        )
      try {
        val auth = "Bot $token"
        val meResp = api.getCurrentBotUser(auth)
        if (!meResp.isSuccessful || meResp.body() == null) {
          return@withContext toDiscordError(meResp, "Bot token verification failed")
        }
        val me = meResp.body()!!
        if (me.bot == false) {
          return@withContext ProviderResult.Error(
            "User accounts/Self-Bots are prohibited. Use a Discord Bot Token."
          )
        }
        val guildsResp = api.getBotGuilds(auth)
        if (!guildsResp.isSuccessful) {
          return@withContext toDiscordError(guildsResp, "Could not list Bot servers")
        }
        val guilds =
          guildsResp.body().orEmpty().map { g ->
            val owner = g.owner == true
            DiscordGuildSummary(
              id = g.id,
              name = g.name,
              canManageChannels = hasManageChannelsPermission(g.permissions, owner),
              isOwner = owner,
            )
          }
        ProviderResult.Success(
          DiscordVaultVerificationResult(
            botUsername = me.username,
            botUserId = me.id,
            guilds = guilds,
          )
        )
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error verifying Discord Bot: ${e.localizedMessage}", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord verification failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  suspend fun fetchGuildRoles(rawBotToken: String, guildId: String): ProviderResult<List<DiscordRole>> =
    withContext(Dispatchers.IO) {
      val token = sanitizeBotToken(rawBotToken)
        ?: return@withContext ProviderResult.Error("Invalid Discord Bot Token.")
      try {
        val resp = api.getGuildRoles("Bot $token", guildId)
        if (!resp.isSuccessful) {
          return@withContext toDiscordError(resp, "Could not fetch server roles")
        }
        ProviderResult.Success(resp.body().orEmpty().filter { it.name != "@everyone" })
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error fetching roles", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Role fetch failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  suspend fun createOrLinkPrivateVaultChannel(
    rawBotToken: String,
    guildId: String,
    guildName: String,
    channelName: String = "smart-clipboard-vault",
    allowedUserId: String = "",
    allowedRoleId: String = "",
  ): ProviderResult<DiscordChannel> =
    withContext(Dispatchers.IO) {
      val token = sanitizeBotToken(rawBotToken)
        ?: return@withContext ProviderResult.Error("Invalid Discord Bot Token.")
      val auth = "Bot $token"
      val normalizedChannel =
        channelName.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").ifBlank {
          "smart-clipboard-vault"
        }

      try {
        val meResp = api.getCurrentBotUser(auth)
        if (!meResp.isSuccessful || meResp.body() == null) {
          return@withContext toDiscordError(meResp, "Bot verification failed")
        }
        val botUser = meResp.body()!!
        if (botUser.bot == false) {
          return@withContext ProviderResult.Error("Self-Bots/User Tokens are prohibited.")
        }

        // Reuse existing private vault channel if already created in this guild
        val existingResp = api.getGuildChannels(auth, guildId)
        val existing =
          if (existingResp.isSuccessful) {
            existingResp.body().orEmpty().firstOrNull {
              it.type == 0 && it.name.equals(normalizedChannel, ignoreCase = true)
            }
          } else null

        val targetChannel: DiscordChannel =
          if (existing != null) {
            existing
          } else {
            val overwrites = buildList {
              // 1. Deny @everyone VIEW_CHANNEL (role id == guildId in Discord API)
              add(
                DiscordPermissionOverwrite(
                  id = guildId,
                  type = OVERWRITE_TYPE_ROLE,
                  allow = "0",
                  deny = PERMISSION_VIEW_CHANNEL.toString(),
                )
              )
              // 2. Allow the Bot itself
              add(
                DiscordPermissionOverwrite(
                  id = botUser.id,
                  type = OVERWRITE_TYPE_MEMBER,
                  allow = VAULT_ALLOW_MASK.toString(),
                  deny = "0",
                )
              )
              // 3. Optional User ID
              val cleanUser = allowedUserId.trim()
              if (cleanUser.isNotEmpty() && cleanUser != botUser.id) {
                add(
                  DiscordPermissionOverwrite(
                    id = cleanUser,
                    type = OVERWRITE_TYPE_MEMBER,
                    allow = VAULT_ALLOW_MASK.toString(),
                    deny = "0",
                  )
                )
              }
              // 4. Optional Role ID
              val cleanRole = allowedRoleId.trim()
              if (cleanRole.isNotEmpty() && cleanRole != guildId) {
                add(
                  DiscordPermissionOverwrite(
                    id = cleanRole,
                    type = OVERWRITE_TYPE_ROLE,
                    allow = VAULT_ALLOW_MASK.toString(),
                    deny = "0",
                  )
                )
              }
            }

            val createResp =
              api.createGuildChannel(
                botAuthorization = auth,
                guildId = guildId,
                body =
                  DiscordCreateChannelRequest(
                    name = normalizedChannel,
                    type = 0,
                    topic = "Private Smart Clipboard Vault",
                    permissionOverwrites = overwrites,
                  ),
              )
            if (!createResp.isSuccessful || createResp.body() == null) {
              return@withContext toDiscordError(
                createResp,
                "Failed to create private channel #$normalizedChannel (ensure Bot has Manage Channels)",
              )
            }
            createResp.body()!!
          }

        // Verify Bot can read the private channel cleanly
        val readCheck = api.getChannelMessages(auth, targetChannel.id, limit = 1)
        if (!readCheck.isSuccessful) {
          return@withContext toDiscordError(
            readCheck,
            "Channel #${targetChannel.name ?: normalizedChannel} created, but Bot lacks Read History access",
          )
        }

        val current = prefs.getDiscordConfigOnce()
        val chName = targetChannel.name ?: normalizedChannel
        prefs.saveDiscordConfig(
          current.copy(
            enabled = true,
            botToken = token,
            channelId = targetChannel.id,
            channelName = chName,
            selectedGuildId = guildId,
            selectedGuildName = guildName,
            allowedUserId = allowedUserId.trim(),
            allowedRoleId = allowedRoleId.trim(),
            lastStatus = "Private Vault #$chName in $guildName",
          )
        )
        ProviderResult.Success(targetChannel, detail = "Private Vault #$chName ready")
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error setting up Discord Private Vault", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord Private Vault error: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun testConnection(): ProviderResult<String> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getDiscordConfigOnce()
      val webhook = cfg.webhookUrl.trim()
      val botToken = cfg.botToken.trim()
      val channelId = cfg.channelId.trim()

      if (webhook.isEmpty() && (botToken.isEmpty() || channelId.isEmpty())) {
        return@withContext ProviderResult.Error(
          "Provide a Discord Bot Token + Channel ID (Private Vault) or an outgoing Webhook URL."
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
            return@withContext toDiscordError(resp, "Webhook check failed")
          }
          statuses.add("Webhook ready")
        }

        if (botToken.isNotEmpty() && channelId.isNotEmpty()) {
          val safeBotToken =
            sanitizeBotToken(botToken)
              ?: return@withContext ProviderResult.Error(
                "Discord Bot Token is invalid (User Tokens are rejected)."
              )
          val chResp = api.getChannel("Bot $safeBotToken", channelId)
          if (!chResp.isSuccessful) {
            return@withContext toDiscordError(
              chResp,
              "Bot channel check failed. Verify Bot Token & permissions",
            )
          }
          val chName = chResp.body()?.name ?: channelId
          statuses.add("Bot linked to #$chName")
        }

        val summary = statuses.joinToString(" • ")
        prefs.saveDiscordConfig(cfg.copy(lastStatus = summary))
        ProviderResult.Success(summary)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error reaching Discord: ${e.localizedMessage}", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord test failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun pushSingleClipAndReturnRemoteId(clip: ClipItem): ProviderResult<String> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getDiscordConfigOnce()
      if (!cfg.enabled) return@withContext ProviderResult.Error("Discord integration is disabled.")
      if (clip.isSensitive || clip.isTrashed) {
        return@withContext ProviderResult.Error("Sensitive or trashed clip cannot be pushed.")
      }
      val webhook = cfg.webhookUrl.trim()
      val safeBotToken = sanitizeBotToken(cfg.botToken)
      val channelId = cfg.channelId.trim()
      val useBotChannel = safeBotToken != null && channelId.isNotEmpty()

      if (!useBotChannel && !isValidWebhookUrl(webhook)) {
        return@withContext ProviderResult.Error(
          "No valid Discord Bot Private Vault or outgoing Webhook configured."
        )
      }

      try {
        val categories = categoryRepository.getAllCategoriesOnce().associateBy { it.id }
        val catName = categories[clip.categoryId]?.name ?: "Personal"
        val messageText = buildDiscordMessage(clip, catName)
        val rawChunks = messageText.chunked(1850)
        val chunks =
          if (rawChunks.size > 1) {
            rawChunks.mapIndexed { idx, part -> "[${idx + 1}/${rawChunks.size}]\n$part" }
          } else {
            rawChunks
          }

        var firstMessageId: String? = null
        for (chunk in chunks) {
          if (useBotChannel) {
            val resp =
              api.sendChannelMessage(
                "Bot ${safeBotToken!!}",
                channelId,
                DiscordMessageRequest(content = chunk),
              )
            if (!resp.isSuccessful) {
              return@withContext toDiscordError(resp, "Discord Bot send failed")
            }
            if (firstMessageId == null) {
              firstMessageId = resp.body()?.id
            }
          } else {
            val resp = api.executeWebhook(webhook, DiscordWebhookPayload(content = chunk))
            if (!resp.isSuccessful) {
              return@withContext toDiscordError(resp, "Discord Webhook send failed")
            }
          }
        }
        val remoteId =
          if (useBotChannel && firstMessageId != null) {
            "discord:$channelId:$firstMessageId"
          } else {
            "discord:webhook:${clip.contentHash.take(8)}"
          }
        prefs.saveDiscordConfig(
          cfg.copy(lastSyncAt = System.currentTimeMillis(), lastStatus = "Synced clip")
        )
        ProviderResult.Success(remoteId, detail = "${chunks.size} part(s)")
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error sending to Discord", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord push failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun pushClips(clips: List<ClipItem>): ProviderResult<Int> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getDiscordConfigOnce()
      if (!cfg.enabled) return@withContext ProviderResult.Error("Discord integration is disabled.")
      val webhook = cfg.webhookUrl.trim()
      val safeBotToken = sanitizeBotToken(cfg.botToken)
      val channelId = cfg.channelId.trim()
      val useBotChannel = safeBotToken != null && channelId.isNotEmpty()

      if (!isValidWebhookUrl(webhook) && !useBotChannel) {
        return@withContext ProviderResult.Error(
          "No valid Discord outgoing Webhook or Bot channel configured."
        )
      }

      val categories = categoryRepository.getAllCategoriesOnce().associateBy { it.id }
      var sentCount = 0
      try {
        val exportable = clips.filter { !it.isSensitive && !it.isTrashed }
        for (clip in exportable) {
          val catName = categories[clip.categoryId]?.name ?: "Personal"
          val messageText = buildDiscordMessage(clip, catName)
          val rawChunks = messageText.chunked(1850)
          val chunks =
            if (rawChunks.size > 1) {
              rawChunks.mapIndexed { idx, part -> "[${idx + 1}/${rawChunks.size}]\n$part" }
            } else {
              rawChunks
            }
          for (chunk in chunks) {
            if (isValidWebhookUrl(webhook) && !useBotChannel) {
              val resp = api.executeWebhook(webhook, DiscordWebhookPayload(content = chunk))
              if (!resp.isSuccessful) {
                return@withContext toDiscordError(resp, "Discord Webhook send failed")
              }
            } else if (useBotChannel) {
              val resp =
                api.sendChannelMessage(
                  "Bot ${safeBotToken!!}",
                  channelId,
                  DiscordMessageRequest(content = chunk),
                )
              if (!resp.isSuccessful) {
                return@withContext toDiscordError(resp, "Discord send failed")
              }
            } else {
              val resp = api.executeWebhook(webhook, DiscordWebhookPayload(content = chunk))
              if (!resp.isSuccessful) {
                return@withContext toDiscordError(resp, "Discord send failed")
              }
            }
          }
          sentCount++
        }
        prefs.saveDiscordConfig(
          cfg.copy(lastSyncAt = System.currentTimeMillis(), lastStatus = "Pushed $sentCount clips")
        )
        ProviderResult.Success(sentCount)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error sending to Discord", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord push failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun pullClips(): ProviderResult<List<PulledClip>> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getDiscordConfigOnce()
      if (!cfg.enabled) return@withContext ProviderResult.Error("Discord integration is disabled.")
      val safeBotToken = sanitizeBotToken(cfg.botToken)
      val channelId = cfg.channelId.trim()
      // Webhooks cannot read messages — return empty cleanly if only webhook is configured
      if (safeBotToken == null || channelId.isEmpty()) {
        return@withContext ProviderResult.Success(
          emptyList(),
          detail = "Bot Token + Channel ID required for incoming messages",
        )
      }
      try {
        val resp =
          api.getChannelMessages(
            botAuthorization = "Bot $safeBotToken",
            channelId = channelId,
            afterMessageId = cfg.lastMessageId,
            limit = 50,
          )
        if (!resp.isSuccessful) {
          return@withContext toDiscordError(resp, "Discord fetch failed")
        }
        val messages = resp.body().orEmpty().sortedBy { it.id.toLongOrNull() ?: 0L }
        val pulled =
          messages
            .filter { it.content.isNotBlank() && it.author?.bot != true }
            .map { msg ->
              val parsed = parseFormattedDiscordContent(msg.content)
              PulledClip(
                content = parsed.content,
                title = parsed.title,
                categoryHint = parsed.categoryHint,
                source = ClipSource.DISCORD,
                remoteId = "discord:$channelId:${msg.id}",
                remoteTimestamp = parseIsoTimestamp(msg.timestamp),
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
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error pulling Discord messages", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord pull failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun fetchRecoveryItems(maxItems: Int): ProviderResult<List<PulledClip>> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getDiscordConfigOnce()
      val safeBotToken = sanitizeBotToken(cfg.botToken)
      val channelId = cfg.channelId.trim()
      if (!cfg.enabled || safeBotToken == null || channelId.isEmpty()) {
        return@withContext ProviderResult.Error(
          "Discord Bot Token and Channel ID are required to scan cloud history."
        )
      }
      try {
        val collected = mutableListOf<DiscordMessageDto>()
        var beforeId: String? = null
        val targetCount = maxItems.coerceIn(1, 300)
        var pages = 0
        while (collected.size < targetCount && pages < 4) {
          val pageLimit = (targetCount - collected.size).coerceIn(1, 100)
          val resp =
            api.getChannelMessages(
              botAuthorization = "Bot $safeBotToken",
              channelId = channelId,
              afterMessageId = null,
              limit = pageLimit,
              beforeMessageId = beforeId,
            )
          if (!resp.isSuccessful) {
            return@withContext toDiscordError(resp, "Discord recovery scan failed")
          }
          val batch = resp.body().orEmpty()
          if (batch.isEmpty()) break
          collected.addAll(batch)
          val oldest = batch.minByOrNull { it.id.toLongOrNull() ?: Long.MAX_VALUE }
          if (oldest == null || batch.size < pageLimit) break
          beforeId = oldest.id
          pages++
        }

        val items =
          collected
            .distinctBy { it.id }
            .filter { it.content.isNotBlank() }
            .sortedByDescending { it.id.toLongOrNull() ?: 0L }
            .map { msg ->
              val parsed = parseFormattedDiscordContent(msg.content)
              PulledClip(
                content = parsed.content,
                title = parsed.title,
                categoryHint = parsed.categoryHint,
                source = ClipSource.DISCORD,
                remoteId = "discord:$channelId:${msg.id}",
                remoteTimestamp = parseIsoTimestamp(msg.timestamp),
              )
            }
        ProviderResult.Success(items)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error scanning Discord history", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord recovery scan failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  override suspend fun deleteRemoteClip(remoteId: String): ProviderResult<Unit> =
    withContext(Dispatchers.IO) {
      val cfg = prefs.getDiscordConfigOnce()
      val safeBotToken = sanitizeBotToken(cfg.botToken)
        ?: return@withContext ProviderResult.Error("Discord Bot Token required to delete remote messages.")
      val parts = remoteId.split(":")
      if (parts.size != 3 || parts[0] != "discord" || parts[1] == "webhook") {
        return@withContext ProviderResult.Success(Unit)
      }
      val channelId = parts[1]
      val messageId = parts[2]
      try {
        val resp = api.deleteChannelMessage("Bot $safeBotToken", channelId, messageId)
        if (resp.isSuccessful || resp.code() == 404) {
          ProviderResult.Success(Unit)
        } else {
          toDiscordError(resp, "Discord remote delete failed")
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        ProviderResult.Error("Network error deleting Discord message", isRetryable = true)
      } catch (e: Exception) {
        ProviderResult.Error("Discord delete failed: ${e.localizedMessage ?: "Unexpected error"}")
      }
    }

  private fun <T> toDiscordError(resp: Response<T>, prefix: String): ProviderResult.Error {
    val code = resp.code()
    val retryAfterHeader =
      resp.headers()["Retry-After"]?.toDoubleOrNull()
        ?: resp.headers()["X-RateLimit-Reset-After"]?.toDoubleOrNull()
    val retryAfterSeconds = retryAfterHeader?.let { ceil(it).toLong().coerceAtLeast(1L) }
    val retryable = code == 429 || code >= 500
    return ProviderResult.Error(
      message = "$prefix (HTTP $code)",
      isRetryable = retryable,
      retryAfterSeconds = retryAfterSeconds,
    )
  }

  private fun isValidWebhookUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.code < 0x20 }) return false
    val parsed = trimmed.toHttpUrlOrNull() ?: return false
    if (!parsed.isHttps) return false
    val allowedHosts = setOf("discord.com", "discordapp.com", "ptb.discord.com", "canary.discord.com")
    if (parsed.host.lowercase() !in allowedHosts) return false
    return parsed.encodedPath.startsWith("/api/webhooks/")
  }

  private fun buildDiscordMessage(clip: ClipItem, categoryName: String): String {
    val header =
      buildString {
        append("**[").append(categoryName).append("]** ")
        if (clip.title.isNotBlank()) append(clip.title)
      }.trim()
    return if (header.isNotEmpty()) "$header\n${clip.content}" else clip.content
  }

  private data class ParsedDiscordContent(
    val content: String,
    val title: String = "",
    val categoryHint: String? = null,
  )

  private fun parseFormattedDiscordContent(raw: String): ParsedDiscordContent {
    val trimmed = raw.trim()
    val match = Regex("""^\*\*\[([^\]]+)]\*\*\s*(.*?)\n([\s\S]+)$""").matchEntire(trimmed)
    return if (match != null) {
      ParsedDiscordContent(
        categoryHint = match.groupValues[1].trim(),
        title = match.groupValues[2].trim(),
        content = match.groupValues[3].trim(),
      )
    } else {
      ParsedDiscordContent(content = trimmed)
    }
  }

  private fun parseIsoTimestamp(iso: String): Long {
    if (iso.isBlank()) return System.currentTimeMillis()
    val clean = iso.replace(Regex("""\.\d+"""), "").replace("+00:00", "Z")
    return runCatching {
      val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      }
      fmt.parse(clean)?.time ?: System.currentTimeMillis()
    }.getOrDefault(System.currentTimeMillis())
  }
}
