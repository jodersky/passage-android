package io.crashbox.passage.otp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TotpTest {
  // RFC 6238, appendix B: the seeds are ASCII digits, the codes have 8 digits
  private val seeds = mapOf(
    "SHA1" to "12345678901234567890",
    "SHA256" to "12345678901234567890123456789012",
    "SHA512" to "1234567890123456789012345678901234567890123456789012345678901234",
  )
  private val vectors = listOf(
    59L to listOf("94287082", "46119246", "90693936"),
    1111111109L to listOf("07081804", "68084774", "25091201"),
    1111111111L to listOf("14050471", "67062674", "99943326"),
    1234567890L to listOf("89005924", "91819424", "93441116"),
    2000000000L to listOf("69279037", "90698825", "38618901"),
    20000000000L to listOf("65353130", "77737706", "47863826"),
  )

  @Test
  fun rfc6238() {
    for ((i, algorithm) in listOf("SHA1", "SHA256", "SHA512").withIndex()) {
      val totp = Totp(seeds.getValue(algorithm).toByteArray(), algorithm, digits = 8)
      for ((time, codes) in vectors) assertEquals(codes[i], totp.code(time), "$algorithm at $time")
    }
  }

  @Test
  fun parsesUris() {
    // base32 of the SHA1 seed
    val totp = Totp.parse("otpauth://totp/ACME%20Co:john.doe@email.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=ACME%20Co&digits=8")
    assertEquals("94287082", totp.code(59))
    assertEquals("ACME Co", totp.issuer)
    assertEquals("john.doe@email.com", totp.account)
    assertEquals("ACME Co (john.doe@email.com)", totp.label)

    val defaults = Totp.parse("otpauth://totp/totp-secret?secret=gezdgnbvgy3tqojqgezdgnbvgy3tqojq&issuer=totp-secret")
    assertEquals("SHA1", defaults.algorithm)
    assertEquals(6, defaults.digits)
    assertEquals(30, defaults.period)
    assertEquals("287082", defaults.code(59)) // the last 6 digits
    assertEquals("totp-secret", defaults.label)

    val sha256 = Totp.parse("otpauth://totp/x?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA&algorithm=SHA256&digits=8&period=30")
    assertEquals("46119246", sha256.code(59))

    assertEquals("me", Totp.parse("otpauth://totp/me?secret=GEZDGNBV").label)
    assertEquals(1, Totp.parse("otpauth://totp/me?secret=GEZDGNBV&period=60").secondsRemaining(59))
  }

  @Test
  fun rejectsInvalidUris() {
    for ((uri, message) in listOf(
      "https://example.com" to "not an otpauth:// URL",
      "otpauth://hotp/x?secret=GEZDGNBV&counter=0" to "counter-based (HOTP) one-time passwords are not supported",
      "otpauth://totp/x?issuer=a" to "missing secret",
      "otpauth://totp/x?secret=GEZ1" to "invalid secret: '1' is not base32",
      "otpauth://totp/x?secret=GEZDGNBV&algorithm=MD5" to "unsupported algorithm MD5",
      "otpauth://totp/x?secret=GEZDGNBV&digits=4" to "unsupported number of digits 4",
      "otpauth://totp/x?secret=" to "empty secret",
    )) {
      assertEquals(message, assertFailsWith<IllegalArgumentException>(uri) { Totp.parse(uri) }.message, uri)
    }
  }

  @Test
  fun base32() {
    assertContentEquals("foobar".toByteArray(), Totp.base32("MZXW6YTBOI======"))
    assertContentEquals("foobar".toByteArray(), Totp.base32("mzxw 6ytb oi"))
  }
}
