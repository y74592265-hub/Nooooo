package com.example.core.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.example.domain.model.FieldContextSignal
import com.example.domain.model.FieldIntent

/**
 * Stateless Context Detection Engine translating Android EditorInfo into a clean domain signal.
 * Never persists EditorInfo or targetPackage to disk.
 */
object ContextDetectionEngine {

  fun analyze(info: EditorInfo?): FieldContextSignal {
    if (info == null) return FieldContextSignal()

    val inputType = info.inputType
    val inputClass = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    val flags = inputType and InputType.TYPE_MASK_FLAGS

    val isPasswordText =
      inputClass == InputType.TYPE_CLASS_TEXT &&
        (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
          variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)

    val isPasswordNumber =
      inputClass == InputType.TYPE_CLASS_NUMBER &&
        variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

    val isSecretField = isPasswordText || isPasswordNumber

    val noPersonalizedLearning =
      (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    val isMultiline =
      inputClass == InputType.TYPE_CLASS_TEXT &&
        ((flags and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
          (flags and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0)

    val imeAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
    val intent =
      when {
        isSecretField -> FieldIntent.GENERAL
        inputClass == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI ->
          FieldIntent.URL
        inputClass == InputType.TYPE_CLASS_TEXT &&
          (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> FieldIntent.EMAIL
        inputClass == InputType.TYPE_CLASS_PHONE || inputClass == InputType.TYPE_CLASS_NUMBER ->
          FieldIntent.PHONE_OR_NUMBER
        imeAction == EditorInfo.IME_ACTION_SEARCH -> FieldIntent.SEARCH
        isMultiline -> FieldIntent.CODE_OR_MULTILINE
        else -> FieldIntent.GENERAL
      }

    return FieldContextSignal(
      intent = intent,
      isSecretField = isSecretField,
      noPersonalizedLearning = noPersonalizedLearning,
      isMultiline = isMultiline,
      targetPackage = info.packageName,
    )
  }
}
