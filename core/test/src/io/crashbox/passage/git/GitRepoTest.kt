package io.crashbox.passage.git

import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.eclipse.jgit.api.Git

class GitRepoTest {
  private val f = GitFixture()

  @AfterTest
  fun cleanup() = f.close()

  /** Fetch, integrate without conflicts, push. */
  private fun sync(repo: GitRepo) {
    repo.fetch()
    repo.integrate(repo.divergence(), emptyMap(), f.author) { error("no conflicts expected") }
    repo.push()
  }

  @Test
  fun unknownHostKeyIsReportedAndCanBeTrusted() {
    val e = assertFailsWith<Exception> { f.repo("clone").clone(f.url) }
    val problem = assertIs<UnknownHostKeyException>(hostKeyProblem(e))
    assertEquals("[127.0.0.1]:${f.server.port}", problem.hostKey.host)
    assertEquals("ecdsa-sha2-nistp256", problem.hostKey.type)

    f.knownHosts.trust(problem.hostKey)
    val clone = f.repo("clone")
    clone.clone(f.url)
    assertEquals("hello\n", File(clone.dir, "hello.txt").readText())
  }

  private fun assertHostKeyChangedWhenTrusting(other: KeyPair) {
    f.trustServer()
    // replace the trusted key by another one
    File(f.tmp, "known_hosts").writeText(HostKey("[127.0.0.1]:${f.server.port}", other.public).knownHostsLine + "\n")
    val e = assertFailsWith<Exception> { f.repo("clone").clone(f.url) }
    assertIs<HostKeyChangedException>(hostKeyProblem(e))
  }

  @Test
  fun changedHostKeyIsRejected() = assertHostKeyChangedWhenTrusting(GitFixture.ecKeyPair())

  @Test
  fun changedHostKeyTypeIsRejected() =
    assertHostKeyChangedWhenTrusting(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair())

  @Test
  fun unauthorizedClientKeyIsRejected() {
    f.trustServer()
    val e = assertFailsWith<Exception> { f.repo("clone", key = GitFixture.ecKeyPair()).clone(f.url) }
    assertNull(hostKeyProblem(e))
    assertNotNull(generateSequence<Throwable>(e) { it.cause }.firstOrNull { it.message?.contains("auth", ignoreCase = true) == true })
  }

  @Test
  fun pullAndPush() {
    val clone = f.clone()
    f.commitToRemote("remote.txt" to "from elsewhere\n")
    sync(clone)
    assertEquals("from elsewhere\n", File(clone.dir, "remote.txt").readText())

    f.commitLocally(clone, "local.txt" to "from the phone\n", message = "add local.txt")
    sync(clone)
    assertEquals("add local.txt", f.remoteHead())
  }

  @Test
  fun divergentChangesToDifferentFilesAreRebased() {
    val clone = f.clone()
    f.commitLocally(clone, "new.txt" to "new\n", "hello.txt" to null, message = "change things")
    assertEquals(true, clone.hasUnpushedCommits())
    f.commitToRemote("remote.txt" to "concurrent\n")

    sync(clone)

    assertEquals(false, clone.hasUnpushedCommits())
    assertEquals("change things", f.remoteHead())
    assertEquals("new\n", f.remoteFile("new.txt"))
    assertEquals(null, f.remoteFile("hello.txt"))
    assertEquals("concurrent\n", f.remoteFile("remote.txt"))
    // rebased: linear history
    Git.open(clone.dir).use { git -> assertTrue(git.log().call().all { it.parentCount <= 1 }) }
  }

  @Test
  fun rejectedPushIsReported() {
    val clone = f.clone()
    f.commitLocally(clone, "new.txt" to "new\n", message = "add new.txt")
    f.commitToRemote("remote.txt" to "concurrent\n", message = "add remote.txt")

    assertFailsWith<PushRejectedException> { clone.push() } // not fast-forward
    assertEquals("add remote.txt", f.remoteHead())
  }
}
