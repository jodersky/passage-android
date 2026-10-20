package io.crashbox.passage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType

/** The SSH key used for git: an ECDSA P-256 signing key in StrongBox. */
object SshKeystore {
  private const val PROVIDER = "AndroidKeyStore"
  private const val ALIAS = "ssh-key"

  /**
   * Loads the key, generating it on first use. The private key is a Keystore
   * handle; signing happens in StrongBox.
   */
  fun getOrCreate(): KeyPair {
    val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
    if (!ks.containsAlias(ALIAS)) generate()
    val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
    return KeyPair(entry.certificate.publicKey, entry.privateKey)
  }

  private fun generate() {
    val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .setIsStrongBoxBacked(true)
      .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply {
      initialize(spec)
      generateKeyPair()
    }
  }

  fun securityLevel(): String = keystoreSecurityLevel(ALIAS)

  /** The public key in OpenSSH authorized_keys format. */
  fun authorizedKey(keyPair: KeyPair, comment: String = "passage"): String {
    val blob = Buffer.PlainBuffer().putPublicKey(keyPair.public).compactData
    return "${KeyType.fromKey(keyPair.public)} ${Base64.getEncoder().encodeToString(blob)} $comment"
  }
}
