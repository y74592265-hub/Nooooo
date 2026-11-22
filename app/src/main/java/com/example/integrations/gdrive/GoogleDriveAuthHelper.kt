package com.example.integrations.gdrive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface DriveAuthState {
  data class Authorized(val accessToken: String) : DriveAuthState

  data class NeedsUserConsent(val pendingIntent: PendingIntent) : DriveAuthState

  data class Failed(val message: String) : DriveAuthState
}

@Singleton
class GoogleDriveAuthHelper @Inject constructor(@ApplicationContext private val context: Context) {

  companion object {
    const val SCOPE_DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val SCOPE_EMAIL = "https://www.googleapis.com/auth/userinfo.email"
    private const val AUTH_TIMEOUT_MS = 15_000L
  }

  @Volatile private var cachedToken: String? = null

  fun getCachedAccessToken(): String? = cachedToken

  fun clearCachedToken() {
    cachedToken = null
  }

  suspend fun requestAuthorization(includeVisibleFolderScope: Boolean): DriveAuthState {
    val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
    if (availability != ConnectionResult.SUCCESS) {
      return DriveAuthState.Failed(
        "Google Play Services unavailable on this device (code $availability)."
      )
    }

    return withTimeoutOrNull(AUTH_TIMEOUT_MS) {
      suspendCancellableCoroutine { cont ->
        val scopes =
          buildList {
            add(Scope(SCOPE_DRIVE_APPDATA))
            add(Scope(SCOPE_EMAIL))
            if (includeVisibleFolderScope) {
              add(Scope(SCOPE_DRIVE_FILE))
            }
          }
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
                cont.resume(DriveAuthState.Authorized(token))
              } else {
                cont.resume(DriveAuthState.Failed("No access token returned by Google Play Services."))
              }
            }
          }
          .addOnFailureListener { e ->
            if (!cont.isActive) return@addOnFailureListener
            cont.resume(
              DriveAuthState.Failed(
                e.localizedMessage
                  ?: "Google Drive OAuth authorization failed. Check Play Services & OAuth client ID."
              )
            )
          }
      }
    } ?: DriveAuthState.Failed("Google Drive authorization timed out.")
  }

  fun handleAuthorizationResultIntent(intent: Intent?): DriveAuthState {
    if (intent == null) return DriveAuthState.Failed("Authorization canceled.")
    return try {
      val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
      val token = result.accessToken
      if (!token.isNullOrBlank()) {
        cachedToken = token
        DriveAuthState.Authorized(token)
      } else {
        DriveAuthState.Failed("Access token missing from authorization response.")
      }
    } catch (e: Exception) {
      DriveAuthState.Failed(e.localizedMessage ?: "Failed to parse authorization response.")
    }
  }
}
