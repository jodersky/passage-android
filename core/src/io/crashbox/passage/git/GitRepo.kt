package io.crashbox.passage.git

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.SshTransport

/**
 * The local clone of the password store.
 *
 * Errors from the remote are thrown as jgit exceptions; host key problems
 * can be found in their cause chain with [hostKeyProblem].
 */
class GitRepo(val dir: File, sessions: SshjSessionFactory) {
  private val transport = TransportConfigCallback { (it as? SshTransport)?.sshSessionFactory = sessions }

  fun exists(): Boolean = File(dir, ".git").isDirectory

  fun clone(url: String) {
    Git.cloneRepository()
      .setURI(url)
      .setDirectory(dir)
      .setTransportConfigCallback(transport)
      .call()
      .close()
  }

  /** Fetches and rebases local commits (if any) on top of the remote ones. */
  fun pull() {
    Git.open(dir).use { git ->
      val result = git.pull().setRebase(true).setTransportConfigCallback(transport).call()
      check(result.isSuccessful) { "pull failed: ${result.rebaseResult?.status}" }
    }
  }

  fun push() {
    Git.open(dir).use { git ->
      git.push().setTransportConfigCallback(transport).call()
    }
  }
}

/** The host key exception in [e]'s cause chain, if a connection failed because of the server's host key. */
fun hostKeyProblem(e: Throwable): Exception? =
  generateSequence(e) { it.cause }.firstOrNull { it is UnknownHostKeyException || it is HostKeyChangedException } as Exception?
