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
import com.example.core.preferences.AppPreferencesRepository
import com.example.core.security.IntelligencePrivacyGate
import com.example.data.local.ClipDao
import com.example.data.local.ClipTransitionDao
import com.example.domain.intelligence.ClipPredictionEngine
import com.example.domain.intelligence.ClipTemplateEngine
import com.example.domain.intelligence.UsageLearningEngine
import com.example.domain.model.ClipContentType
import com.example.domain.model.ClipSource
import com.example.domain.model.ClipTransitionEdge
import com.example.domain.model.FieldContextSignal
import com.example.domain.model.FieldIntent
import com.example.domain.model.ImeClipCandidate
import com.example.domain.model.PredictedClipChip
import com.example.domain.model.PredictionReason
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adaptive Field-Aware Companion Clipboard Keyboard Service:
 * - Analyzes active EditorInfo to detect URL, Email, Phone, Code/Multiline, Search, or Secret fields.
 * - Enforces Password Shield: hides all chips, pauses clipboard capture, and suspends learning on passwords/PINs.
 * - Uses SQL-level projection (`getImeCandidateProjections`) so sensitive ciphertext never touches IME memory,
 *   fetching full text by ID strictly on tap.
 * - Integrates Next-Clip Predictive Engine & inline filter tabs (Smart, Links, Code, Pinned).
 */
class SmartClipboardImeService : InputMethodService() {

  enum class ImeFilterTab {
    ALL,
    LINKS,
    CODE,
    PINNED,
  }

  @EntryPoint
  @InstallIn(SingletonComponent::class)
  interface SmartClipboardImeEntryPoint {
    fun clipboardHelper(): SystemClipboardHelper
    fun captureCoordinator(): ClipboardCaptureCoordinator
    fun preferencesRepository(): AppPreferencesRepository
    fun clipDao(): ClipDao
    fun clipTransitionDao(): ClipTransitionDao
    fun usageLearningEngine(): UsageLearningEngine
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var refreshJob: Job? = null
  private var primaryClipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
  private var chipsContainer: LinearLayout? = null
  private var filterRow: LinearLayout? = null
  private var statusLabel: TextView? = null

  private var capturedInSession = 0
  private var isInputBarVisible = false
  private var currentFilter = ImeFilterTab.ALL
  private var activeSignal = FieldContextSignal()

  private val entryPoint: SmartClipboardImeEntryPoint by lazy {
    EntryPointAccessors.fromApplication(applicationContext, SmartClipboardImeEntryPoint::class.java)
  }

  override fun onCreate() {
    super.onCreate()
    serviceScope.launch(Dispatchers.IO) {
      // Keep AppPreferencesRepository.latestSettingsSnapshot warm throughout IME lifetime
      runCatching { entryPoint.preferencesRepository().userSettings.collect() }
    }
    primaryClipListener =
      entryPoint.clipboardHelper().registerPrimaryClipListener {
        val signal = ContextDetectionEngine.analyze(currentInputEditorInfo)
        val settings = entryPoint.preferencesRepository().latestSettingsSnapshot
        val decision = IntelligencePrivacyGate.evaluateImeField(signal, settings)
        if (!decision.canCaptureFromIme) return@registerPrimaryClipListener

        val snapshots = entryPoint.clipboardHelper().readAllClipboardSnapshots()
        captureSnapshots(
          snapshots = snapshots,
          packageHint = currentInputEditorInfo?.packageName,
          toastIfHidden = true,
        )
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
    isInputBarVisible = true
    activeSignal = ContextDetectionEngine.analyze(info)
    serviceScope.launch {
      val settings =
        withContext(Dispatchers.IO) {
          runCatching { entryPoint.preferencesRepository().userSettings.first() }
            .getOrDefault(entryPoint.preferencesRepository().latestSettingsSnapshot)
        }
      val decision = IntelligencePrivacyGate.evaluateImeField(activeSignal, settings)
      if (decision.canCaptureFromIme) {
        val snapshots = entryPoint.clipboardHelper().readAllClipboardSnapshots()
        captureSnapshots(snapshots = snapshots, packageHint = info?.packageName, toastIfHidden = false)
      }
      loadPredictedClips()
    }
  }

  override fun onWindowShown() {
    super.onWindowShown()
    isInputBarVisible = true
    activeSignal = ContextDetectionEngine.analyze(currentInputEditorInfo)
    val settings = entryPoint.preferencesRepository().latestSettingsSnapshot
    val decision = IntelligencePrivacyGate.evaluateImeField(activeSignal, settings)
    if (decision.canCaptureFromIme) {
      val snapshots = entryPoint.clipboardHelper().readAllClipboardSnapshots()
      captureSnapshots(
        snapshots = snapshots,
        packageHint = currentInputEditorInfo?.packageName,
        toastIfHidden = false,
      )
    }
    loadPredictedClips()
  }

  override fun onFinishInputView(finishingInput: Boolean) {
    isInputBarVisible = false
    super.onFinishInputView(finishingInput)
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
        val signal = ContextDetectionEngine.analyze(currentInputEditorInfo)
        val settings = entryPoint.preferencesRepository().latestSettingsSnapshot
        val decision = IntelligencePrivacyGate.evaluateImeField(signal, settings)
        if (!decision.canCaptureFromIme) {
          renderPasswordShield()
          return@buildActionChip
        }
        val snapshots = entryPoint.clipboardHelper().readAllClipboardSnapshots()
        captureSnapshots(
          snapshots = snapshots,
          packageHint = currentInputEditorInfo?.packageName,
          toastIfHidden = false,
        )
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
    headerRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
    headerRow.addView(switchKeyboardBtn)

    val statusText =
      TextView(this).apply {
        text = getString(R.string.ime_bar_hint)
        setTextColor(0xFF94A3B8.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(4), 0, dp(6))
      }
    statusLabel = statusText

    val filtersContainer =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, dp(8))
      }
    filterRow = filtersContainer
    renderFilterTabs()

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
    root.addView(filtersContainer)
    root.addView(scroller)

    activeSignal = ContextDetectionEngine.analyze(currentInputEditorInfo)
    loadPredictedClips()
    return root
  }

  private fun renderFilterTabs() {
    val row = filterRow ?: return
    row.removeAllViews()
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    val tabs =
      listOf(
        ImeFilterTab.ALL to R.string.ime_filter_all,
        ImeFilterTab.LINKS to R.string.ime_filter_links,
        ImeFilterTab.CODE to R.string.ime_filter_code,
        ImeFilterTab.PINNED to R.string.ime_filter_pinned,
      )
    for ((tab, labelRes) in tabs) {
      val selected = tab == currentFilter
      val tv =
        TextView(this).apply {
          text = getString(labelRes)
          setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFCBD5E1.toInt())
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
          setPadding(dp(10), dp(5), dp(10), dp(5))
          background =
            GradientDrawable().apply {
              setColor(if (selected) 0xFF2563EB.toInt() else 0xFF1E293B.toInt())
              cornerRadius = dp(12).toFloat()
            }
          layoutParams =
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
          setOnClickListener {
            if (currentFilter != tab) {
              currentFilter = tab
              renderFilterTabs()
              loadPredictedClips()
            }
          }
        }
      row.addView(tv)
    }
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

  private fun captureSnapshots(
    snapshots: List<com.example.core.clipboard.SystemClipboardSnapshot>,
    packageHint: String?,
    toastIfHidden: Boolean,
  ) {
    if (snapshots.isEmpty()) return
    serviceScope.launch {
      val outcomes =
        runCatching {
            entryPoint
              .captureCoordinator()
              .captureAllFromSystemClipboard(
                source = ClipSource.IME,
                foregroundPackageHint = packageHint,
                overrideSnapshots = snapshots,
                allowWhenAutoCaptureDisabled = true,
              )
          }
          .getOrDefault(emptyList())

      val saved = outcomes.filterIsInstance<com.example.core.clipboard.CaptureOutcome.Saved>()
      if (saved.isNotEmpty()) {
        capturedInSession += saved.size
        val latest = saved.last()
        val statusMsg =
          if (latest.autoMarkedSensitive) {
            getString(R.string.ime_captured_sensitive_status, capturedInSession)
          } else {
            val preview =
              latest.clip.content.replace("\n", " ").trim().let {
                if (it.length > 26) "${it.take(26)}…" else it
              }
            getString(R.string.ime_captured_status, preview, capturedInSession)
          }
        statusLabel?.text = statusMsg
        if (toastIfHidden && !isInputBarVisible) {
          android.widget.Toast.makeText(applicationContext, statusMsg, android.widget.Toast.LENGTH_SHORT).show()
        }
      }
      loadPredictedClips()
    }
  }

  private fun loadPredictedClips() {
    val signal = activeSignal
    val settings = entryPoint.preferencesRepository().latestSettingsSnapshot
    val decision = IntelligencePrivacyGate.evaluateImeField(signal, settings)
    if (!decision.canShowImeChips) {
      refreshJob?.cancel()
      renderPasswordShield()
      return
    }

    refreshJob?.cancel()
    refreshJob =
      serviceScope.launch {
        val ranked =
          withContext(Dispatchers.IO) {
            runCatching {
              val rows = entryPoint.clipDao().getImeCandidateProjections(limit = 30)
              val filteredRows =
                when (currentFilter) {
                  ImeFilterTab.ALL -> rows
                  ImeFilterTab.LINKS -> rows.filter { it.contentType == ClipContentType.LINK }
                  ImeFilterTab.CODE -> rows.filter { it.contentType == ClipContentType.CODE }
                  ImeFilterTab.PINNED -> rows.filter { it.isPinned }
                }
              val candidates =
                filteredRows.map {
                  ImeClipCandidate(
                    id = it.id,
                    title = it.title,
                    preview = it.preview,
                    contentType = it.contentType,
                    categoryId = it.categoryId,
                    isPinned = it.isPinned,
                    isFavorite = it.isFavorite,
                    useCount = it.useCount,
                    lastPastedAt = it.lastPastedAt,
                    updatedAt = it.updatedAt,
                    versionNumber = it.versionNumber,
                  )
                }
              val lastAnchor = entryPoint.usageLearningEngine().currentLastUsedClipId()
              val transitions: List<ClipTransitionEdge> =
                if (lastAnchor != null && settings.usageLearningEnabled) {
                  entryPoint.clipTransitionDao().getTopTransitionsFrom(lastAnchor, 6).map {
                    ClipTransitionEdge(
                      fromClipId = it.fromClipId,
                      toClipId = it.toClipId,
                      weight = it.weight,
                      hitCount = it.hitCount,
                      lastTriggeredAt = it.lastTriggeredAt,
                    )
                  }
                } else {
                  emptyList()
                }
              ClipPredictionEngine.rank(
                candidates = candidates,
                signal = signal,
                lastUsedClipId = lastAnchor,
                transitionsFromLast = transitions,
                settings = settings,
                limit = 10,
              )
            }
              .getOrDefault(emptyList())
          }
        renderPredictedChips(ranked, signal)
      }
  }

  private fun renderPasswordShield() {
    filterRow?.visibility = View.GONE
    val container = chipsContainer ?: return
    container.removeAllViews()
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    statusLabel?.text = getString(R.string.ime_shield_hint)
    statusLabel?.setTextColor(0xFFFBBF24.toInt())

    val badge =
      TextView(this).apply {
        text = getString(R.string.ime_shield_title)
        setTextColor(0xFFFDE68A.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background =
          GradientDrawable().apply {
            setColor(0xFF451A03.toInt())
            setStroke(dp(1), 0xFFB45309.toInt())
            cornerRadius = dp(12).toFloat()
          }
      }
    container.addView(badge)
  }

  private fun renderPredictedChips(chips: List<PredictedClipChip>, signal: FieldContextSignal) {
    filterRow?.visibility = View.VISIBLE
    statusLabel?.setTextColor(0xFF94A3B8.toInt())
    val container = chipsContainer ?: return
    container.removeAllViews()
    val density = resources.displayMetrics.density
    fun dp(v: Int): Int = (v * density).toInt()

    if (chips.isEmpty()) {
      statusLabel?.text = getString(R.string.ime_bar_empty)
      return
    }
    if (capturedInSession == 0) {
      statusLabel?.text =
        when (signal.intent) {
          FieldIntent.URL -> getString(R.string.ime_context_url)
          FieldIntent.EMAIL -> getString(R.string.ime_context_email)
          FieldIntent.PHONE_OR_NUMBER -> getString(R.string.ime_context_phone)
          FieldIntent.CODE_OR_MULTILINE -> getString(R.string.ime_context_code)
          FieldIntent.SEARCH,
          FieldIntent.GENERAL -> getString(R.string.ime_bar_hint)
        }
    }

    for (item in chips) {
      val candidate = item.candidate
      val baseLabel = candidate.title.ifBlank { candidate.preview }.replace("\n", " ").trim()
      val prefix =
        when (item.reason) {
          PredictionReason.NEXT_IN_SEQUENCE -> "[${getString(R.string.ime_reason_next)}] "
          PredictionReason.MATCHES_FIELD -> "[${getString(R.string.ime_reason_match)}] "
          PredictionReason.PINNED -> "[${getString(R.string.ime_reason_pin)}] "
          PredictionReason.FREQUENT,
          PredictionReason.RECENT -> ""
        }
      val versionSuffix = if (candidate.versionNumber > 1) " (v${candidate.versionNumber})" else ""
      val label = "$prefix$baseLabel$versionSuffix"

      val chip =
        TextView(this).apply {
          text = label
          maxLines = 1
          maxEms = 16
          ellipsize = TextUtils.TruncateAt.END
          setTextColor(0xFFE2E8F0.toInt())
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
          setPadding(dp(14), dp(10), dp(14), dp(10))
          background =
            GradientDrawable().apply {
              val strokeColor =
                when (item.reason) {
                  PredictionReason.NEXT_IN_SEQUENCE -> 0xFF38BDF8.toInt()
                  PredictionReason.MATCHES_FIELD -> 0xFF34D399.toInt()
                  else -> 0xFF334155.toInt()
                }
              setColor(0xFF1E293B.toInt())
              setStroke(dp(1), strokeColor)
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
            serviceScope.launch {
              val fullContent =
                withContext(Dispatchers.IO) {
                  entryPoint.clipDao().getPasteableContentById(candidate.id)
                }
              if (!fullContent.isNullOrEmpty()) {
                val textToCommit =
                  if (ClipTemplateEngine.hasTemplateTokens(fullContent)) {
                    ClipTemplateEngine.expand(fullContent)
                  } else {
                    fullContent
                  }
                runCatching { currentInputConnection?.commitText(textToCommit, 1) }
                entryPoint.usageLearningEngine().recordUsage(
                  clipId = candidate.id,
                  isSensitive = false,
                  signal = signal,
                )
              }
            }
          }
        }
      container.addView(chip)
    }
  }
}
