package io.crashbox.passage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import io.crashbox.passage.age.JcaP256KeyAgreement
import io.crashbox.passage.age.P256TagRecipient
import io.crashbox.passage.age.P256TagRecipientStanzaReader
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.KeyAgreement
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType

/**
 * The app's two keys, both P-256 in StrongBox and not exportable:
 * - the age identity (ECDH), which requires user authentication for every use
 * - the SSH key for git (signing), usable while the device is unlocked
 */
object Keys {
  private const val PROVIDER = "AndroidKeyStore"
  private const val AGE_ALIAS = "age"
  private const val SSH_ALIAS = "ssh"

  private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

  private fun entry(alias: String): KeyStore.PrivateKeyEntry {
    val ks = keyStore()
    if (!ks.containsAlias(alias)) generate(alias)
    return ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
  }

  private fun generate(alias: String) {
    val spec = when (alias) {
      AGE_ALIAS -> KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
        .setUserAuthenticationRequired(true)
        // 0: authentication is needed for every single operation
        .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        // Since the device credential is accepted anyway, invalidating the key
        // when a fingerprint is enrolled adds little, but would lock the
        // phone out of the store.
        .setInvalidatedByBiometricEnrollment(false)
      SSH_ALIAS -> KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
        .setDigests(KeyProperties.DIGEST_SHA256)
      else -> error("unknown key $alias")
    }
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setIsStrongBoxBacked(true)
      .setUnlockedDeviceRequired(true)
      .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
      initialize(spec)
      generateKeyPair()
    }
  }

  val ageRecipient: P256TagRecipient
    get() = P256TagRecipient.of(entry(AGE_ALIAS).certificate.publicKey as ECPublicKey)

  /** @param authorize asks the user to authenticate the given key agreement, see [UserAuthenticator] */
  fun ageIdentity(authorize: (KeyAgreement) -> Unit): P256TagRecipientStanzaReader {
    val entry = entry(AGE_ALIAS)
    return P256TagRecipientStanzaReader(
      JcaP256KeyAgreement(entry.privateKey, entry.certificate.publicKey as ECPublicKey, PROVIDER, authorize)
    )
  }

  fun sshKeyPair(): KeyPair = entry(SSH_ALIAS).let { KeyPair(it.certificate.publicKey, it.privateKey) }

  /** The SSH public key, in authorized_keys format. */
  val sshPublicKey: String
    get() {
      val key = entry(SSH_ALIAS).certificate.publicKey
      val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
      return "${KeyType.fromKey(key)} ${Base64.getEncoder().encodeToString(blob)} passage"
    }

  val ageSecurityLevel: String get() = securityLevel(AGE_ALIAS)
  val sshSecurityLevel: String get() = securityLevel(SSH_ALIAS)

  private fun securityLevel(alias: String): String {
    val key = entry(alias).privateKey
    val info = KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
    return when (info.securityLevel) {
      KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
      KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
      KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
      else -> "unknown (${info.securityLevel})"
    }
  }
}
