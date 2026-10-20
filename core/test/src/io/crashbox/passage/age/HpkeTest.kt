package io.crashbox.passage.age

import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals

class HpkeTest {
  // RFC 9180 test vector A.5.1: DHKEM(P-256, HKDF-SHA256), HKDF-SHA256,
  // ChaCha20Poly1305, base mode
  // https://github.com/cfrg/draft-irtf-cfrg-hpke/blob/master/test-vectors.json
  private val info = hex("4f6465206f6e2061204772656369616e2055726e")
  private val skRm = hex("a4d1c55836aa30f9b3fbb6ac98d338c877c2867dd3a77396d13f68d3ab150d3b")
  private val pkRm = hex(
    "04a697bffde9405c992883c5c439d6cc358170b51af72812333b015621dc0f40ba" +
      "d9bb726f68a5c013806a790ec716ab8669f84f6b694596c2987cf35baba2a006"
  )
  private val enc = hex(
    "04c07836a0206e04e31d8ae99bfd549380b072a1b1b82e563c935c095827824fc1" +
      "559eac6fb9e3c70cd3193968994e7fe9781aa103f5b50e934b5b2f387e381291"
  )
  private val sharedSecret = hex("806520f82ef0b03c823b7fc524b6b55a088f566b9751b89551c170f4113bd850")
  private val key = hex("a8f45490a92a3b04d1dbf6cf2c3939ad8bfc9bfcb97c04bffe116730c9dfe3fc")
  private val baseNonce = hex("726b4390ed2209809f58c693")
  private val aad = hex("436f756e742d30")
  private val pt = hex("4265617574792069732074727574682c20747275746820626561757479")
  private val ct = hex("6469c41c5c81d3aa85432531ecf6460ec945bde1eb428cb2fedf7a29f5a685b4ccb0d057f03ea2952a27bb458b")

  private val agreement = JcaP256KeyAgreement(
    KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, skRm), P256.jcaParameters)),
    P256.toJcaPublicKey(pkRm)
  )

  private fun dh() = agreement.agree(P256.toJcaPublicKey(enc))

  @Test
  fun extractAndExpand() {
    assertContentEquals(sharedSecret, Hpke.extractAndExpand(dh(), enc + pkRm))
  }

  @Test
  fun keySchedule() {
    val (k, n) = Hpke.keySchedule(sharedSecret, info)
    assertContentEquals(key, k)
    assertContentEquals(baseNonce, n)
  }

  @Test
  fun open() {
    assertContentEquals(pt, Hpke.openBase(dh(), enc, pkRm, info, aad, ct))
  }

  @Test
  fun sealThenOpen() {
    // sealing is BouncyCastle's implementation, opening is ours
    val sealed = Hpke.sealBase(pkRm, info, aad, pt)
    val dh = agreement.agree(P256.toJcaPublicKey(sealed.enc))
    assertContentEquals(pt, Hpke.openBase(dh, sealed.enc, pkRm, info, aad, sealed.ciphertext))
  }

  @Test
  fun publicKeyEncoding() {
    val jca: ECPublicKey = P256.toJcaPublicKey(pkRm)
    assertContentEquals(pkRm, P256.encode(jca, compressed = false))
    assertContentEquals(pkRm, P256.uncompress(P256.compress(pkRm)))
  }
}

internal fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
