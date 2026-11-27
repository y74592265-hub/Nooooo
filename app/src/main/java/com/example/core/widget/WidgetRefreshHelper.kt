package com.example.core.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Triggers an asynchronous refresh for all pinned Smart Clipboard home screen widgets.
 * Safe no-op when no widgets are installed or in Robolectric unit tests.
 */
object WidgetRefreshHelper {
  fun requestRefresh(context: Context) {
    runCatching {
      val appCtx = context.applicationContext ?: context
      val manager = AppWidgetManager.getInstance(appCtx) ?: return
      val component = ComponentName(appCtx, ClipboardWidgetProvider::class.java)
      val ids = manager.getAppWidgetIds(component)
      if (ids == null || ids.isEmpty()) return
      val updateIntent =
        Intent(appCtx, ClipboardWidgetProvider::class.java).apply {
          action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
          putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
      appCtx.sendBroadcast(updateIntent)
    }
  }
}
