package com.example.core.clipboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.core.preferences.AppLanguage
import java.util.Locale

/**
 * Manages a silent, ongoing 1-tap notification with a "Save Clip" action so users
 * on any Android 10–15 device (including OEMs whose Quick Settings collapse slowly)
 * can save consecutive copied items with one tap without leaving their current app.
 */
object QuickCaptureNotificationHelper {

  const val CHANNEL_ID = "quick_capture_channel"
  const val NOTIFICATION_ID = 4102

  fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

  @SuppressLint("MissingPermission")
  fun showOrCancel(
    context: Context,
    enabled: Boolean,
    language: AppLanguage = AppLanguage.ARABIC,
  ) {
    val manager = NotificationManagerCompat.from(context)
    if (!enabled || !hasNotificationPermission(context)) {
      runCatching { manager.cancel(NOTIFICATION_ID) }
      return
    }

    val localized = localize(context, language)
    ensureChannel(context, localized)

    val captureIntent =
      Intent(context, QuickCaptureActivity::class.java).apply {
        action = QuickCaptureActivity.ACTION_CAPTURE_CLIPBOARD
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
      }

    val pendingIntent =
      PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        captureIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val title = localized.getString(R.string.quick_notif_title)
    val body = localized.getString(R.string.quick_notif_body)
    val actionLabel = localized.getString(R.string.quick_notif_action)

    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_qs_capture_clip)
        .setContentTitle(title)
        .setContentText(body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setContentIntent(pendingIntent)
        .addAction(R.drawable.ic_qs_capture_clip, actionLabel, pendingIntent)
        .build()

    runCatching { manager.notify(NOTIFICATION_ID, notification) }
  }

  private fun ensureChannel(context: Context, localized: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
    val channel =
      NotificationChannel(
          CHANNEL_ID,
          localized.getString(R.string.quick_notif_channel_name),
          NotificationManager.IMPORTANCE_LOW,
        )
        .apply {
          description = localized.getString(R.string.quick_notif_channel_desc)
          setSound(null, null)
          enableVibration(false)
          setShowBadge(false)
        }
    nm.createNotificationChannel(channel)
  }

  private fun localize(base: Context, language: AppLanguage): Context {
    if (language == AppLanguage.SYSTEM) return base
    val locale = Locale.forLanguageTag(language.tag)
    val config = Configuration(base.resources.configuration)
    config.setLocale(locale)
    return base.createConfigurationContext(config)
  }
}
