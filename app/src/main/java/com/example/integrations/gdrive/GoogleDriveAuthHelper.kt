package com.example.integrations.gdrive

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.example.R
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface DriveAuthState {
  data class Authorized(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtMs: Long = 0L,
  ) : DriveAuthState

  data class NeedsUserConsent(val pendingIntent: PendingIntent) : DriveAuthState

  data class Failed(
    val message: String,
    val isUnregisteredConsole: Boolean = false,
  ) : DriveAuthState
}

data class BrowserOAuthSession(
  val authUri: Uri,
  val awaitResult: suspend () -> DriveAuthState,
  val cancel: () -> Unit,
)

@Singleton
open class GoogleDriveAuthHelper
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val okHttpClient: OkHttpClient,
) {

  constructor(context: Context) : this(context, OkHttpClient())

  companion object {
    const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val SCOPE_EMAIL = "https://www.googleapis.com/auth/userinfo.email"
    private const val AUTH_TIMEOUT_MS = 15_000L
    private const val BROWSER_OAUTH_TIMEOUT_MS = 180_000L
    private const val GOOGLE_AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val GOOGLE_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    private const val CALLBACK_PATH = "/oauth2callback"
  }

  @Volatile private var cachedToken: String? = null

  open fun getCachedAccessToken(): String? = cachedToken

  open fun setCachedAccessToken(token: String?) {
    cachedToken = token?.takeIf { it.isNotBlank() }
  }

  open fun clearCachedToken() {
    cachedToken = null
  }

  fun getPackageName(): String = context.packageName

  @Suppress("DEPRECATION")
  fun getSigningSha1Fingerprint(): String {
    return try {
      val pm = context.packageManager
      val pkg = context.packageName
      val signatures =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
          val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
          val signingInfo = info.signingInfo ?: return ""
          if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
          else signingInfo.signingCertificateHistory
        } else {
          pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
      val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return ""
      val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
      digest.joinToString(":") { b -> "%02X".format(b) }
    } catch (_: Exception) {
      ""
    }
  }

  open suspend fun requestAuthorization(includeVisibleFolderScope: Boolean): DriveAuthState {
    val availability =
      try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: Exception) {
        return DriveAuthState.Failed(
          e.localizedMessage ?: "Google Play Services check failed."
        )
      }
    if (availability != ConnectionResult.SUCCESS) {
      return DriveAuthState.Failed(
        context.getString(R.string.drive_err_play_services_unavailable, availability)
      )
    }

    return try {
      withTimeoutOrNull(AUTH_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
          val scopes = buildScopes(includeVisibleFolderScope)
          val request = AuthorizationRequest.builder().setRequestedScopes(scopes).build()
          Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
              if (!cont.isActive) return@addOnSuccessListener
              if (result.hasResolution()) {
                val pi = result.pendingIntent
                if (pi != null) {
                  cont.resume(DriveAuthState.NeedsUserConsent(pi))
                } else {
                  cont.resume(DriveAuthState.Failed("Authorization consent intent unavailable."))
                }
              } else {
                val token = result.accessToken
                if (!token.isNullOrBlank()) {
                  cachedToken = token
                  cont.resume(
                    DriveAuthState.Authorized(
                      accessToken = token,
                      expiresAtMs = System.currentTimeMillis() + 50 * 60_000L,
                    )
                  )
                } else {
                  cont.resume(
                    DriveAuthState.Failed("No access token returned by Google Play Services.")
                  )
                }
              }
            }
            .addOnFailureListener { e ->
              if (!cont.isActive) return@addOnFailureListener
              cont.resume(mapPlayServicesAuthError(e))
            }
        }
      } ?: DriveAuthState.Failed("Google Drive authorization timed out.")
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: Exception) {
      mapPlayServicesAuthError(e)
    }
  }

  fun handleAuthorizationResultIntent(intent: Intent?): DriveAuthState =
    handleAuthorizationResultIntent(Activity.RESULT_OK, intent)

  fun handleAuthorizationResultIntent(resultCode: Int, intent: Intent?): DriveAuthState {
    if (resultCode != Activity.RESULT_OK && intent == null) {
      return DriveAuthState.Failed(context.getString(R.string.drive_err_auth_canceled))
    }
    if (intent == null) {
      return DriveAuthState.Failed(context.getString(R.string.drive_err_auth_canceled))
    }
    return try {
      val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
      val token = result.accessToken
      if (!token.isNullOrBlank()) {
        cachedToken = token
        DriveAuthState.Authorized(
          accessToken = token,
          expiresAtMs = System.currentTimeMillis() + 50 * 60_000L,
        )
      } else {
        DriveAuthState.Failed("Access token missing from authorization response.")
      }
    } catch (e: Exception) {
      mapPlayServicesAuthError(e)
    }
  }

  suspend fun startBrowserLoopbackAuthorization(
    clientId: String,
    clientSecret: String,
    includeVisibleFolderScope: Boolean,
  ): Result<BrowserOAuthSession> =
    withContext(Dispatchers.IO) {
      val cleanClientId = clientId.trim()
      if (cleanClientId.isBlank()) {
        return@withContext Result.failure(IllegalArgumentException("OAuth Client ID is blank"))
      }

      runCatching {
        val serverSocket =
          ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).apply {
            soTimeout = BROWSER_OAUTH_TIMEOUT_MS.toInt()
          }
        val port = serverSocket.localPort
        val redirectUri = "http://127.0.0.1:$port$CALLBACK_PATH"
        val state = generateRandomUrlSafe(24)
        val codeVerifier = generateRandomUrlSafe(64)
        val codeChallenge = sha256Base64Url(codeVerifier)

        val scopeString =
          buildScopes(includeVisibleFolderScope).joinToString(" ") { it.scopeUri }

        val authUri =
          Uri.parse(GOOGLE_AUTH_ENDPOINT)
            .buildUpon()
            .appendQueryParameter("client_id", cleanClientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", scopeString)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()

        BrowserOAuthSession(
          authUri = authUri,
          cancel = { runCatching { serverSocket.close() } },
          awaitResult = {
            awaitLoopbackCodeAndExchange(
              serverSocket = serverSocket,
              expectedState = state,
              redirectUri = redirectUri,
              clientId = cleanClientId,
              clientSecret = clientSecret.trim(),
              codeVerifier = codeVerifier,
            )
          },
        )
      }
    }

  private suspend fun awaitLoopbackCodeAndExchange(
    serverSocket: ServerSocket,
    expectedState: String,
    redirectUri: String,
    clientId: String,
    clientSecret: String,
    codeVerifier: String,
  ): DriveAuthState =
    withContext(Dispatchers.IO) {
      try {
        serverSocket.use { server ->
          val deadline = System.currentTimeMillis() + BROWSER_OAUTH_TIMEOUT_MS
          while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L).toInt()
            server.soTimeout = remaining
            val socket =
              try {
                server.accept()
              } catch (_: SocketTimeoutException) {
                return@withContext DriveAuthState.Failed(
                  context.getString(R.string.drive_err_oauth_timeout)
                )
              }

            socket.use { client ->
              client.soTimeout = 10_000
              val reader = BufferedReader(InputStreamReader(client.getInputStream()))
              val requestLine = reader.readLine() ?: return@use
              // Drain headers quickly without blocking
              while (true) {
                val h = reader.readLine()
                if (h.isNullOrEmpty()) break
              }
              val parts = requestLine.split(" ")
              val rawTarget = parts.getOrNull(1) ?: "/"
              if (!rawTarget.startsWith(CALLBACK_PATH)) {
                val notFound = "HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                client.getOutputStream().write(notFound.toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()
                return@use
              }

              val callbackUri = Uri.parse("http://127.0.0.1$rawTarget")
              val errorParam = callbackUri.getQueryParameter("error")
              val returnedState = callbackUri.getQueryParameter("state")
              val authCode = callbackUri.getQueryParameter("code")

              val htmlSuccess =
                errorParam.isNullOrBlank() &&
                  !authCode.isNullOrBlank() &&
                  returnedState == expectedState

              writeHtmlCallbackResponse(client.getOutputStream(), htmlSuccess, errorParam)

              if (!errorParam.isNullOrBlank()) {
                return@withContext DriveAuthState.Failed(
                  if (errorParam == "access_denied") {
                    context.getString(R.string.drive_err_auth_canceled)
                  } else {
                    "Google OAuth error: $errorParam"
                  }
                )
              }
              if (returnedState != expectedState) {
                return@withContext DriveAuthState.Failed("OAuth state mismatch. Please retry.")
              }
              if (authCode.isNullOrBlank()) {
                return@withContext DriveAuthState.Failed("Authorization code missing from callback.")
              }

              return@withContext exchangeCodeForTokens(
                clientId = clientId,
                clientSecret = clientSecret,
                code = authCode,
                codeVerifier = codeVerifier,
                redirectUri = redirectUri,
              )
            }
          }
          DriveAuthState.Failed(context.getString(R.string.drive_err_oauth_timeout))
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (e: IOException) {
        DriveAuthState.Failed(context.getString(R.string.drive_err_auth_canceled))
      } catch (e: Exception) {
        DriveAuthState.Failed(e.localizedMessage ?: "Browser OAuth flow failed.")
      }
    }

  private fun writeHtmlCallbackResponse(
    out: java.io.OutputStream,
    success: Boolean,
    errorParam: String?,
  ) {
    val title = if (success) "Smart Clipboard • Connected" else "Smart Clipboard • OAuth Error"
    val heading =
      if (success) "تم التفويض بنجاح • Connected to Google Drive"
      else "فشل التفويض • Authorization Failed"
    val bodyText =
      if (success) {
        "يمكنك إغلاق هذه الصفحة والعودة إلى تطبيق Smart Clipboard.<br/>You can close this tab and return to Smart Clipboard."
      } else {
        "Error: ${errorParam ?: "Invalid response"}. Please return to Smart Clipboard and try again."
      }
    val html =
      """
      <!doctype html>
      <html>
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width,initial-scale=1" />
        <title>$title</title>
        <style>
          body{font-family:system-ui,-apple-system,sans-serif;background:#0f172a;color:#f8fafc;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:24px;text-align:center;}
          .card{max-width:420px;background:#1e293b;border-radius:16px;padding:28px;border:1px solid #334155;}
          h2{margin-top:0;color:${if (success) "#38bdf8" else "#f87171"};}
          p{color:#cbd5e1;line-height:1.6;}
        </style>
      </head>
      <body>
        <div class="card">
          <h2>$heading</h2>
          <p>$bodyText</p>
        </div>
      </body>
      </html>
      """.trimIndent()
    val bytes = html.toByteArray(Charsets.UTF_8)
    val header =
      "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
    out.write(header.toByteArray(Charsets.UTF_8))
    out.write(bytes)
    out.flush()
  }

  private fun exchangeCodeForTokens(
    clientId: String,
    clientSecret: String,
    code: String,
    codeVerifier: String,
    redirectUri: String,
  ): DriveAuthState {
    val formBuilder =
      FormBody.Builder()
        .add("client_id", clientId)
        .add("code", code)
        .add("code_verifier", codeVerifier)
        .add("redirect_uri", redirectUri)
        .add("grant_type", "authorization_code")
    if (clientSecret.isNotBlank()) {
      formBuilder.add("client_secret", clientSecret)
    }
    return executeTokenRequest(formBuilder.build(), fallbackRefreshToken = null)
  }

  open suspend fun refreshAccessToken(
    clientId: String,
    clientSecret: String,
    refreshToken: String,
  ): DriveAuthState =
    withContext(Dispatchers.IO) {
      val cleanClientId = clientId.trim()
      val cleanRefresh = refreshToken.trim()
      if (cleanClientId.isBlank() || cleanRefresh.isBlank()) {
        return@withContext DriveAuthState.Failed("OAuth Client ID or Refresh Token missing.")
      }
      val formBuilder =
        FormBody.Builder()
          .add("client_id", cleanClientId)
          .add("refresh_token", cleanRefresh)
          .add("grant_type", "refresh_token")
      if (clientSecret.isNotBlank()) {
        formBuilder.add("client_secret", clientSecret.trim())
      }
      executeTokenRequest(formBuilder.build(), fallbackRefreshToken = cleanRefresh)
    }

  private fun executeTokenRequest(
    formBody: FormBody,
    fallbackRefreshToken: String?,
  ): DriveAuthState {
    return try {
      val request = Request.Builder().url(GOOGLE_TOKEN_ENDPOINT).post(formBody).build()
      okHttpClient.newCall(request).execute().use { resp ->
        val raw = resp.body?.string().orEmpty()
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (!resp.isSuccessful) {
          val errCode = json?.optString("error").orEmpty()
          val errDesc = json?.optString("error_description").orEmpty()
          val friendly =
            when {
              errCode == "invalid_client" || errDesc.contains("client_secret", ignoreCase = true) ->
                context.getString(R.string.drive_err_invalid_client_secret)
              errCode == "redirect_uri_mismatch" ->
                context.getString(R.string.drive_err_redirect_uri_mismatch)
              errCode == "invalid_grant" ->
                context.getString(R.string.drive_err_invalid_grant)
              errDesc.isNotBlank() -> "$errCode: $errDesc"
              else -> "OAuth token exchange failed (HTTP ${resp.code})"
            }
          return DriveAuthState.Failed(friendly)
        }
        val accessToken = json?.optString("access_token").orEmpty()
        if (accessToken.isBlank()) {
          return DriveAuthState.Failed("Access token missing in OAuth token response.")
        }
        val expiresIn = json?.optLong("expires_in", 3600L) ?: 3600L
        val refreshToken =
          json?.optString("refresh_token")?.takeIf { it.isNotBlank() } ?: fallbackRefreshToken
        cachedToken = accessToken
        DriveAuthState.Authorized(
          accessToken = accessToken,
          refreshToken = refreshToken,
          expiresAtMs = System.currentTimeMillis() + (expiresIn - 60L).coerceAtLeast(60L) * 1000L,
        )
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (e: IOException) {
      DriveAuthState.Failed("Network error contacting Google OAuth server.")
    } catch (e: Exception) {
      DriveAuthState.Failed(e.localizedMessage ?: "OAuth token exchange error.")
    }
  }

  private fun mapPlayServicesAuthError(e: Exception): DriveAuthState.Failed {
    val msg = e.localizedMessage.orEmpty()
    val statusCode = (e as? ApiException)?.statusCode
    val isUnregistered =
      msg.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) ||
        statusCode == CommonStatusCodes.DEVELOPER_ERROR ||
        (statusCode == CommonStatusCodes.INTERNAL_ERROR &&
          (msg.contains("UNREGISTERED", ignoreCase = true) || msg.contains(":8")))
    if (isUnregistered) {
      return DriveAuthState.Failed(
        message = context.getString(R.string.drive_err_unregistered_console),
        isUnregisteredConsole = true,
      )
    }
    if (statusCode == CommonStatusCodes.CANCELED || msg.contains("CANCELED", ignoreCase = true)) {
      return DriveAuthState.Failed(context.getString(R.string.drive_err_auth_canceled))
    }
    if (statusCode == CommonStatusCodes.NETWORK_ERROR) {
      return DriveAuthState.Failed(context.getString(R.string.drive_err_network))
    }
    return DriveAuthState.Failed(
      msg.ifBlank { context.getString(R.string.drive_err_unregistered_console) },
      isUnregisteredConsole = false,
    )
  }

  private fun buildScopes(includeVisibleFolderScope: Boolean): List<Scope> = buildList {
    add(Scope(SCOPE_DRIVE_APPDATA))
    add(Scope(SCOPE_EMAIL))
    if (includeVisibleFolderScope) {
      add(Scope(SCOPE_DRIVE_FILE))
    }
  }

  private fun generateRandomUrlSafe(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    SecureRandom().nextBytes(bytes)
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
  }

  private fun sha256Base64Url(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
    return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
  }
}
