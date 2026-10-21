package io.crashbox.passage.store

import io.crashbox.passage.otp.Totp
import java.security.SecureRandom

/**
 * The content of an entry, following the pass/passage conventions: the first
 * line is the password, the rest are free-form notes. Like pass-otp, the
 * first `otpauth://` line anywhere is a one-time password; if it is the first
 * line, the entry has no password.
 */
class EntryContent(
  /** empty if the entry has none */
  val password: String,
  /** the notes, without the otpauth line */
  val notes: String,
  /** the otpauth:// URI, if any */
  val otp: String?,
) {
  init {
    require('\n' !in password) { "password must be a single line" }
    require(otp == null || ('\n' !in otp && Totp.isOtpUri(otp))) { "invalid otpauth line" }
    require(!Totp.isOtpUri(password)) { "the password must not be an otpauth URI" }
  }

  /**
   * Back to the stored form. Without a password, the otpauth line comes first
   * (a pass-otp entry); otherwise it comes after the notes.
   */
  override fun toString(): String {
    val notes = notes.trimEnd()
    val lines = if (password.isEmpty() && otp != null) listOf(otp) + notes.lines()
    else listOf(password) + notes.lines() + listOfNotNull(otp)
    return lines.joinToString("\n").trimEnd() + "\n"
  }

  companion object {
    fun parse(content: String): EntryContent {
      val lines = content.lines()
      val otpIndex = lines.indexOfFirst(Totp::isOtpUri)
      val password = if (otpIndex == 0) "" else lines.first()
      val notes = lines.drop(1).filterIndexed { i, _ -> i + 1 != otpIndex }.joinToString("\n").trimEnd()
      return EntryContent(password, notes, lines.getOrNull(otpIndex)?.trim())
    }
  }
}

object PasswordGenerator {
  private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  // [:punct:] in the C locale, as used by `passage generate`
  private const val PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
  private val random = SecureRandom()

  /** Same defaults as `passage generate`: 25 characters from [:punct:][:alnum:]. */
  fun generate(length: Int = 25, symbols: Boolean = true): String {
    val chars = if (symbols) ALNUM + PUNCT else ALNUM
    return String(CharArray(length) { chars[random.nextInt(chars.length)] })
  }
}
