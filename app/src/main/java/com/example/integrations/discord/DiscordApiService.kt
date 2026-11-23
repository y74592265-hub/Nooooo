package com.example.integrations.discord

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

@JsonClass(generateAdapter = true)
data class DiscordWebhookPayload(
  val content: String,
  val username: String = "Smart Clipboard",
)

@JsonClass(generateAdapter = true)
data class DiscordMessageRequest(
  val content: String,
)

@JsonClass(generateAdapter = true)
data class DiscordAuthor(
  val id: String,
  val username: String,
  val bot: Boolean? = false,
)

@JsonClass(generateAdapter = true)
data class DiscordBotUser(
  val id: String,
  val username: String,
  val bot: Boolean? = true,
)

@JsonClass(generateAdapter = true)
data class DiscordGuild(
  val id: String,
  val name: String,
  val owner: Boolean? = false,
  val permissions: String? = "0",
)

@JsonClass(generateAdapter = true)
data class DiscordRole(
  val id: String,
  val name: String,
  val permissions: String? = "0",
)

@JsonClass(generateAdapter = true)
data class DiscordPermissionOverwrite(
  val id: String,
  val type: Int,
  val allow: String = "0",
  val deny: String = "0",
)

@JsonClass(generateAdapter = true)
data class DiscordCreateChannelRequest(
  val name: String,
  val type: Int = 0,
  val topic: String? = null,
  @Json(name = "permission_overwrites")
  val permissionOverwrites: List<DiscordPermissionOverwrite> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DiscordChannel(
  val id: String,
  val name: String? = null,
  val type: Int = 0,
  @Json(name = "guild_id") val guildId: String? = null,
)

@JsonClass(generateAdapter = true)
data class DiscordMessageDto(
  val id: String,
  val content: String = "",
  val author: DiscordAuthor? = null,
  val timestamp: String = "",
)

interface DiscordApiService {
  @POST
  suspend fun executeWebhook(
    @Url webhookUrl: String,
    @Body payload: DiscordWebhookPayload,
  ): Response<Unit>

  @GET
  suspend fun getWebhookInfo(@Url webhookUrl: String): Response<DiscordChannel>

  @GET("api/v10/users/@me")
  suspend fun getCurrentBotUser(
    @Header("Authorization") botAuthorization: String,
  ): Response<DiscordBotUser>

  @GET("api/v10/users/@me/guilds")
  suspend fun getBotGuilds(
    @Header("Authorization") botAuthorization: String,
  ): Response<List<DiscordGuild>>

  @GET("api/v10/guilds/{guildId}/channels")
  suspend fun getGuildChannels(
    @Header("Authorization") botAuthorization: String,
    @Path("guildId") guildId: String,
  ): Response<List<DiscordChannel>>

  @GET("api/v10/guilds/{guildId}/roles")
  suspend fun getGuildRoles(
    @Header("Authorization") botAuthorization: String,
    @Path("guildId") guildId: String,
  ): Response<List<DiscordRole>>

  @POST("api/v10/guilds/{guildId}/channels")
  suspend fun createGuildChannel(
    @Header("Authorization") botAuthorization: String,
    @Path("guildId") guildId: String,
    @Body body: DiscordCreateChannelRequest,
  ): Response<DiscordChannel>

  @GET("api/v10/channels/{channelId}")
  suspend fun getChannel(
    @Header("Authorization") botAuthorization: String,
    @Path("channelId") channelId: String,
  ): Response<DiscordChannel>

  @POST("api/v10/channels/{channelId}/messages")
  suspend fun sendChannelMessage(
    @Header("Authorization") botAuthorization: String,
    @Path("channelId") channelId: String,
    @Body body: DiscordMessageRequest,
  ): Response<DiscordMessageDto>

  @GET("api/v10/channels/{channelId}/messages")
  suspend fun getChannelMessages(
    @Header("Authorization") botAuthorization: String,
    @Path("channelId") channelId: String,
    @Query("after") afterMessageId: String? = null,
    @Query("limit") limit: Int = 25,
    @Query("before") beforeMessageId: String? = null,
  ): Response<List<DiscordMessageDto>>

  @DELETE("api/v10/channels/{channelId}/messages/{messageId}")
  suspend fun deleteChannelMessage(
    @Header("Authorization") botAuthorization: String,
    @Path("channelId") channelId: String,
    @Path("messageId") messageId: String,
  ): Response<Unit>
}
