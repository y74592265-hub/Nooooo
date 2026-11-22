package com.example.integrations.discord

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
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
data class DiscordChannel(
  val id: String,
  val name: String? = null,
  val type: Int = 0,
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
  ): Response<List<DiscordMessageDto>>
}
