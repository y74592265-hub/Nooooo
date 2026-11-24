package com.example.core.clipboard

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.AppPreferencesRepository
import com.example.domain.model.ClipSource
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Translucent, no-history capture activity invoked by the Quick Capture Notification,
 * Quick Settings Tile, or Android's text-selection menu ("Quick Save Clip").
 *
 * Attaches a 1dp focusable transparent anchor View so OEM WindowManager skins reliably grant
 * window focus after Quick Settings / Notification shade collapse, retries briefly for
 * ClipboardService focus token propagation on Android 10–15, saves all snapshots, shows a
 * localized Toast, and exits immediately without navigating away from the user's current app.
 */
@AndroidEntryPoint
class QuickCaptureActivity : ComponentActivity() {

  companion object {
    const val ACTION_CAPTURE_CLIPBOARD = "com.example.action.QUICK_CAPTURE_CLIPBOARD"
    private const val FALLBACK_FOCUS_TIMEOUT_MS = 950L
    private val RETRY_DELAYS_MS = longArrayOf(60L, 100L, 140L)
  }

  @Inject lateinit var captureCoordinator: ClipboardCaptureCoordinator
  @Inject lateinit var clipboardHelper: SystemClipboardHelper
  @Inject lateinit var prefs: AppPreferencesRepository

  private val startedCapture = AtomicBoolean(false)
  private val mainHandler = Handler(Looper.getMainLooper())
  private var fallbackRunnable: Runnable? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    disableTransitionAnimations(isOpen = true)

    // Attach a minimal focusable View so OEM skins dispatch onWindowFocusChanged(true) cleanly
    val focusAnchor =
      View(this).apply {
        layoutParams = ViewGroup.LayoutParams(1, 1)
        isFocusable = true
        isFocusableInTouchMode = true
      }
    setContentView(focusAnchor)
    focusAnchor.requestFocus()

    handleIncomingIntent(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
    startedCapture.set(false)
    handleIncomingIntent(intent)
  }

  private fun handleIncomingIntent(targetIntent: Intent?) {
    val incomingProcessText =
      if (targetIntent?.action == Intent.ACTION_PROCESS_TEXT) {
        targetIntent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
      } else {
        null
      }

    if (!incomingProcessText.isNullOrBlank()) {
      startedCapture.set(true)
      lifecycleScope.launch {
        val outcome =
          captureCoordinator.captureSnippetDirectly(
            rawText = incomingProcessText,
            source = ClipSource.SHARE_SHEET,
          )
        showOutcomeToast(listOf(outcome))
        finishSilently()
      }
      return
    }

    if (hasWindowFocus() && startedCapture.compareAndSet(false, true)) {
      runClipboardCaptureWithRetry()
      return
    }

    val fallback = Runnable {
      if (startedCapture.compareAndSet(false, true)) {
        runClipboardCaptureWithRetry()
      }
    }
    fallbackRunnable = fallback
    mainHandler.postDelayed(fallback, FALLBACK_FOCUS_TIMEOUT_MS)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus && startedCapture.compareAndSet(false, true)) {
      fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
      runClipboardCaptureWithRetry()
    }
  }

  override fun onDestroy() {
    fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
    super.onDestroy()
  }

  private fun runClipboardCaptureWithRetry() {
    lifecycleScope.launch {
      var snapshots = clipboardHelper.readAllClipboardSnapshots()
      if (snapshots.isEmpty()) {
        for (waitMs in RETRY_DELAYS_MS) {
          delay(waitMs)
          snapshots = clipboardHelper.readAllClipboardSnapshots()
          if (snapshots.isNotEmpty()) break
        }
      }
      val outcomes =
        captureCoordinator.captureAllFromSystemClipboard(
          source = ClipSource.CLIPBOARD,
          overrideSnapshots = snapshots.ifEmpty { null },
          allowWhenAutoCaptureDisabled = true,
        )
      showOutcomeToast(outcomes)
      finishSilently()
    }
  }

  private suspend fun showOutcomeToast(outcomes: List<CaptureOutcome>) {
    val settings = runCatching { prefs.userSettings.first() }.getOrNull()
    val localized = localize(this, settings?.language ?: AppLanguage.ARABIC)
    val saved = outcomes.filterIsInstance<CaptureOutcome.Saved>()

    val message =
      when {
        saved.size > 1 ->
          localized.getString(R.string.quick_capture_saved_count, saved.size)
        saved.size == 1 -> {
          val item = saved.first()
          if (item.autoMarkedSensitive) {
            localized.getString(R.string.quick_capture_sensitive)
          } else {
            val preview =
              item.clip.content.replace("\n", " ").trim().let {
                if (it.length > 36) "${it.take(36)}…" else it
              }
            localized.getString(R.string.quick_capture_saved, preview)
          }
        }
        outcomes.any { it is CaptureOutcome.AlreadyCaptured } ->
          localized.getString(R.string.quick_capture_already_saved)
        outcomes.any { it is CaptureOutcome.SkippedSensitive } ->
          localized.getString(R.string.msg_sensitive_skipped)
        outcomes.any { it is CaptureOutcome.ExcludedByRule } ->
          localized.getString(R.string.quick_capture_excluded)
        else -> localized.getString(R.string.quick_capture_empty)
      }

    Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
  }

  private fun finishSilently() {
    if (!isFinishing) {
      finish()
      disableTransitionAnimations(isOpen = false)
    }
  }

  private fun disableTransitionAnimations(isOpen: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      overrideActivityTransition(
        if (isOpen) OVERRIDE_TRANSITION_OPEN else OVERRIDE_TRANSITION_CLOSE,
        0,
        0,
      )
    } else {
      @Suppress("DEPRECATION")
      overridePendingTransition(0, 0)
    }
  }

  private fun localize(base: Context, language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return base
    val locale = Locale.forLanguageTag(language.tag)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
  }
}
