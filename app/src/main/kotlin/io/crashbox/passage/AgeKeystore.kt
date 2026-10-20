package io.crashbox.passage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import io.crashbox.passage.age.JcaP256KeyAgreement
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** The age identity: a P-256 ECDH key in StrongBox. It cannot be exported. */
object AgeKeystore {
  private const val PROVIDER = "AndroidKeyStore"
  private const val ALIAS = "age-identity"

  private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

  /** Loads the key, generating it on first use. */
  fun getOrCreate(): JcaP256KeyAgreement {
    val ks = keyStore()
    if (!ks.containsAlias(ALIAS)) generate()
    val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    return JcaP256KeyAgreement(entry.privateKey, entry.certificate.publicKey as ECPublicKey, PROVIDER)
  }

  private fun generate() {
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setIsStrongBoxBacked(true)
      .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
      initialize(spec)
      generateKeyPair()
    }
  }

  fun securityLevel(): String = keystoreSecurityLevel(ALIAS)
}

/** Where a Keystore key actually lives, as reported by Keystore. */
fun keystoreSecurityLevel(alias: String): String {
  val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
  val key = (ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey
  val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
  return when (info.securityLevel) {
    KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
    KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
    else -> "unknown (${info.securityLevel})"
  }
}
