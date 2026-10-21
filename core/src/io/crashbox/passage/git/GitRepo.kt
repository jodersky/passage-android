package io.crashbox.passage.git

import java.io.File
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream

/** The remote rejected a push. */
class PushRejectedException(message: String) : Exception(message)

enum class Change { ADDED, MODIFIED, DELETED }

/** A path changed differently in local and remote commits since they diverged. */
data class Conflict(val path: String, val local: Change, val remote: Change) {
  /** "Keep both" only makes sense if neither side deleted the file. */
  val canKeepBoth: Boolean get() = local != Change.DELETED && remote != Change.DELETED
}

enum class Resolution { MINE, THEIRS, BOTH }

/**
 * How the local branch and the fetched remote branch relate.
 *
 * @property remote the remote branch head, null if the remote has no such branch yet
 * @property localChanges paths changed by local commits since the common base
 */
class Divergence(
  val local: ObjectId?,
  val remote: ObjectId?,
  val localChanges: Map<String, Change>,
  val remoteChanges: Map<String, Change>,
  val conflicts: List<Conflict>,
)

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
      .setURI(normalizeUrl(url))
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
      stage(git, paths)
      git.commit().setMessage(message).setAuthor(author).setCommitter(author).call()
    }
  }

  private fun stage(git: Git, paths: List<String>) {
    for (path in paths) {
      if (File(dir, path).exists()) git.add().addFilepattern(path).call()
      else git.rm().addFilepattern(path).call()
    }
  }

  fun fetch() {
    Git.open(dir).use { git -> git.fetch().setTransportConfigCallback(transport).call() }
  }

  private fun remoteRef(repo: Repository) = "refs/remotes/origin/${repo.branch}"

  /** Compares the local branch with the last fetched remote branch. Does not change anything. */
  fun divergence(): Divergence =
    Git.open(dir).use { git ->
      val repo = git.repository
      val local = repo.resolve("HEAD")
      val remote = repo.resolve(remoteRef(repo))
      if (local == null || remote == null) return Divergence(local, remote, emptyMap(), emptyMap(), emptyList())
      val base = RevWalk(repo).use { walk ->
        walk.revFilter = RevFilter.MERGE_BASE
        walk.markStart(walk.parseCommit(local))
        walk.markStart(walk.parseCommit(remote))
        walk.next() ?: error("local and remote histories are unrelated")
      }
      val localDiff = diff(repo, base, local)
      val remoteDiff = diff(repo, base, remote)
      val conflicts = localDiff.keys.intersect(remoteDiff.keys)
        .filter { localDiff.getValue(it).second != remoteDiff.getValue(it).second } // same change on both sides is fine
        .sorted()
        .map { Conflict(it, localDiff.getValue(it).first, remoteDiff.getValue(it).first) }
      Divergence(local, remote, localDiff.mapValues { it.value.first }, remoteDiff.mapValues { it.value.first }, conflicts)
    }

  /** Changed paths between two commits, with the kind of change and the new blob (null when deleted). */
  private fun diff(repo: Repository, from: ObjectId, to: ObjectId): Map<String, Pair<Change, ObjectId?>> =
    RevWalk(repo).use { walk ->
      DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
        formatter.setRepository(repo)
        formatter.isDetectRenames = false
        formatter.scan(walk.parseCommit(from).tree, walk.parseCommit(to).tree).associate { entry ->
          when (entry.changeType) {
            DiffEntry.ChangeType.ADD -> entry.newPath to (Change.ADDED to entry.newId.toObjectId())
            DiffEntry.ChangeType.DELETE -> entry.oldPath to (Change.DELETED to null)
            else -> entry.newPath to (Change.MODIFIED to entry.newId.toObjectId())
          }
        }
      }
    }

  /** A file's content in a given commit, or null if it does not exist there. */
  fun read(commit: ObjectId, path: String): ByteArray? =
    Git.open(dir).use { git ->
      val repo = git.repository
      val tree = RevWalk(repo).use { it.parseCommit(commit).tree }
      TreeWalk.forPath(repo, path, tree)?.use { repo.open(it.getObjectId(0)).bytes }
    }

  /**
   * Integrates the fetched remote changes described by [divergence] into the
   * local branch: rebases local commits when nothing conflicts, otherwise
   * merges, resolving each conflict as given in [resolutions].
   *
   * @param conflictCopy where to keep the local version of a path resolved
   *   with [Resolution.BOTH]
   * @return the paths whose content now comes from the local side, after
   *   integration (including copies)
   */
  fun integrate(
    divergence: Divergence,
    resolutions: Map<String, Resolution>,
    author: PersonIdent,
    conflictCopy: (String) -> String,
  ): List<String> {
    val local = divergence.local
    val remote = divergence.remote ?: return divergence.localChanges.filterValues { it != Change.DELETED }.keys.toList()
    require(divergence.conflicts.all { it.path in resolutions }) { "every conflict must be resolved" }
    Git.open(dir).use { git ->
      check(git.repository.resolve("HEAD") == local) { "the branch changed since it was analyzed" }
      if (divergence.conflicts.isEmpty()) rebase(git, remote) else merge(git, divergence, resolutions, author, conflictCopy)
    }
    val kept = divergence.localChanges.filterValues { it != Change.DELETED }.keys.filter { path ->
      when (resolutions[path]) {
        null, Resolution.MINE -> true
        Resolution.THEIRS, Resolution.BOTH -> false
      }
    }
    val copies = divergence.conflicts.filter { resolutions[it.path] == Resolution.BOTH }.map { conflictCopy(it.path) }
    return kept + copies
  }

  private fun rebase(git: Git, remote: ObjectId) {
    val result = git.rebase().setUpstream(remote).call()
    if (!result.status.isSuccessful) {
      if (git.repository.repositoryState.isRebasing) git.rebase().setOperation(RebaseCommand.Operation.ABORT).call()
      error("rebase failed: ${result.status}")
    }
  }

  private fun merge(
    git: Git,
    divergence: Divergence,
    resolutions: Map<String, Resolution>,
    author: PersonIdent,
    conflictCopy: (String) -> String,
  ) {
    val local = divergence.local!!
    val remote = divergence.remote!!
    git.merge()
      .include(remote)
      .setCommit(false)
      .setFastForward(MergeCommand.FastForwardMode.NO_FF)
      .setStrategy(MergeStrategy.RECURSIVE)
      .call()
    val changed = mutableListOf<String>()
    for (conflict in divergence.conflicts) {
      val path = conflict.path
      val mine = read(local, path)
      val theirs = read(remote, path)
      when (resolutions.getValue(path)) {
        Resolution.MINE -> write(path, mine)
        Resolution.THEIRS -> write(path, theirs)
        Resolution.BOTH -> {
          require(conflict.canKeepBoth) { "cannot keep both versions of $path" }
          val copy = conflictCopy(path)
          write(path, theirs)
          write(copy, mine)
          changed += copy
        }
      }
      changed += path
    }
    stage(git, changed)
    val names = divergence.conflicts.joinToString { it.path }
    git.commit().setMessage("Merge remote changes, resolving conflicts in $names.").setAuthor(author).setCommitter(author).call()
  }

  /** Writes a file in the working tree, or deletes it if [content] is null. */
  private fun write(path: String, content: ByteArray?) {
    val file = File(dir, path)
    if (content == null) {
      file.delete()
    } else {
      file.parentFile.mkdirs()
      file.writeBytes(content)
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

  /** Whether there are local commits that are not on the remote branch yet. */
  fun hasUnpushedCommits(): Boolean =
    Git.open(dir).use { git ->
      val repo = git.repository
      val remote = repo.resolve(remoteRef(repo)) ?: return true
      val local = repo.resolve("HEAD") ?: return false
      git.log().addRange(remote, local).call().any()
    }
}

private val RebaseResult.Status.isSuccessful: Boolean
  get() = this == RebaseResult.Status.OK || this == RebaseResult.Status.FAST_FORWARD ||
    this == RebaseResult.Status.UP_TO_DATE || this == RebaseResult.Status.NOTHING_TO_COMMIT

/**
 * Rewrites scp-like URLs with an IPv6 literal, which jgit cannot parse
 * (`git@[fd00::1]:~/git/x.git`), to the equivalent ssh URL
 * (`ssh://git@[fd00::1]/~/git/x.git`). Like with git, scp-like paths are
 * relative to the home directory unless they are absolute. Other URLs are
 * returned unchanged.
 */
fun normalizeUrl(url: String): String {
  val match = SCP_IPV6.matchEntire(url.trim()) ?: return url
  val (user, host, path) = match.destructured
  val sshPath = when {
    path.startsWith("/") -> path
    path.startsWith("~") -> "/$path"
    else -> "/~/$path"
  }
  return "ssh://$user[$host]$sshPath"
}

private val SCP_IPV6 = Regex("""([^@/\[]+@)?\[([0-9A-Fa-f:.%a-zA-Z]+)\]:(?!//)(.+)""")

/** The host key exception in [e]'s cause chain, if a connection failed because of the server's host key. */
fun hostKeyProblem(e: Throwable): Exception? =
  generateSequence(e) { it.cause }.firstOrNull { it is UnknownHostKeyException || it is HostKeyChangedException } as Exception?
