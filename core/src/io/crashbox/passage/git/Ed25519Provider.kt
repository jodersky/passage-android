package io.crashbox.passage.git

import java.security.Provider
import java.security.Security

/**
 * Ed25519 key factory and signatures, from BouncyCastle.
 *
 * On Android, sshj must use the default JCA providers (so that Keystore keys
 * can sign), but Android's Conscrypt names Ed25519 keys by their OID
 * ("1.3.101.112") instead of "Ed25519" or "EdDSA", which sshj does not
 * recognize: servers with Ed25519 host keys would fail with "Don't know how
 * to encode key". Installed first, this provider takes over only these two
 * algorithms.
 */
// The (String, String, String) constructor does not exist on Android, which
// only has this one (deprecated on the JDK, but present).
@Suppress("DEPRECATION")
class Ed25519Provider : Provider(NAME, 1.0, "Ed25519 from BouncyCastle, for sshj") {
  init {
    put("KeyFactory.Ed25519", "org.bouncycastle.jcajce.provider.asymmetric.edec.KeyFactorySpi\$Ed25519")
    put("Signature.Ed25519", "org.bouncycastle.jcajce.provider.asymmetric.edec.SignatureSpi\$Ed25519")
  }

  companion object {
    const val NAME = "PassageEd25519"

    fun install() {
      if (Security.getProvider(NAME) == null) Security.insertProviderAt(Ed25519Provider(), 1)
    }
  }
}
