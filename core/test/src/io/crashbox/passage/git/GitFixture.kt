package io.crashbox.passage.git

import io.crashbox.passage.testserver.GitSshServer
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.treewalk.TreeWalk

/** A git server over SSH with one repository, and helpers to inspect and change it directly. */
class GitFixture(host: String = "127.0.0.1", hostKeyAlgorithm: String = "EC") : AutoCloseable {
  val tmp: File = Files.createTempDirectory("passage-git-test").toFile()
  val bare = File(tmp, "repos/passage.git")
  val clientKey = ecKeyPair()
  val author = PersonIdent("test", "test@example.com")

  val server = GitSshServer(
    repos = File(tmp, "repos").toPath(),
    authorizedKeys = { listOf(clientKey.public) },
    hostKey = File(tmp, "hostkey").toPath(),
    host = host,
    hostKeyAlgorithm = hostKeyAlgorithm,
  ).start()

  /** the host as written in URLs: IPv6 literals in brackets */
  val urlHost = if (':' in host) "[$host]" else host
  val url = "ssh://git@$urlHost:${server.port}/passage.git"
  val knownHosts = KnownHosts(File(tmp, "known_hosts"))

  init {
    Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
    commitToRemote("hello.txt" to "hello\n")
  }

  override fun close() {
    server.close()
    tmp.deleteRecursively()
  }

  fun repo(name: String, key: KeyPair = clientKey) =
    GitRepo(File(tmp, name), SshjSessionFactory({ key }, knownHosts))

  /** Trusts the server's host key, as the user would. */
  fun trustServer() {
    val e = runCatching { repo("probe").clone(url) }.exceptionOrNull() ?: return
    knownHosts.trust((hostKeyProblem(e) as UnknownHostKeyException).hostKey)
  }

  /** A trusted clone. */
  fun clone(name: String = "clone"): GitRepo {
    trustServer()
    return repo(name).also { it.clone(url) }
  }

  /**
   * Commits changes on the remote, as if pushed by another device (over the
   * file system, not SSH). A null content deletes the file.
   */
  fun commitToRemote(vararg changes: Pair<String, String?>, message: String = "remote change") {
    val work = Files.createTempDirectory(tmp.toPath(), "work").toFile()
    Git.cloneRepository().setURI(bare.toURI().toString()).setDirectory(work).call().use { git ->
      for ((path, content) in changes) {
        val file = File(work, path)
        if (content == null) {
          git.rm().addFilepattern(path).call()
        } else {
          file.parentFile.mkdirs()
          file.writeText(content)
          git.add().addFilepattern(path).call()
        }
      }
      git.commit().setMessage(message).setAuthor(author).setCommitter(author).call()
      git.push().setRemote("origin").setRefSpecs(RefSpec("HEAD:refs/heads/main")).call()
    }
  }

  /** Commits changes in a clone. A null content deletes the file. */
  fun commitLocally(repo: GitRepo, vararg changes: Pair<String, String?>, message: String = "local change") {
    for ((path, content) in changes) {
      val file = File(repo.dir, path)
      if (content == null) file.delete() else file.apply { parentFile.mkdirs() }.writeText(content)
    }
    repo.commit(message, author, changes.map { it.first })
  }

  fun remoteHead(): String = Git.open(bare).use { git ->
    git.repository.parseCommit(git.repository.resolve("refs/heads/main")).shortMessage
  }

  fun remoteFile(path: String): String? = Git.open(bare).use { git ->
    val repo = git.repository
    val tree = repo.parseCommit(repo.resolve("refs/heads/main")).tree
    TreeWalk.forPath(repo, path, tree)?.use { repo.open(it.getObjectId(0)).bytes.decodeToString() }
  }

  companion object {
    fun ecKeyPair(): KeyPair =
      KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
  }
}
