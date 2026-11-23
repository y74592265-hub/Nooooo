package com.example.core.ime

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.R
import com.example.core.clipboard.ClipboardCaptureCoordinator
import com.example.core.clipboard.SystemClipboardHelper
import com.example.domain.model.ClipItem
import com.example.domain.model.ClipSource
import com.example.domain.repository.ClipRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Optional, disabled-by-default Companion Clipboard Keyboard Service (Android 10+ opt-in IME).
 * When enabled intentionally by the user in Android System Settings -> Keyboards, it receives IME
 * focus in third-party apps so newly copied snippets are captured automatically into Smart Clipboard
 * and recent non-sensitive clips can be pasted directly into active input fields.
 */
class SmartClipboardImeService : InputMethodService() {

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface SmartClipboardImeEntryPoint {
    fun clipboardHelper(): SystemClipboardHelper
    fun captureCoordinator(): ClipboardCaptureCoordinator
    fun clipRepository(): ClipRepository
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var refreshJob: Job? = null
  private var primaryClipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
  private var chipsContainer: LinearLayout? = null
  private var statusLabel: TextView? = null

  private val entryPoint: SmartClipboardImeEntryPoint by lazy {
    EntryPointAccessors.fromApplication(applicationContext, SmartClipboardImeEntryPoint::class.java)
  }

  override fun onCreate() {
    super.onCreate()
    primaryClipListener =
      entryPoint.clipboardHelper().registerPrimaryClipListener {
        captureCurrentClipboard()
      }
  }

  override fun onDestroy() {
    primaryClipListener?.let { entryPoint.clipboardHelper().unregisterPrimaryClipListener(it) }
    primaryClipListener = null
    serviceScope.cancel()
    super.onDestroy()
  }

  override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    captureCurrentClipboard(info?.packageName)
    loadRecentClips()
  }

  override fun onCreateInputView(): View {
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    val root =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(14))
        setBackgroundColor(0xFF0F172A.toInt())
      }

    val headerRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }

    val titleView =
      TextView(this).apply {
        text = getString(R.string.ime_bar_title)
        setTextColor(0xFFF8FAFC.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      }

    val captureNowBtn =
      buildActionChip(getString(R.string.action_paste_from_clipboard), 0xFF1E3A8A.toInt()) {
        captureCurrentClipboard(currentInputEditorInfo?.packageName)
      }

    val switchKeyboardBtn =
      buildActionChip(getString(R.string.ime_switch_keyboard), 0xFF334155.toInt()) {
        runCatching {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
          } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showInputMethodPicker()
          }
        }
      }

    headerRow.addView(titleView)
    headerRow.addView(captureNowBtn)
    headerRow.addView(
      View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) }
    )
    headerRow.addView(switchKeyboardBtn)

    val statusText =
      TextView(this).apply {
        text = getString(R.string.ime_bar_hint)
        setTextColor(0xFF94A3B8.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(4), 0, dp(8))
      }
    statusLabel = statusText

    val chipsRow =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
      }
    chipsContainer = chipsRow

    val scroller =
      HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(chipsRow)
      }

    root.addView(headerRow)
    root.addView(statusText)
    root.addView(scroller)

    loadRecentClips()
    return root
  }

  private fun buildActionChip(label: String, bgColor: Int, onClick: () -> Unit): TextView {
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()
    return TextView(this).apply {
      text = label
      setTextColor(0xFFFFFFFF.toInt())
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
      setPadding(dp(12), dp(8), dp(12), dp(8))
      background =
        GradientDrawable().apply {
          setColor(bgColor)
          cornerRadius = dp(18).toFloat()
        }
      setOnClickListener { onClick() }
    }
  }

  private fun captureCurrentClipboard(packageHint: String? = null) {
    serviceScope.launch {
      runCatching {
        entryPoint
          .captureCoordinator()
          .captureFromSystemClipboard(
            source = ClipSource.IME,
            foregroundPackageHint = packageHint,
          )
      }
      loadRecentClips()
    }
  }

  private fun loadRecentClips() {
    refreshJob?.cancel()
    refreshJob =
      serviceScope.launch {
        val recent =
          withContext(Dispatchers.IO) {
            runCatching { entryPoint.clipRepository().getActiveClipsPaged(limit = 8, offset = 0) }
              .getOrDefault(emptyList())
          }
        renderRecentChips(recent)
      }
  }

  private fun renderRecentChips(clips: List<ClipItem>) {
    val container = chipsContainer ?: return
    container.removeAllViews()
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    val pasteable = clips.filter { !it.isSensitive }.take(8)
    if (pasteable.isEmpty()) {
      statusLabel?.text = getString(R.string.ime_bar_empty)
      return
    }
    statusLabel?.text = getString(R.string.ime_bar_hint)

    for (clip in pasteable) {
      val label = clip.title.ifBlank { clip.content }.replace("\n", " ").trim()
      val chip =
        TextView(this).apply {
          text = label
          maxLines = 1
          maxEms = 14
          ellipsize = TextUtils.TruncateAt.END
          setTextColor(0xFFE2E8F0.toInt())
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
          setPadding(dp(14), dp(10), dp(14), dp(10))
          background =
            GradientDrawable().apply {
              setColor(0xFF1E293B.toInt())
              setStroke(dp(1), 0xFF334155.toInt())
              cornerRadius = dp(14).toFloat()
            }
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
              marginEnd = dp(8)
            }
          setOnClickListener {
            runCatching {
              currentInputConnection?.commitText(clip.content, 1)
            }
          }
        }
      container.addView(chip)
    }
  }
}
