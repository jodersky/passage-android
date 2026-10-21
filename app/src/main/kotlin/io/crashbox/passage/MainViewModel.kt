package io.crashbox.passage

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.crashbox.passage.git.Conflict
import io.crashbox.passage.git.HostKey
import io.crashbox.passage.git.HostKeyChangedException
import io.crashbox.passage.git.Resolution
import io.crashbox.passage.git.UnknownHostKeyException
import io.crashbox.passage.git.hostKeyProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
  data object Entries : Screen
  data class Entry(val name: String) : Screen
  /** Editing an entry; [name] is null for a new one. */
  class Editor(val name: String?, val content: String) : Screen {
    override fun toString() = "Editor($name)" // never show the content
  }
  data object Conflicts : Screen
  data object Settings : Screen
}

/** A host key to confirm before connecting; [trust] trusts it and retries. */
class HostKeyQuestion(val hostKey: HostKey, val trust: () -> Unit)

class MainViewModel(app: Application) : AndroidViewModel(app) {
  val passage = (app as App).passage

  var screen: Screen by mutableStateOf(if (passage.repo.exists()) Screen.Entries else Screen.Settings)
  var entries: List<String> by mutableStateOf(emptyList())
    private set
  /** whether there are local commits that were not pushed yet */
  var unpushed: Boolean by mutableStateOf(false)
    private set
  /** conflicts with the remote found by the last sync, waiting for the user to resolve them */
  var conflicts: List<Conflict> by mutableStateOf(emptyList())
    private set
  /** entries that must be re-encrypted before pushing */
  var pendingReencryption: Set<String> by mutableStateOf(emptySet())
    private set
  /** what is running in the background, if anything */
  var busy: String? by mutableStateOf(null)
    private set
  /** whether a sync runs, in any of its steps */
  val syncing: Boolean get() = busy == SYNCING || busy == PUSHING
  var error: String? by mutableStateOf(null)
  /** a short message for the user */
  var notice: String? by mutableStateOf(null)
  var hostKeyQuestion: HostKeyQuestion? by mutableStateOf(null)

  init {
    refresh()
  }

  private fun refresh() {
    viewModelScope.launch {
      withContext(Dispatchers.IO) {
        if (passage.repo.exists()) passage.store.entries() to passage.repo.hasUnpushedCommits() else emptyList<String>() to false
      }.let { (e, u) ->
        entries = e
        unpushed = u
      }
      pendingReencryption = passage.pendingReencryption
    }
  }

  /**
   * Runs [op] in the background, one operation at a time, then [onSuccess]
   * with its result on the main thread. For remote operations, asks the user
   * about unknown host keys and retries.
   */
  private fun <T> run(label: String, remote: Boolean = false, onSuccess: (T) -> Unit = {}, op: () -> T) {
    if (busy != null) return
    busy = label
    viewModelScope.launch {
      val result = runCatching { withContext(Dispatchers.IO) { op() } }
      busy = null
      refresh()
      result.onSuccess(onSuccess).onFailure { e ->
        when (val problem = hostKeyProblem(e)) {
          is UnknownHostKeyException -> hostKeyQuestion = HostKeyQuestion(problem.hostKey) {
            hostKeyQuestion = null
            passage.knownHosts.trust(problem.hostKey)
            run(label, remote, onSuccess, op)
          }
          is HostKeyChangedException -> error =
            "The host key of ${problem.hostKey.host} has CHANGED to ${problem.hostKey.type} " +
              "${problem.hostKey.fingerprint}. Someone could be intercepting the connection. Not connecting."
          else -> error = describe(e) + if (remote && !hasLocalNetworkAccess()) LOCAL_NETWORK_HINT else ""
        }
      }
    }
  }

  private fun hasLocalNetworkAccess() =
    getApplication<Application>().checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

  fun clone(url: String) = run("Cloning…", remote = true, onSuccess = { screen = Screen.Entries }) { passage.clone(url) }

  /**
   * Fetches and integrates remote changes, then pushes. Stops when changes
   * conflict, until the user resolved them with [resolve].
   */
  fun sync(activity: Activity) = run(
    SYNCING,
    remote = true,
    onSuccess = { found: List<Conflict> ->
      conflicts = found
      if (found.isEmpty()) push(activity) else screen = Screen.Conflicts
    },
  ) {
    val divergence = passage.sync.prepare()
    if (divergence.conflicts.isEmpty()) passage.integrate(divergence, emptyMap())
    divergence.conflicts
  }

  /** Integrates the fetched remote changes, resolving conflicts as given, then pushes. */
  fun resolve(activity: Activity, resolutions: Map<String, Resolution>) = run(
    "Resolving…",
    onSuccess = { remaining: List<Conflict> ->
      conflicts = remaining
      if (remaining.isEmpty()) {
        screen = Screen.Entries
        push(activity)
      } else {
        notice = "The conflicts changed meanwhile, please review them again"
      }
    },
  ) {
    // recompute against the last fetch: local changes may have been made
    // while the user was deciding
    val divergence = passage.repo.divergence()
    if (divergence.conflicts.map { it.path }.toSet() != resolutions.keys) return@run divergence.conflicts
    passage.integrate(divergence, resolutions)
    emptyList()
  }

  /** Re-encrypts entries whose recipients changed, then pushes. Without re-encrypting all of them, nothing is pushed. */
  private fun push(activity: Activity) = run(
    PUSHING,
    remote = true,
    onSuccess = { skipped: List<String> ->
      notice = if (skipped.isEmpty()) "Synced"
      else "Synced. Could not re-encrypt ${skipped.joinToString()}: this phone cannot read them."
    },
  ) {
    val skipped = mutableListOf<String>()
    for (name in passage.pendingReencryption.sorted()) {
      if (passage.store.exists(name)) {
        try {
          passage.reencrypt(name, UserAuthenticator(activity, "Re-encrypt for updated recipients", name)::authorize)
        } catch (e: NotARecipientException) {
          skipped += name
        }
      }
      passage.pendingReencryption -= name
    }
    passage.repo.push()
    skipped
  }

  /** Saves an entry locally; [oldName] is null for a new entry, and differs from [name] when renaming. */
  fun save(oldName: String?, name: String, content: String) {
    var warnings = emptyList<String>()
    run("Saving…", onSuccess = {
      screen = Screen.Entries
      notice = (listOf("Saved $name") + warnings).joinToString("\n")
    }) { warnings = passage.save(oldName, name, content) }
  }

  /** Deletes an entry locally. */
  fun delete(name: String) = run("Deleting…", onSuccess = {
    screen = Screen.Entries
    notice = "Deleted $name"
  }) { passage.delete(name) }
}

private const val SYNCING = "Syncing…"
private const val PUSHING = "Pushing…"

private const val LOCAL_NETWORK_HINT =
  "\n\nIf the server is on your local network, allow Passage to access \"Nearby devices\" in the app's permissions."

/** A message for the user: the most specific one in the cause chain. */
fun describe(e: Throwable): String =
  generateSequence(e) { it.cause }.mapNotNull { it.message }.lastOrNull() ?: e.toString()
