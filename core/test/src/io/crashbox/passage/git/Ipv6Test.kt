package io.crashbox.passage.git

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.eclipse.jgit.transport.URIish

class Ipv6Test {
  @Test
  fun jgitKeepsBracketsAroundIpv6Hosts() {
    // which is why the session factory strips them before connecting
    val uri = URIish("ssh://jodersky@[fd42:aa00:4320:2000:4::1]:2223/~/git/passage.git")
    assertEquals("[fd42:aa00:4320:2000:4::1]", uri.host)
    assertEquals(2223, uri.port)
    assertEquals("~/git/passage.git", uri.path)
  }

  private val f = GitFixture(host = "::1")

  @AfterTest
  fun cleanup() = f.close()

  @Test
  fun cloneSyncAndHostKeysOverIpv6() {
    val e = assertFailsWith<Exception> { f.repo("clone").clone(f.url) }
    val problem = assertIs<UnknownHostKeyException>(hostKeyProblem(e))
    assertEquals("[::1]:${f.server.port}", problem.hostKey.host) // OpenSSH's known_hosts form
    f.knownHosts.trust(problem.hostKey)

    val clone = f.repo("clone")
    clone.clone(f.url)
    assertEquals("hello\n", File(clone.dir, "hello.txt").readText())

    f.commitLocally(clone, "phone.txt" to "from the phone\n", message = "add phone.txt")
    f.commitToRemote("remote.txt" to "from elsewhere\n")
    clone.fetch()
    clone.integrate(clone.divergence(), emptyMap(), f.author) { error("no conflicts expected") }
    clone.push()
    assertEquals("from elsewhere\n", File(clone.dir, "remote.txt").readText())
    assertEquals("add phone.txt", f.remoteHead())
  }
}
