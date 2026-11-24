package com.example.core.clipboard

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.R

object QuickTileInstaller {

  fun requestAddCaptureTile(context: Context, onFeedback: (String) -> Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val sbm = context.getSystemService(StatusBarManager::class.java)
      if (sbm == null) {
        onFeedback(context.getString(R.string.multicopy_tile_manual_hint))
        return
      }
      runCatching {
          sbm.requestAddTileService(
            ComponentName(context, ClipboardCaptureTileService::class.java),
            context.getString(R.string.qs_tile_label),
            Icon.createWithResource(context, R.drawable.ic_qs_capture_clip),
            ContextCompat.getMainExecutor(context),
          ) { resultCode ->
            val msgRes =
              when (resultCode) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                  R.string.multicopy_tile_added
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                  R.string.multicopy_tile_already_added
                else -> R.string.multicopy_tile_manual_hint
              }
            onFeedback(context.getString(msgRes))
          }
        }
        .onFailure { onFeedback(context.getString(R.string.multicopy_tile_manual_hint)) }
    } else {
      onFeedback(context.getString(R.string.multicopy_tile_manual_hint))
    }
  }
}
