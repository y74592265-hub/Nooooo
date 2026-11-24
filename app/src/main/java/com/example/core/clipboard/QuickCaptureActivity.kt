package com.example.core.clipboard

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Translucent, no-history capture activity invoked by the Quick Settings Tile or
 * Android's text-selection menu ("Quick Save Clip").
 *
 * Acquires brief window focus on Android 10+ to read ClipboardManager without
 * navigating the user away from their current app, saves the clip(s), shows a
 * brief Toast, and exits immediately.
 */
@AndroidEntryPoint
class QuickCaptureActivity : ComponentActivity() {

  companion object {
    const val ACTION_CAPTURE_CLIPBOARD = "com.example.action.QUICK_CAPTURE_CLIPBOARD"
  }

  @Inject lateinit var captureCoordinator: ClipboardCaptureCoordinator
  @Inject lateinit var clipboardHelper: SystemClipboardHelper
  @Inject lateinit var prefs: AppPreferencesRepository

  private val startedCapture = AtomicBoolean(false)
  private val mainHandler = Handler(Looper.getMainLooper())
  private var fallbackRunnable: Runnable? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
    } else {
      @Suppress("DEPRECATION")
      overridePendingTransition(0, 0)
    }

    val incomingProcessText =
      if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
        intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
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

    val fallback = Runnable {
      if (startedCapture.compareAndSet(false, true)) {
        runClipboardCapture(retryDelayMs = 80L)
      }
    }
    fallbackRunnable = fallback
    mainHandler.postDelayed(fallback, 280L)
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus && startedCapture.compareAndSet(false, true)) {
      fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
      runClipboardCapture(retryDelayMs = 90L)
    }
  }

  override fun onDestroy() {
    fallbackRunnable?.let { mainHandler.removeCallbacks(it) }
    super.onDestroy()
  }

  private fun runClipboardCapture(retryDelayMs: Long) {
    lifecycleScope.launch {
      var snapshots = clipboardHelper.readAllClipboardSnapshots()
      if (snapshots.isEmpty() && retryDelayMs > 0L) {
        delay(retryDelayMs)
        snapshots = clipboardHelper.readAllClipboardSnapshots()
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
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
      } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
      }
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
