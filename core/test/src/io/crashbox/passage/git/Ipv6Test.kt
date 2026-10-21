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
  fun normalizesScpLikeIpv6Urls() {
    assertEquals("ssh://git@[fd00::1]/~/git/passage.git", normalizeUrl("git@[fd00::1]:~/git/passage.git"))
    assertEquals("ssh://git@[fd00::1]/~/git/passage.git", normalizeUrl("git@[fd00::1]:git/passage.git"))
    assertEquals("ssh://git@[fd00::1]/srv/git/passage.git", normalizeUrl("git@[fd00::1]:/srv/git/passage.git"))
    assertEquals("ssh://[fe80::1%wlan0]/~/x.git", normalizeUrl("[fe80::1%wlan0]:x.git"))
    // left alone
    for (url in listOf(
      "git@github.com:me/passage.git",
      "ssh://git@[fd00::1]:2222/srv/passage.git",
      "ssh://git@[fd00::1]/~/git/passage.git",
      "https://example.com/x.git",
    )) assertEquals(url, normalizeUrl(url))
  }

  @Test
  fun jgitParsesNormalizedUrls() {
    val uri = URIish(normalizeUrl("git@[fd00::1]:~/git/passage.git"))
    assertEquals("git", uri.user)
    assertEquals("[fd00::1]", uri.host)
    assertEquals("~/git/passage.git", uri.path) // home-relative, like the scp-like form
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
