package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.core.preferences.AppPreferencesRepository
import com.example.ui.SmartClipboardRoot
import com.example.ui.viewmodel.ClipboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

  @Inject lateinit var prefs: AppPreferencesRepository

  private val clipboardViewModel: ClipboardViewModel by viewModels()

  private var sharedIncomingText by mutableStateOf<String?>(null)
  private var isLocked by mutableStateOf(false)
  private var lockErrorMessage by mutableStateOf<String?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    extractIncomingShareText(intent)

    lifecycleScope.launch {
      val settings = prefs.userSettings.first()
      if (settings.appLockEnabled) {
        isLocked = true
        promptBiometricUnlock()
      }
    }

    setContent {
      SmartClipboardRoot(
        sharedIncomingText = sharedIncomingText,
        onSharedTextConsumed = { sharedIncomingText = null },
        isLocked = isLocked,
        lockErrorMessage = lockErrorMessage,
        onRequestUnlock = { promptBiometricUnlock() },
        clipboardViewModel = clipboardViewModel,
      )
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    extractIncomingShareText(intent)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus && !isLocked) {
      clipboardViewModel.onAppWindowFocused()
    }
  }

  private fun extractIncomingShareText(intent: Intent?) {
    if (intent == null) return
    val text =
      when (intent.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT ->
          intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
      }
    if (!text.isNullOrBlank()) {
      sharedIncomingText = text.trim()
    }
  }

  private fun promptBiometricUnlock() {
    val biometricManager = BiometricManager.from(this)
    val authenticators =
      BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val canAuth = biometricManager.canAuthenticate(authenticators)
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
      // No biometric or device credential enrolled on this device/emulator — avoid locking user out permanently
      isLocked = false
      lockErrorMessage = null
      return
    }

    val executor = ContextCompat.getMainExecutor(this)
    val prompt =
      BiometricPrompt(
        this,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            isLocked = false
            lockErrorMessage = null
          }

          override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            lockErrorMessage = errString.toString()
          }
        },
      )

    val info =
      BiometricPrompt.PromptInfo.Builder()
        .setTitle(getString(R.string.lock_title))
        .setSubtitle(getString(R.string.lock_subtitle))
        .setAllowedAuthenticators(authenticators)
        .build()

    prompt.authenticate(info)
  }
}
