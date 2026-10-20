package io.crashbox.passage

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.crashbox.passage.git.HostKey
import io.crashbox.passage.git.HostKeyChangedException
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
  /** what is running in the background, if anything */
  var busy: String? by mutableStateOf(null)
    private set
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
    }
  }

  /**
   * Runs [op] in the background, one operation at a time. For remote
   * operations, asks the user about unknown host keys and retries.
   */
  private fun run(label: String, onSuccess: () -> Unit = {}, op: () -> Unit) {
    if (busy != null) return
    busy = label
    viewModelScope.launch {
      val result = runCatching { withContext(Dispatchers.IO) { op() } }
      busy = null
      refresh()
      result.onSuccess { onSuccess() }.onFailure { e ->
        when (val problem = hostKeyProblem(e)) {
          is UnknownHostKeyException -> hostKeyQuestion = HostKeyQuestion(problem.hostKey) {
            hostKeyQuestion = null
            passage.knownHosts.trust(problem.hostKey)
            run(label, onSuccess, op)
          }
          is HostKeyChangedException -> error =
            "The host key of ${problem.hostKey.host} has CHANGED to ${problem.hostKey.type} " +
              "${problem.hostKey.fingerprint}. Someone could be intercepting the connection. Not connecting."
          else -> error = describe(e)
        }
      }
    }
  }

  fun clone(url: String) = run("Cloning…", onSuccess = { screen = Screen.Entries }) { passage.clone(url) }

  /** Pulls and pushes; [announce] tells the user when done. */
  fun sync(announce: Boolean = true) =
    run("Syncing…", onSuccess = { if (announce) notice = "Synced" }) { passage.repo.sync() }

  fun save(name: String, content: String, isNew: Boolean) {
    var warnings = emptyList<String>()
    run("Saving…", onSuccess = {
      screen = Screen.Entries
      notice = (listOf("Saved $name") + warnings).joinToString("\n")
      sync(announce = false)
    }) { warnings = passage.save(name, content, isNew) }
  }

  fun delete(name: String) = run("Deleting…", onSuccess = {
    screen = Screen.Entries
    notice = "Deleted $name"
    sync(announce = false)
  }) { passage.delete(name) }
}

/** A message for the user: the most specific one in the cause chain. */
fun describe(e: Throwable): String =
  generateSequence(e) { it.cause }.mapNotNull { it.message }.lastOrNull() ?: e.toString()
