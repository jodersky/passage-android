package io.crashbox.passage.age

import com.exceptionfactory.jagged.FileKey
import com.exceptionfactory.jagged.RecipientStanza
import com.exceptionfactory.jagged.RecipientStanzaReader
import com.exceptionfactory.jagged.RecipientStanzaWriter
import com.exceptionfactory.jagged.UnsupportedRecipientStanzaException
import com.exceptionfactory.jagged.bech32.Bech32
import com.exceptionfactory.jagged.framework.codec.CanonicalBase64
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import javax.crypto.KeyAgreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator

/*
 * The age p256tag recipient type, designed for hardware keys.
 * Spec: https://c2sp.org/age#the-tagged-recipient-types
 */

private const val STANZA_TYPE = "p256tag"
private const val HRP = "age1tag"
private const val TAG_LENGTH = 4
private const val BODY_LENGTH = 32
private val LABEL = "age-encryption.org/p256tag".toByteArray(Charsets.US_ASCII)

/** An `age1tag1...` recipient: a P-256 public key. */
class P256TagRecipient private constructor(
  /** SEC 1 compressed point */
  private val compressed: ByteArray
) {
  internal val uncompressed: ByteArray = P256.uncompress(compressed)

  override fun toString(): String = Bech32.getEncoder().encode(HRP, compressed).toString()

  override fun equals(other: Any?): Boolean =
    other is P256TagRecipient && compressed.contentEquals(other.compressed)

  override fun hashCode(): Int = compressed.contentHashCode()

  /**
   * The stanza tag: lets an identity check if a stanza is addressed to it
   * without performing the (possibly user-gated) ECDH.
   */
  internal fun tag(enc: ByteArray): ByteArray {
    val recipientHash = MessageDigest.getInstance("SHA-256").digest(compressed).copyOf(TAG_LENGTH)
    return HKDFBytesGenerator(SHA256Digest()).extractPRK(LABEL, enc + recipientHash).copyOf(TAG_LENGTH)
  }

  companion object {
    /** @throws IllegalArgumentException if [encoded] is not a valid `age1tag1...` recipient */
    fun parse(encoded: String): P256TagRecipient {
      val address = Bech32.getDecoder().decode(encoded)
      require(address.humanReadablePart.toString() == HRP) { "not an $HRP recipient" }
      val data = address.data
      require(data.size == P256.COMPRESSED_LENGTH) { "invalid $HRP recipient length" }
      return P256TagRecipient(P256.compress(data))
    }

    fun of(publicKey: ECPublicKey): P256TagRecipient = P256TagRecipient(P256.encode(publicKey, compressed = true))
  }
}

/**
 * A P-256 private key that is only usable for ECDH, for example one that is
 * kept in the Android Keystore.
 */
interface P256KeyAgreement {
  val publicKey: ECPublicKey

  /**
   * Computes the raw ECDH shared secret (the x-coordinate of the shared point)
   * with the given peer public key.
   */
  fun agree(peer: ECPublicKey): ByteArray
}

/**
 * ECDH through the JCA. With the Android Keystore provider, the private key
 * never leaves the secure hardware.
 *
 * @param authorize called with the initialized key agreement before it is
 *   used, for keys that require user authentication for every operation.
 *   Blocks until the user authenticated, or throws.
 */
class JcaP256KeyAgreement(
  private val privateKey: PrivateKey,
  override val publicKey: ECPublicKey,
  private val provider: String? = null,
  private val authorize: (KeyAgreement) -> Unit = {},
) : P256KeyAgreement {
  override fun agree(peer: ECPublicKey): ByteArray {
    val ka = if (provider == null) KeyAgreement.getInstance("ECDH") else KeyAgreement.getInstance("ECDH", provider)
    ka.init(privateKey)
    authorize(ka)
    ka.doPhase(peer, true)
    return ka.generateSecret()
  }
}

private class Stanza(
  private val arguments: List<String>,
  private val body: ByteArray
) : RecipientStanza {
  override fun getType() = STANZA_TYPE
  override fun getArguments() = arguments
  override fun getBody() = body.clone()
}

class P256TagRecipientStanzaWriter(private val recipient: P256TagRecipient) : RecipientStanzaWriter {
  override fun getRecipientStanzas(fileKey: FileKey): Iterable<RecipientStanza> {
    val sealed = Hpke.sealBase(recipient.uncompressed, LABEL, ByteArray(0), fileKey.encoded)
    val encoder = CanonicalBase64.getEncoder()
    val args = listOf(encoder.encodeToString(recipient.tag(sealed.enc)), encoder.encodeToString(sealed.enc))
    return listOf(Stanza(args, sealed.ciphertext))
  }
}

/** The file has no stanza addressed to [recipient]: it was not encrypted to this identity. */
class NoMatchingStanzaException(val recipient: P256TagRecipient) :
  UnsupportedRecipientStanzaException("no $STANZA_TYPE Recipient Stanza for $recipient")

class P256TagRecipientStanzaReader(private val key: P256KeyAgreement) : RecipientStanzaReader {
  private val recipient = P256TagRecipient.of(key.publicKey)

  override fun getFileKey(recipientStanzas: Iterable<RecipientStanza>): FileKey {
    for (stanza in recipientStanzas) {
      if (stanza.type != STANZA_TYPE) continue

      val args = stanza.arguments
      if (args.size != 2) malformed("expected 2 arguments, got ${args.size}")
      val tag = decodeBase64(args[0], TAG_LENGTH, "tag")
      val enc = decodeBase64(args[1], P256.UNCOMPRESSED_LENGTH, "enc")
      val body = stanza.body
      if (body.size != BODY_LENGTH) malformed("body length ${body.size}")

      // not addressed to us: skip it without touching the private key
      if (!MessageDigest.isEqual(tag, recipient.tag(enc))) continue

      val peer = try {
        P256.toJcaPublicKey(enc)
      } catch (e: IllegalArgumentException) {
        malformed("enc is not a valid P-256 point")
      }
      val dh = key.agree(peer)
      return FileKey(Hpke.openBase(dh, enc, recipient.uncompressed, LABEL, ByteArray(0), body))
    }
    throw NoMatchingStanzaException(recipient)
  }

  private fun decodeBase64(encoded: String, length: Int, name: String): ByteArray {
    val decoded = try {
      CanonicalBase64.getDecoder().decode(encoded.toByteArray(Charsets.US_ASCII))
    } catch (e: IllegalArgumentException) {
      malformed("$name is not canonical base64")
    }
    if (decoded.size != length) malformed("$name length ${decoded.size}")
    return decoded
  }

  private fun malformed(reason: String): Nothing =
    throw UnsupportedRecipientStanzaException("malformed $STANZA_TYPE Recipient Stanza: $reason")
}
