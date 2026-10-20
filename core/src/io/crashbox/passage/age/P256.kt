package io.crashbox.passage.age

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import org.bouncycastle.crypto.ec.CustomNamedCurves

/** Conversions between encodings of P-256 public keys. */
internal object P256 {
  const val COMPRESSED_LENGTH = 33
  const val UNCOMPRESSED_LENGTH = 65

  private val curve = CustomNamedCurves.getByName("P-256").curve

  val jcaParameters: ECParameterSpec by lazy {
    val params = AlgorithmParameters.getInstance("EC")
    params.init(ECGenParameterSpec("secp256r1"))
    params.getParameterSpec(ECParameterSpec::class.java)
  }

  /**
   * Decodes a SEC 1 point (compressed or uncompressed) and checks that it lies
   * on the curve.
   *
   * @throws IllegalArgumentException if the encoding or the point is invalid
   */
  private fun decode(encoded: ByteArray): org.bouncycastle.math.ec.ECPoint {
    require(encoded.size == COMPRESSED_LENGTH || encoded.size == UNCOMPRESSED_LENGTH) {
      "invalid P-256 point length ${encoded.size}"
    }
    val point = curve.decodePoint(encoded)
    require(!point.isInfinity && point.isValid) { "invalid P-256 point" }
    return point
  }

  fun compress(encoded: ByteArray): ByteArray = decode(encoded).getEncoded(true)

  fun uncompress(encoded: ByteArray): ByteArray = decode(encoded).getEncoded(false)

  fun encode(key: ECPublicKey, compressed: Boolean): ByteArray =
    curve.createPoint(key.w.affineX, key.w.affineY).getEncoded(compressed).also {
      // createPoint does not validate
      decode(it)
    }

  fun toJcaPublicKey(encoded: ByteArray): ECPublicKey {
    val point = decode(encoded).normalize()
    val spec = ECPublicKeySpec(
      ECPoint(point.affineXCoord.toBigInteger(), point.affineYCoord.toBigInteger()),
      jcaParameters
    )
    return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
  }
}
