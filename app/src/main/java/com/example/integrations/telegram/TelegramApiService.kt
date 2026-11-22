package com.example.integrations.telegram

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class TelegramResponse<T>(
  val ok: Boolean,
  val result: T? = null,
  @Json(name = "error_code") val errorCode: Int? = null,
  val description: String? = null,
)

@JsonClass(generateAdapter = true)
data class TelegramUser(
  val id: Long,
  val username: String? = null,
  @Json(name = "first_name") val firstName: String? = null,
)

@JsonClass(generateAdapter = true)
data class TelegramChat(
  val id: Long,
  val type: String? = null,
  val username: String? = null,
  val title: String? = null,
)

@JsonClass(generateAdapter = true)
data class TelegramMessage(
  @Json(name = "message_id") val messageId: Long,
  val chat: TelegramChat,
  val text: String? = null,
  val caption: String? = null,
  val date: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class TelegramUpdate(
  @Json(name = "update_id") val updateId: Long,
  val message: TelegramMessage? = null,
)

@JsonClass(generateAdapter = true)
data class TelegramSendMessageRequest(
  @Json(name = "chat_id") val chatId: String,
  val text: String,
  @Json(name = "disable_web_page_preview") val disableWebPagePreview: Boolean = true,
)

interface TelegramApiService {
  @GET("bot{token}/getMe")
  suspend fun getMe(@Path("token") token: String): Response<TelegramResponse<TelegramUser>>

  @POST("bot{token}/sendMessage")
  suspend fun sendMessage(
    @Path("token") token: String,
    @Body body: TelegramSendMessageRequest,
  ): Response<TelegramResponse<TelegramMessage>>

  @GET("bot{token}/getUpdates")
  suspend fun getUpdates(
    @Path("token") token: String,
    @Query("offset") offset: Long? = null,
    @Query("limit") limit: Int = 50,
    @Query("timeout") timeout: Int = 0,
  ): Response<TelegramResponse<List<TelegramUpdate>>>
}
