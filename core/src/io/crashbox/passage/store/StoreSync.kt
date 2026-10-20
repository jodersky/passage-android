package io.crashbox.passage.store

import io.crashbox.passage.git.Divergence
import io.crashbox.passage.git.GitRepo
import io.crashbox.passage.git.Resolution
import java.io.File
import java.time.LocalDate
import org.eclipse.jgit.lib.PersonIdent

/**
 * Synchronizes a password store with its remote, on top of [GitRepo]: names
 * the copies of entries kept in both versions after a conflict, and finds
 * entries that must be re-encrypted because their recipients changed.
 */
class StoreSync(
  private val repo: GitRepo,
  private val store: PasswordStore,
  private val today: () -> LocalDate = LocalDate::now,
) {
  /** Fetches and compares with the remote. Changes nothing locally. */
  fun prepare(): Divergence {
    repo.fetch()
    return repo.divergence()
  }

  /**
   * Integrates remote changes, see [GitRepo.integrate].
   *
   * @return entries from the local side whose recipients changed: they are
   *   encrypted to outdated recipients and must be re-encrypted before
   *   pushing
   */
  fun integrate(divergence: Divergence, resolutions: Map<String, Resolution>, author: PersonIdent): List<String> {
    val copies = mutableMapOf<String, String>()
    val localPaths = repo.integrate(divergence, resolutions, author) { path -> copies.getOrPut(path) { conflictCopy(path) } }
    val local = divergence.local ?: return emptyList()
    return localPaths.mapNotNull(PasswordStore::entryName).filter { name ->
      val before = PasswordStore.recipientsIn(name) { repo.read(local, it)?.decodeToString() }
      val after = PasswordStore.recipientsIn(name) { File(store.root, it).takeIf(File::isFile)?.readText() }
      before != after
    }
  }

  /** `web/github.age` -> `web/github-conflict-2026-10-21.age`, avoiding existing files. */
  private fun conflictCopy(path: String): String {
    val base = path.removeSuffix(PasswordStore.SUFFIX) + "-conflict-" + today()
    val suffix = if (path.endsWith(PasswordStore.SUFFIX)) PasswordStore.SUFFIX else ""
    return generateSequence(1) { it + 1 }
      .map { n -> if (n == 1) "$base$suffix" else "$base-$n$suffix" }
      .first { !File(store.root, it).exists() }
  }
}
