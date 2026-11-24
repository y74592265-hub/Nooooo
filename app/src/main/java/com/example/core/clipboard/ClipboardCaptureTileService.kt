package com.example.core.clipboard

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.R

/**
 * Quick Settings Tile that lets the user save the current system clipboard
 * after any copy operation without switching away from their current app.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ClipboardCaptureTileService : TileService() {

  override fun onStartListening() {
    super.onStartListening()
    val tile = qsTile ?: return
    tile.state = Tile.STATE_ACTIVE
    tile.label = getString(R.string.qs_tile_label)
    tile.contentDescription = getString(R.string.qs_tile_label)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      tile.subtitle = getString(R.string.qs_tile_subtitle)
    }
    tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_capture_clip)
    tile.updateTile()
  }

  override fun onClick() {
    super.onClick()
    val action = Runnable { launchQuickCapture() }
    if (isLocked) {
      unlockAndRun(action)
    } else {
      action.run()
    }
  }

  @SuppressLint("StartActivityAndCollapseDeprecated")
  private fun launchQuickCapture() {
    val intent =
      Intent(this, QuickCaptureActivity::class.java).apply {
        action = QuickCaptureActivity.ACTION_CAPTURE_CLIPBOARD
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
      }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      val pendingIntent =
        PendingIntent.getActivity(
          this,
          1001,
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      startActivityAndCollapse(pendingIntent)
    } else {
      @Suppress("DEPRECATION")
      startActivityAndCollapse(intent)
    }
  }
}
