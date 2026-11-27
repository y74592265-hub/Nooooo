package com.example.core.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.example.MainActivity
import com.example.R
import com.example.core.clipboard.QuickCaptureActivity
import com.example.core.clipboard.SystemClipboardHelper
import com.example.core.preferences.AppLanguage
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.KeystoreCryptoManager
import com.example.data.local.ClipDao
import com.example.data.local.ImeClipProjectionRow
import com.example.domain.intelligence.ClipTemplateEngine
import com.example.domain.intelligence.UsageLearningEngine
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Interactive Home Screen Widget for Smart Clipboard:
 * - Privacy-safe: uses `clipDao.getImeCandidateProjections` (`isTrashed = 0 AND isSensitive = 0`)
 *   so encrypted/sensitive items never appear on the home screen.
 * - Respects Biometric App Lock: hides previews behind a tap-to-unlock prompt when enabled.
 * - Provides 1-tap Capture (via QuickCaptureActivity) and 1-tap Copy (with dynamic template expansion).
 */
@AndroidEntryPoint
class ClipboardWidgetProvider : AppWidgetProvider() {

  companion object {
    const val ACTION_COPY_WIDGET_CLIP = "com.example.action.WIDGET_COPY_CLIP"
    const val EXTRA_CLIP_ID = "extra_widget_clip_id"
    private const val MAX_WIDGET_ROWS = 4
  }

  @Inject lateinit var clipDao: ClipDao
  @Inject lateinit var clipboardHelper: SystemClipboardHelper
  @Inject lateinit var cryptoManager: KeystoreCryptoManager
  @Inject lateinit var prefs: AppPreferencesRepository
  @Inject lateinit var usageLearningEngine: UsageLearningEngine

  private val rowContainers =
    intArrayOf(R.id.widget_row_0, R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3)
  private val rowTexts =
    intArrayOf(
      R.id.widget_row_0_text,
      R.id.widget_row_1_text,
      R.id.widget_row_2_text,
      R.id.widget_row_3_text,
    )
  private val rowCopyLabels =
    intArrayOf(
      R.id.widget_row_0_copy,
      R.id.widget_row_1_copy,
      R.id.widget_row_2_copy,
      R.id.widget_row_3_copy,
    )

  override fun onReceive(context: Context, intent: Intent) {
    super.onReceive(context, intent)
    if (intent.action == ACTION_COPY_WIDGET_CLIP) {
      val clipId = intent.getLongExtra(EXTRA_CLIP_ID, -1L)
      if (clipId <= 0L) return
      val pendingResult = goAsync()
      CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
          handleWidgetCopy(context.applicationContext, clipId)
        } finally {
          pendingResult.finish()
        }
      }
    }
  }

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    if (appWidgetIds.isEmpty()) return
    val pendingResult = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        val appCtx = context.applicationContext
        val settings = runCatching { prefs.userSettings.first() }.getOrNull()
        val localized = localize(appCtx, settings?.language ?: AppLanguage.SYSTEM)
        val isLocked = settings?.appLockEnabled == true
        val items =
          if (isLocked) emptyList()
          else runCatching { clipDao.getImeCandidateProjections(MAX_WIDGET_ROWS) }.getOrDefault(emptyList())

        for (widgetId in appWidgetIds) {
          val views = buildRemoteViews(appCtx, localized, isLocked, items)
          appWidgetManager.updateAppWidget(widgetId, views)
        }
      } finally {
        pendingResult.finish()
      }
    }
  }

  private suspend fun handleWidgetCopy(appCtx: Context, clipId: Long) {
    val settings = runCatching { prefs.userSettings.first() }.getOrNull()
    if (settings?.appLockEnabled == true) {
      val unlockIntent =
        Intent(appCtx, MainActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
      withContext(Dispatchers.Main) { runCatching { appCtx.startActivity(unlockIntent) } }
      return
    }

    val rawContent = runCatching { clipDao.getPasteableContentById(clipId) }.getOrNull() ?: return
    val language = settings?.language ?: AppLanguage.SYSTEM
    val localized = localize(appCtx, language)
    val locale = localized.resources.configuration.locales.get(0) ?: Locale.getDefault()
    val hasTemplate = ClipTemplateEngine.hasTemplateTokens(rawContent)
    val finalContent =
      if (hasTemplate) ClipTemplateEngine.expand(rawContent, locale = locale) else rawContent

    prefs.updateLastDetectedClipHash(cryptoManager.sha256(finalContent))
    clipboardHelper.copyToClipboard("Smart Clipboard", finalContent, isSensitive = false)
    runCatching { usageLearningEngine.recordUsage(clipId = clipId, isSensitive = false) }

    val toastMsg =
      localized.getString(
        if (hasTemplate) R.string.msg_template_copied else R.string.action_copied
      )
    withContext(Dispatchers.Main) {
      Toast.makeText(appCtx, toastMsg, Toast.LENGTH_SHORT).show()
    }

    // Refresh widget rows so usage ordering stays up to date
    val manager = AppWidgetManager.getInstance(appCtx) ?: return
    val ids = manager.getAppWidgetIds(ComponentName(appCtx, ClipboardWidgetProvider::class.java))
    if (ids != null && ids.isNotEmpty()) {
      val items =
        runCatching { clipDao.getImeCandidateProjections(MAX_WIDGET_ROWS) }.getOrDefault(emptyList())
      for (id in ids) {
        manager.updateAppWidget(id, buildRemoteViews(appCtx, localized, isLocked = false, items))
      }
    }
  }

  private fun buildRemoteViews(
    appCtx: Context,
    localized: Context,
    isLocked: Boolean,
    items: List<ImeClipProjectionRow>,
  ): RemoteViews {
    val views = RemoteViews(appCtx.packageName, R.layout.widget_smart_clipboard)
    views.setTextViewText(R.id.widget_header_title, localized.getString(R.string.app_name))
    views.setTextViewText(R.id.widget_capture_btn, localized.getString(R.string.widget_capture_btn))

    val openAppIntent =
      Intent(appCtx, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
    val openAppPi =
      PendingIntent.getActivity(
        appCtx,
        2001,
        openAppIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    views.setOnClickPendingIntent(R.id.widget_header_title, openAppPi)

    val captureIntent =
      Intent(appCtx, QuickCaptureActivity::class.java).apply {
        action = QuickCaptureActivity.ACTION_CAPTURE_CLIPBOARD
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
      }
    val capturePi =
      PendingIntent.getActivity(
        appCtx,
        2002,
        captureIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    views.setOnClickPendingIntent(R.id.widget_capture_btn, capturePi)

    if (isLocked || items.isEmpty()) {
      views.setViewVisibility(R.id.widget_empty_state, View.VISIBLE)
      views.setTextViewText(
        R.id.widget_empty_state,
        localized.getString(
          if (isLocked) R.string.widget_locked_text else R.string.widget_empty_text
        ),
      )
      views.setOnClickPendingIntent(R.id.widget_empty_state, openAppPi)
      for (i in 0 until MAX_WIDGET_ROWS) {
        views.setViewVisibility(rowContainers[i], View.GONE)
      }
      return views
    }

    views.setViewVisibility(R.id.widget_empty_state, View.GONE)
    val copyLabel = localized.getString(R.string.action_copy)

    for (i in 0 until MAX_WIDGET_ROWS) {
      val item = items.getOrNull(i)
      if (item == null) {
        views.setViewVisibility(rowContainers[i], View.GONE)
      } else {
        views.setViewVisibility(rowContainers[i], View.VISIBLE)
        val snippet = formatRowLabel(item)
        views.setTextViewText(rowTexts[i], snippet)
        views.setTextViewText(rowCopyLabels[i], copyLabel)

        val copyIntent =
          Intent(appCtx, ClipboardWidgetProvider::class.java).apply {
            action = ACTION_COPY_WIDGET_CLIP
            data = Uri.parse("smartclip://widget/copy/${item.id}")
            putExtra(EXTRA_CLIP_ID, item.id)
          }
        val copyPi =
          PendingIntent.getBroadcast(
            appCtx,
            2100 + i,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
        views.setOnClickPendingIntent(rowContainers[i], copyPi)
      }
    }
    return views
  }

  private fun formatRowLabel(row: ImeClipProjectionRow): String {
    val body = row.preview.replace("\n", " ").trim()
    val core =
      if (row.title.isNotBlank()) "${row.title.trim()}: $body"
      else body
    return if (row.isPinned) "★ $core" else core
  }

  private fun localize(base: Context, language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return base
    val locale = Locale.forLanguageTag(language.tag)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
  }
}
