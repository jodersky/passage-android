package io.crashbox.passage.git

import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import net.schmizz.sshj.common.KeyType

class Ed25519Test {
  init {
    Ed25519Provider.install()
  }

  @Test
  fun providerKeysAreRecognizedBySshj() {
    // an Ed25519 public key, X.509 encoded
    val encoded = Base64.getDecoder().decode("MCowBQYDK2VwAyEAGb9ECWmEzf6FQbrBZ9w7lshQhqowtrbLDFw4rXAxZuE=")
    val factory = KeyFactory.getInstance("Ed25519")
    assertEquals(Ed25519Provider.NAME, factory.provider.name)
    val key = factory.generatePublic(X509EncodedKeySpec(encoded))
    assertEquals("Ed25519", key.algorithm)
    assertEquals(KeyType.ED25519, KeyType.fromKey(key))
    assertEquals(Ed25519Provider.NAME, Signature.getInstance("Ed25519").provider.name)
  }

  private val f = GitFixture(hostKeyAlgorithm = "Ed25519")

  @AfterTest
  fun cleanup() = f.close()

  @Test
  fun serverWithEd25519HostKey() {
    val e = assertFailsWith<Exception> { f.repo("clone").clone(f.url) }
    val problem = assertIs<UnknownHostKeyException>(hostKeyProblem(e))
    assertEquals("ssh-ed25519", problem.hostKey.type)
    f.knownHosts.trust(problem.hostKey)

    val clone = f.repo("clone")
    clone.clone(f.url) // verifies the host key against known_hosts, and its signature
    assertEquals("hello\n", File(clone.dir, "hello.txt").readText())
  }
}
