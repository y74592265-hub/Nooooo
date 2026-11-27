package com.example.core.security

import com.example.core.preferences.UserSettings
import com.example.domain.model.FieldContextSignal

data class PrivacyPolicyDecision(
  val canShowImeChips: Boolean,
  val canCaptureFromIme: Boolean,
  val canLearnUsage: Boolean,
  val canGroupLineage: Boolean,
)

/**
 * Mandatory Privacy & Security Gatekeeper enforcing structural invariants across IME,
 * Usage Learning, and Smart Lineage:
 * - Secret fields (passwords/PINs) and IME_FLAG_NO_PERSONALIZED_LEARNING block chips, capture & learning.
 * - Sensitive clips (isSensitive=true) and excluded package rules block learning and lineage completely.
 */
object IntelligencePrivacyGate {

  fun evaluateImeField(
    signal: FieldContextSignal,
    settings: UserSettings,
  ): PrivacyPolicyDecision {
    val pkgExcluded = isPackageExcluded(signal.targetPackage, settings.excludedSourceAppsCsv)
    val secretOrPrivate = signal.isSecretField || signal.noPersonalizedLearning || pkgExcluded
    return PrivacyPolicyDecision(
      canShowImeChips = !signal.isSecretField && !pkgExcluded,
      canCaptureFromIme = !secretOrPrivate,
      canLearnUsage = !secretOrPrivate && settings.usageLearningEnabled,
      canGroupLineage = !secretOrPrivate && settings.smartLineageEnabled,
    )
  }

  fun canLearnClipUsage(
    isSensitive: Boolean,
    signal: FieldContextSignal? = null,
    settings: UserSettings,
  ): Boolean {
    if (isSensitive || !settings.usageLearningEnabled) return false
    if (signal != null) {
      if (signal.isSecretField || signal.noPersonalizedLearning) return false
      if (isPackageExcluded(signal.targetPackage, settings.excludedSourceAppsCsv)) return false
    }
    return true
  }

  fun canGroupLineage(isSensitive: Boolean, settings: UserSettings): Boolean =
    !isSensitive && settings.smartLineageEnabled

  fun isPackageExcluded(packageName: String?, excludedAppsCsv: String): Boolean {
    if (packageName.isNullOrBlank() || excludedAppsCsv.isBlank()) return false
    val pkgLower = packageName.lowercase()
    return excludedAppsCsv
      .split(',')
      .asSequence()
      .map { it.trim().lowercase() }
      .filter { it.isNotEmpty() }
      .any { rule -> pkgLower.contains(rule) }
  }
}
