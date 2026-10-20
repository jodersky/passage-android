package io.crashbox.passage.age

import javax.crypto.AEADBadTagException
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * HPKE (RFC 9180) base mode with DHKEM(P-256, HKDF-SHA256), HKDF-SHA256 and
 * ChaCha20Poly1305, single-shot.
 *
 * Sealing is delegated entirely to BouncyCastle. Opening cannot be, because
 * BouncyCastle needs the raw private key while ours may live in hardware that
 * only exposes the ECDH operation. Opening therefore takes the DH output and
 * implements the remaining parts of RFC 9180 (ExtractAndExpand and the key
 * schedule) on top of BouncyCastle's HKDF and ChaCha20Poly1305. Having two
 * independent implementations lets the tests cross-check them.
 */
internal object Hpke {
  private const val KEM_ID = 0x0010
  private const val KDF_ID = 0x0001
  private const val AEAD_ID = 0x0003
  private const val N_SECRET = 32
  private const val N_K = 32
  private const val N_N = 12
  private const val TAG_BITS = 128
  private const val MODE_BASE: Byte = 0

  private val VERSION = ascii("HPKE-v1")
  private val KEM_SUITE = ascii("KEM") + i2osp(KEM_ID)
  private val HPKE_SUITE = ascii("HPKE") + i2osp(KEM_ID) + i2osp(KDF_ID) + i2osp(AEAD_ID)

  class Sealed(val enc: ByteArray, val ciphertext: ByteArray)

  fun sealBase(pkR: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray): Sealed {
    val hpke = HPKE(HPKE.mode_base, HPKE.kem_P256_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)
    val ctx = hpke.setupBaseS(hpke.deserializePublicKey(pkR), info)
    return Sealed(ctx.encapsulation, ctx.seal(aad, plaintext))
  }

  /**
   * @param dh the raw ECDH output between the recipient's private key and [enc]
   * @param pkR the recipient's public key, uncompressed
   */
  fun openBase(
    dh: ByteArray,
    enc: ByteArray,
    pkR: ByteArray,
    info: ByteArray,
    aad: ByteArray,
    ciphertext: ByteArray
  ): ByteArray {
    val (key, baseNonce) = keySchedule(extractAndExpand(dh, enc + pkR), info)
    return open(key, baseNonce, aad, ciphertext)
  }

  // RFC 9180, section 4.1
  internal fun extractAndExpand(dh: ByteArray, kemContext: ByteArray): ByteArray {
    val prk = labeledExtract(ByteArray(0), KEM_SUITE, "eae_prk", dh)
    return labeledExpand(prk, KEM_SUITE, "shared_secret", kemContext, N_SECRET)
  }

  // RFC 9180, section 5.1, restricted to base mode (empty psk and psk_id)
  // and without the exporter secret. Returns (key, base_nonce).
  internal fun keySchedule(sharedSecret: ByteArray, info: ByteArray): Pair<ByteArray, ByteArray> {
    val pskIdHash = labeledExtract(ByteArray(0), HPKE_SUITE, "psk_id_hash", ByteArray(0))
    val infoHash = labeledExtract(ByteArray(0), HPKE_SUITE, "info_hash", info)
    val context = byteArrayOf(MODE_BASE) + pskIdHash + infoHash
    val secret = labeledExtract(sharedSecret, HPKE_SUITE, "secret", ByteArray(0))
    val key = labeledExpand(secret, HPKE_SUITE, "key", context, N_K)
    val baseNonce = labeledExpand(secret, HPKE_SUITE, "base_nonce", context, N_N)
    return key to baseNonce
  }

  // The first (and only) message of a context uses sequence number 0, so the
  // nonce is the base nonce.
  private fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
    val cipher = ChaCha20Poly1305()
    cipher.init(false, AEADParameters(KeyParameter(key), TAG_BITS, nonce, aad))
    val out = ByteArray(cipher.getOutputSize(ciphertext.size))
    try {
      val n = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
      cipher.doFinal(out, n)
    } catch (e: InvalidCipherTextException) {
      throw AEADBadTagException("HPKE open failed").apply { initCause(e) }
    }
    return out
  }

  private fun labeledExtract(salt: ByteArray, suite: ByteArray, label: String, ikm: ByteArray): ByteArray =
    HKDFBytesGenerator(SHA256Digest()).extractPRK(salt, VERSION + suite + ascii(label) + ikm)

  private fun labeledExpand(prk: ByteArray, suite: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
    val hkdf = HKDFBytesGenerator(SHA256Digest())
    hkdf.init(HKDFParameters.skipExtractParameters(prk, i2osp(length) + VERSION + suite + ascii(label) + info))
    return ByteArray(length).also { hkdf.generateBytes(it, 0, length) }
  }

  private fun i2osp(n: Int): ByteArray = byteArrayOf((n shr 8).toByte(), n.toByte())

  private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
}
