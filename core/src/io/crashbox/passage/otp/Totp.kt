package io.crashbox.passage.otp

import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A time-based one-time password (RFC 6238), as described by an
 * `otpauth://totp/...` URI (https://github.com/google/google-authenticator/wiki/Key-Uri-Format).
 */
class Totp(
  private val secret: ByteArray,
  val algorithm: String = "SHA1",
  val digits: Int = 6,
  val period: Int = 30,
  val issuer: String? = null,
  val account: String? = null,
) {
  init {
    require(algorithm in ALGORITHMS) { "unsupported algorithm $algorithm" }
    require(digits in 6..8) { "unsupported number of digits $digits" }
    require(period > 0) { "invalid period $period" }
    require(secret.isNotEmpty()) { "empty secret" }
  }

  /** The code at a time, in seconds since the epoch. */
  fun code(epochSeconds: Long): String {
    val counter = epochSeconds / period
    val mac = Mac.getInstance(ALGORITHMS.getValue(algorithm))
    mac.init(SecretKeySpec(secret, "RAW"))
    val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
    // dynamic truncation, RFC 4226 section 5.3
    val offset = hash.last().toInt() and 0x0f
    val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
      ((hash[offset + 1].toInt() and 0xff) shl 16) or
      ((hash[offset + 2].toInt() and 0xff) shl 8) or
      (hash[offset + 3].toInt() and 0xff)
    var modulus = 1
    repeat(digits) { modulus *= 10 }
    return (binary % modulus).toString().padStart(digits, '0')
  }

  /** Seconds until the code changes. */
  fun secondsRemaining(epochSeconds: Long): Int = (period - epochSeconds % period).toInt()

  /** A short description, e.g. "GitHub (jakob)". */
  val label: String
    get() = when {
      issuer == null || account == null || issuer == account -> issuer ?: account ?: "TOTP"
      else -> "$issuer ($account)"
    }

  companion object {
    private val ALGORITHMS = mapOf("SHA1" to "HmacSHA1", "SHA256" to "HmacSHA256", "SHA512" to "HmacSHA512")

    fun isOtpUri(line: String): Boolean = line.trim().startsWith("otpauth://", ignoreCase = true)

    /** @throws IllegalArgumentException with a message for the user if [uri] is not a valid TOTP URI */
    fun parse(uri: String): Totp {
      val parsed = try {
        URI(uri.trim())
      } catch (e: Exception) {
        throw IllegalArgumentException("not a valid URL")
      }
      require(parsed.scheme.equals("otpauth", ignoreCase = true)) { "not an otpauth:// URL" }
      require(parsed.authority.equals("totp", ignoreCase = true)) {
        if (parsed.authority.equals("hotp", ignoreCase = true)) "counter-based (HOTP) one-time passwords are not supported"
        else "not a TOTP URL"
      }
      val params = (parsed.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.associate {
        val (key, value) = (it.split('=', limit = 2) + "").take(2)
        decode(key).lowercase() to decode(value)
      }
      // label: "Issuer:account" or "account"
      val label = decode(parsed.rawPath.removePrefix("/"))
      val labelIssuer = label.substringBefore(':', "").trim().ifEmpty { null }
      val account = label.substringAfter(':').trim().ifEmpty { null }
      return Totp(
        secret = base32(params["secret"] ?: throw IllegalArgumentException("missing secret")),
        algorithm = (params["algorithm"] ?: "SHA1").uppercase(),
        digits = params["digits"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("invalid digits") } ?: 6,
        period = params["period"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("invalid period") } ?: 30,
        issuer = params["issuer"]?.ifEmpty { null } ?: labelIssuer,
        account = account,
      )
    }

    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)

    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** RFC 4648 base32, ignoring case, spaces and padding. */
    internal fun base32(encoded: String): ByteArray {
      val chars = encoded.uppercase().filter { it != ' ' && it != '=' && it != '-' }
      val out = java.io.ByteArrayOutputStream()
      var buffer = 0
      var bits = 0
      for (c in chars) {
        val value = BASE32.indexOf(c)
        require(value >= 0) { "invalid secret: '$c' is not base32" }
        buffer = (buffer shl 5) or value
        bits += 5
        if (bits >= 8) {
          out.write((buffer shr (bits - 8)) and 0xff)
          bits -= 8
        }
      }
      return out.toByteArray()
    }
  }
}
