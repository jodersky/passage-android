package io.crashbox.passage.git

import io.crashbox.passage.testserver.GitSshServer
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec

class GitRepoTest {
  private val tmp: File = Files.createTempDirectory("passage-git-test").toFile()
  private val bare = File(tmp, "repos/passage.git")
  private val clientKey = ecKeyPair()
  private val author = PersonIdent("test", "test@example.com")

  private val server = GitSshServer(
    repos = File(tmp, "repos").toPath(),
    authorizedKeys = { listOf(clientKey.public) },
    hostKey = File(tmp, "hostkey").toPath(),
  ).start()

  private val url = "ssh://git@127.0.0.1:${server.port}/passage.git"
  private val knownHosts = KnownHosts(File(tmp, "known_hosts"))

  init {
    // the remote repository, with one commit
    Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
    commitToRemote("hello.txt", "hello\n")
  }

  @AfterTest
  fun cleanup() {
    server.close()
    tmp.deleteRecursively()
  }

  private fun ecKeyPair(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

  private fun repo(name: String, key: KeyPair = clientKey) =
    GitRepo(File(tmp, name), SshjSessionFactory({ key }, knownHosts))

  /** Commits a file on the remote, as if pushed by another device (over the file system, not SSH). */
  private fun commitToRemote(path: String, content: String) {
    val work = Files.createTempDirectory(tmp.toPath(), "work").toFile()
    Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(work).call().use { git ->
      File(work, path).writeText(content)
      git.add().addFilepattern(path).call()
      git.commit().setMessage("add $path").setAuthor(author).setCommitter(author).call()
      git.push().setRemote("origin").setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
    }
  }

  private fun trustServer() {
    val e = assertFailsWith<Exception> { repo("probe").clone(url) }
    knownHosts.trust(assertIs<UnknownHostKeyException>(hostKeyProblem(e)).hostKey)
  }

  @Test
  fun unknownHostKeyIsReportedAndCanBeTrusted() {
    val e = assertFailsWith<Exception> { repo("clone").clone(url) }
    val problem = assertIs<UnknownHostKeyException>(hostKeyProblem(e))
    assertEquals("[127.0.0.1]:${server.port}", problem.hostKey.host)
    assertEquals("ecdsa-sha2-nistp256", problem.hostKey.type)

    knownHosts.trust(problem.hostKey)
    val clone = repo("clone")
    clone.clone(url)
    assertEquals("hello\n", File(clone.dir, "hello.txt").readText())
  }

  private fun assertHostKeyChangedWhenTrusting(other: KeyPair) {
    trustServer()
    // replace the trusted key by another one
    val known = File(tmp, "known_hosts")
    known.writeText(HostKey("[127.0.0.1]:${server.port}", other.public).knownHostsLine + "\n")

    val e = assertFailsWith<Exception> { repo("clone").clone(url) }
    assertIs<HostKeyChangedException>(hostKeyProblem(e))
  }

  @Test
  fun changedHostKeyIsRejected() = assertHostKeyChangedWhenTrusting(ecKeyPair())

  @Test
  fun changedHostKeyTypeIsRejected() =
    assertHostKeyChangedWhenTrusting(KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair())

  @Test
  fun unauthorizedClientKeyIsRejected() {
    trustServer()
    val e = assertFailsWith<Exception> { repo("clone", key = ecKeyPair()).clone(url) }
    assertNull(hostKeyProblem(e))
    assertNotNull(generateSequence<Throwable>(e) { it.cause }.firstOrNull { it.message?.contains("auth", ignoreCase = true) == true })
  }

  private fun remoteHead(): String = Git.open(bare).use { git ->
    git.repository.parseCommit(git.repository.resolve("refs/heads/main")).shortMessage
  }

  private fun remoteFile(path: String): String? = Git.open(bare).use { git ->
    val repo = git.repository
    val tree = repo.parseCommit(repo.resolve("refs/heads/main")).tree
    org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, path, tree)?.use { repo.open(it.getObjectId(0)).bytes.decodeToString() }
  }

  @Test
  fun commitAndSync() {
    trustServer()
    val clone = repo("clone")
    clone.clone(url)

    File(clone.dir, "new.txt").writeText("new\n")
    File(clone.dir, "hello.txt").delete()
    clone.commit("change things", author, listOf("new.txt", "hello.txt"))
    assertEquals(true, clone.hasUnpushedCommits())

    commitToRemote("remote.txt", "concurrent\n") // the remote moved meanwhile
    clone.sync()

    assertEquals(false, clone.hasUnpushedCommits())
    assertEquals("change things", remoteHead())
    assertEquals("new\n", remoteFile("new.txt"))
    assertEquals(null, remoteFile("hello.txt"))
    assertEquals("concurrent\n", remoteFile("remote.txt"))
  }

  @Test
  fun rejectedPushIsReported() {
    trustServer()
    val clone = repo("clone")
    clone.clone(url)
    File(clone.dir, "new.txt").writeText("new\n")
    clone.commit("add new.txt", author, listOf("new.txt"))
    commitToRemote("remote.txt", "concurrent\n")

    assertFailsWith<PushRejectedException> { clone.push() } // not fast-forward
    assertEquals("add remote.txt", remoteHead())
  }

  @Test
  fun conflictingPullIsAborted() {
    trustServer()
    val clone = repo("clone")
    clone.clone(url)
    File(clone.dir, "hello.txt").writeText("local\n")
    clone.commit("local edit", author, listOf("hello.txt"))
    commitToRemote("hello.txt", "remote\n")

    assertFailsWith<ConflictException> { clone.sync() }
    // the local commit is kept as it was, and the repository is usable
    Git.open(clone.dir).use { git ->
      assertEquals(org.eclipse.jgit.lib.RepositoryState.SAFE, git.repository.repositoryState)
      assertEquals("local edit", git.log().setMaxCount(1).call().first().shortMessage)
    }
    assertEquals("local\n", File(clone.dir, "hello.txt").readText())
    assertEquals("remote\n", remoteFile("hello.txt"))
  }

  @Test
  fun pullAndPush() {
    trustServer()
    val clone = repo("clone")
    clone.clone(url)

    commitToRemote("remote.txt", "from elsewhere\n")
    clone.pull()
    assertEquals("from elsewhere\n", File(clone.dir, "remote.txt").readText())

    Git.open(clone.dir).use { git ->
      File(clone.dir, "local.txt").writeText("from the phone\n")
      git.add().addFilepattern("local.txt").call()
      git.commit().setMessage("add local.txt").setAuthor(author).setCommitter(author).call()
    }
    clone.push()

    Git.open(bare).use { git ->
      val head = git.repository.resolve("refs/heads/main")
      assertEquals("add local.txt", git.repository.parseCommit(head).shortMessage)
    }
  }
}
