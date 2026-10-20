package io.crashbox.passage.store

import java.security.SecureRandom

/** The pass/passage convention: the first line of an entry is the password, the rest are free-form notes. */
object EntryContent {
  fun password(content: String): String = content.substringBefore('\n')

  fun notes(content: String): String = content.substringAfter('\n', "").trimEnd()

  fun of(password: String, notes: String): String {
    require('\n' !in password) { "password must be a single line" }
    val trimmed = notes.trimEnd()
    return if (trimmed.isEmpty()) "$password\n" else "$password\n$trimmed\n"
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
