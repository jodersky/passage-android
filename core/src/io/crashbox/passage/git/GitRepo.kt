package io.crashbox.passage.git

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshTransport

/** A pull could not be applied because local and remote changes conflict. Nothing was changed. */
class ConflictException(message: String) : Exception(message)

/** The remote rejected a push. */
class PushRejectedException(message: String) : Exception(message)

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

  /**
   * Commits changes to the given paths (relative to [dir]): paths that exist
   * are added, missing ones are removed.
   */
  fun commit(message: String, author: PersonIdent, paths: List<String>) {
    Git.open(dir).use { git ->
      for (path in paths) {
        if (File(dir, path).exists()) git.add().addFilepattern(path).call()
        else git.rm().addFilepattern(path).call()
      }
      git.commit().setMessage(message).setAuthor(author).setCommitter(author).call()
    }
  }

  /**
   * Fetches and rebases local commits (if any) on top of the remote ones.
   *
   * @throws ConflictException if local and remote changes conflict; the
   *   rebase is aborted, so the local commits are kept unchanged
   */
  fun pull() {
    Git.open(dir).use { git ->
      val result = git.pull().setRebase(true).setTransportConfigCallback(transport).call()
      if (!result.isSuccessful) {
        val status = result.rebaseResult?.status
        if (git.repository.repositoryState.isRebasing) {
          git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
        }
        throw ConflictException("local changes conflict with remote changes ($status)")
      }
    }
  }

  /** @throws PushRejectedException if the remote did not accept all updates */
  fun push() {
    Git.open(dir).use { git ->
      val rejected = git.push().setTransportConfigCallback(transport).call()
        .flatMap { it.remoteUpdates }
        .filter { it.status != RemoteRefUpdate.Status.OK && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
      if (rejected.isNotEmpty()) {
        throw PushRejectedException(rejected.joinToString { "${it.remoteName}: ${it.status} ${it.message ?: ""}".trim() })
      }
    }
  }

  /** Pulls, then pushes local commits. */
  fun sync() {
    pull()
    push()
  }

  /** Whether there are local commits that are not on the remote branch yet. */
  fun hasUnpushedCommits(): Boolean =
    Git.open(dir).use { git ->
      val repo = git.repository
      val branch = repo.branch
      val remote = repo.resolve("refs/remotes/origin/$branch") ?: return true
      val local = repo.resolve("refs/heads/$branch") ?: return false
      git.log().addRange(remote, local).call().any()
    }
}

/** The host key exception in [e]'s cause chain, if a connection failed because of the server's host key. */
fun hostKeyProblem(e: Throwable): Exception? =
  generateSequence(e) { it.cause }.firstOrNull { it is UnknownHostKeyException || it is HostKeyChangedException } as Exception?
