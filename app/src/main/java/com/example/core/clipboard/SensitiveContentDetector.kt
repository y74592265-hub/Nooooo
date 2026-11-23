package com.example.core.clipboard

import javax.inject.Inject
import javax.inject.Singleton

data class SensitiveAnalysis(
  val isSensitive: Boolean,
  val reason: String? = null,
)

/**
 * Classifies high-risk clipboard strings (OTPs, verification codes, passwords, API tokens,
 * private keys, payment card numbers) locally so instant auto-capture can protect them cleanly.
 */
@Singleton
class SensitiveContentDetector @Inject constructor() {

  private val privateKeyRegex = Regex("""-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----""")
  private val apiTokenRegex =
    Regex(
      """\b(sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AIza[0-9A-Za-z\-_]{30,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,})\b"""
    )
  private val botTokenRegex = Regex("""\b\d{8,11}:[A-Za-z0-9_-]{30,}\b""")
  private val otpStandaloneRegex = Regex("""^\d{4,8}$""")
  private val otpWithContextRegex =
    Regex(
      """(?i)(otp|one[\s-]?time|verification|verify|passcode|pin\s*code|2fa|رمز\s*التحقق|كود\s*التحقق|رمز\s*التأكيد|كود\s*تأكيد)(?:[\p{L}\s:=\-]{0,28})(\d{4,8})\b|\b(\d{4,8})[\s\w]{0,20}(otp|verification|code|رمز\s*التحقق)"""
    )
  private val passwordContextRegex =
    Regex("""(?i)(password|passwd|pwd|secret_key|كلمة\s*المرور|كلمة\s*السر)\s*[:=]\s*\S{4,}""")
  private val cardNumberRegex = Regex("""^(?:\d[ -]?){13,19}$""")

  fun analyze(content: String, systemSensitiveFlag: Boolean = false): SensitiveAnalysis {
    if (systemSensitiveFlag) {
      return SensitiveAnalysis(isSensitive = true, reason = "System sensitive clipboard flag")
    }
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return SensitiveAnalysis(false)

    if (privateKeyRegex.containsMatchIn(trimmed)) {
      return SensitiveAnalysis(true, "Private key detected")
    }
    if (apiTokenRegex.containsMatchIn(trimmed) || botTokenRegex.containsMatchIn(trimmed)) {
      return SensitiveAnalysis(true, "API or Bot token detected")
    }
    if (passwordContextRegex.containsMatchIn(trimmed)) {
      return SensitiveAnalysis(true, "Password credential detected")
    }
    if (otpWithContextRegex.containsMatchIn(trimmed) || otpStandaloneRegex.matches(trimmed)) {
      return SensitiveAnalysis(true, "OTP / verification code detected")
    }
    val digitsOnly = trimmed.filter(Char::isDigit)
    if (cardNumberRegex.matches(trimmed) && digitsOnly.length in 13..19 && passesLuhn(digitsOnly)) {
      return SensitiveAnalysis(true, "Payment card number detected")
    }
    return SensitiveAnalysis(false)
  }

  private fun passesLuhn(digits: String): Boolean {
    var sum = 0
    var alternate = false
    for (i in digits.length - 1 downTo 0) {
      var n = digits[i] - '0'
      if (alternate) {
        n *= 2
        if (n > 9) n = (n % 10) + 1
      }
      sum += n
      alternate = !alternate
    }
    return sum % 10 == 0
  }
}
