package com.example.ui.components

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlin.math.roundToInt

/**
 * Window-scoped, re-entrancy-safe [TextToolbar] implementation replacing Compose's default
 * [androidx.compose.ui.platform.AndroidTextToolbar] to prevent Android's
 * `DecorView: Destroying unexpected ActionMode instance of TYPE_FLOATING` error.
 *
 * Root causes prevented:
 * 1. Nullifies `actionMode` and guards `isFinishing` BEFORE calling `ActionMode.finish()`,
 *    so synchronous re-entrant calls to `hide()` inside `onDestroyActionMode` or
 *    `onActionItemClicked` never invoke `finish()` twice on a nulled `mFloatingActionMode`.
 * 2. Avoids calling `startActionMode` / `finish()` when the host `View` is detached from window.
 * 3. Uses `invalidateContentRect()` when only coordinates move during selection-handle drags.
 */
class PlatformSafeTextToolbar(private val view: View) : TextToolbar {

  companion object {
    private const val MENU_ID_COPY = 0
    private const val MENU_ID_PASTE = 1
    private const val MENU_ID_CUT = 2
    private const val MENU_ID_SELECT_ALL = 3
  }

  private var actionMode: ActionMode? = null
  private var isFinishing = false

  private var contentRect = AndroidRect()
  private var onCopyRequested: (() -> Unit)? = null
  private var onPasteRequested: (() -> Unit)? = null
  private var onCutRequested: (() -> Unit)? = null
  private var onSelectAllRequested: (() -> Unit)? = null

  private var lastHasCopy = false
  private var lastHasPaste = false
  private var lastHasCut = false
  private var lastHasSelectAll = false

  override var status: TextToolbarStatus = TextToolbarStatus.Hidden
    private set

  private val callback =
    object : ActionMode.Callback2() {
      override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        if (menu == null) return false
        populateMenu(menu)
        return true
      }

      override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        if (menu == null) return false
        menu.clear()
        populateMenu(menu)
        return true
      }

      override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        val action =
          when (item?.itemId) {
            MENU_ID_COPY -> onCopyRequested
            MENU_ID_PASTE -> onPasteRequested
            MENU_ID_CUT -> onCutRequested
            MENU_ID_SELECT_ALL -> onSelectAllRequested
            else -> return false
          }
        action?.invoke()
        // Only finish if the action callback did not already hide/destroy this ActionMode.
        if (mode != null && actionMode === mode && !isFinishing) {
          hide()
        }
        return true
      }

      override fun onDestroyActionMode(mode: ActionMode?) {
        if (mode == null || actionMode === mode) {
          actionMode = null
          status = TextToolbarStatus.Hidden
        }
      }

      override fun onGetContentRect(mode: ActionMode?, view: View?, outRect: AndroidRect?) {
        outRect?.set(contentRect)
      }
    }

  private fun populateMenu(menu: Menu) {
    if (onCopyRequested != null) {
      menu
        .add(Menu.NONE, MENU_ID_COPY, 0, android.R.string.copy)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }
    if (onPasteRequested != null) {
      menu
        .add(Menu.NONE, MENU_ID_PASTE, 1, android.R.string.paste)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }
    if (onCutRequested != null) {
      menu
        .add(Menu.NONE, MENU_ID_CUT, 2, android.R.string.cut)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }
    if (onSelectAllRequested != null) {
      menu
        .add(Menu.NONE, MENU_ID_SELECT_ALL, 3, android.R.string.selectAll)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
    }
  }

  override fun showMenu(
    rect: Rect,
    onCopyRequested: (() -> Unit)?,
    onPasteRequested: (() -> Unit)?,
    onCutRequested: (() -> Unit)?,
    onSelectAllRequested: (() -> Unit)?,
  ) {
    if (!view.isAttachedToWindow || view.windowToken == null) {
      clearLocalState()
      return
    }

    contentRect.set(
      rect.left.roundToInt(),
      rect.top.roundToInt(),
      rect.right.roundToInt(),
      rect.bottom.roundToInt(),
    )
    this.onCopyRequested = onCopyRequested
    this.onPasteRequested = onPasteRequested
    this.onCutRequested = onCutRequested
    this.onSelectAllRequested = onSelectAllRequested

    val hasCopy = onCopyRequested != null
    val hasPaste = onPasteRequested != null
    val hasCut = onCutRequested != null
    val hasSelectAll = onSelectAllRequested != null
    val menuChanged =
      hasCopy != lastHasCopy ||
        hasPaste != lastHasPaste ||
        hasCut != lastHasCut ||
        hasSelectAll != lastHasSelectAll
    lastHasCopy = hasCopy
    lastHasPaste = hasPaste
    lastHasCut = hasCut
    lastHasSelectAll = hasSelectAll

    val existing = actionMode
    if (existing == null) {
      val started = runCatching { view.startActionMode(callback, ActionMode.TYPE_FLOATING) }.getOrNull()
      actionMode = started
      status = if (started != null) TextToolbarStatus.Shown else TextToolbarStatus.Hidden
    } else {
      status = TextToolbarStatus.Shown
      runCatching {
        if (menuChanged) {
          existing.invalidate()
        } else {
          existing.invalidateContentRect()
        }
      }
    }
  }

  override fun hide() {
    val active = actionMode
    actionMode = null
    status = TextToolbarStatus.Hidden
    if (active != null && !isFinishing && view.isAttachedToWindow) {
      isFinishing = true
      try {
        active.finish()
      } catch (_: Exception) {
        // Ignore platform window-teardown races cleanly.
      } finally {
        isFinishing = false
      }
    }
  }

  fun onViewDetached() {
    clearLocalState()
  }

  private fun clearLocalState() {
    actionMode = null
    status = TextToolbarStatus.Hidden
    onCopyRequested = null
    onPasteRequested = null
    onCutRequested = null
    onSelectAllRequested = null
  }
}

@Composable
fun ProvideSafeTextToolbar(content: @Composable () -> Unit) {
  val view = LocalView.current
  val toolbar = remember(view) { PlatformSafeTextToolbar(view) }

  DisposableEffect(view, toolbar) {
    val attachListener =
      object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit

        override fun onViewDetachedFromWindow(v: View) {
          toolbar.onViewDetached()
        }
      }
    view.addOnAttachStateChangeListener(attachListener)
    onDispose {
      view.removeOnAttachStateChangeListener(attachListener)
      toolbar.hide()
    }
  }

  CompositionLocalProvider(LocalTextToolbar provides toolbar, content = content)
}
