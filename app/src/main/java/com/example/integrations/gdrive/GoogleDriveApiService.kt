package com.example.integrations.gdrive

import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class DriveFileDto(
  val id: String,
  val name: String,
  val mimeType: String? = null,
  val modifiedTime: String? = null,
  val size: String? = null,
)

@JsonClass(generateAdapter = true)
data class DriveFileListResponse(
  val files: List<DriveFileDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class DriveCreateFolderRequest(
  val name: String,
  val mimeType: String = "application/vnd.google-apps.folder",
)

@JsonClass(generateAdapter = true)
data class GoogleUserInfoResponse(
  val email: String? = null,
  val name: String? = null,
)

interface GoogleDriveApiService {
  @GET("oauth2/v3/userinfo")
  suspend fun getUserInfo(
    @Header("Authorization") bearerToken: String,
  ): Response<GoogleUserInfoResponse>

  @GET("drive/v3/files")
  suspend fun listFiles(
    @Header("Authorization") bearerToken: String,
    @Query("spaces") spaces: String = "appDataFolder",
    @Query("q") query: String? = null,
    @Query("fields") fields: String = "files(id,name,mimeType,modifiedTime,size)",
    @Query("orderBy") orderBy: String = "modifiedTime desc",
    @Query("pageSize") pageSize: Int = 25,
  ): Response<DriveFileListResponse>

  @POST("drive/v3/files")
  suspend fun createFolder(
    @Header("Authorization") bearerToken: String,
    @Body body: DriveCreateFolderRequest,
  ): Response<DriveFileDto>

  @Multipart
  @POST("upload/drive/v3/files?uploadType=multipart")
  suspend fun uploadBackupFile(
    @Header("Authorization") bearerToken: String,
    @Part("metadata") metadata: RequestBody,
    @Part file: MultipartBody.Part,
  ): Response<DriveFileDto>

  @GET("drive/v3/files/{fileId}?alt=media")
  suspend fun downloadFileContent(
    @Header("Authorization") bearerToken: String,
    @Path("fileId") fileId: String,
  ): Response<ResponseBody>
}
