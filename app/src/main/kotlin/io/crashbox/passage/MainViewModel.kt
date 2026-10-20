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
  data object Settings : Screen
}

/** A host key to confirm before connecting; [trust] trusts it and retries. */
class HostKeyQuestion(val hostKey: HostKey, val trust: () -> Unit)

class MainViewModel(app: Application) : AndroidViewModel(app) {
  val passage = (app as App).passage

  var screen: Screen by mutableStateOf(if (passage.repo.exists()) Screen.Entries else Screen.Settings)
  var entries: List<String> by mutableStateOf(emptyList())
    private set
  /** what is running in the background, if anything */
  var busy: String? by mutableStateOf(null)
    private set
  var error: String? by mutableStateOf(null)
  var hostKeyQuestion: HostKeyQuestion? by mutableStateOf(null)

  init {
    refresh()
  }

  private fun refresh() {
    viewModelScope.launch {
      entries = withContext(Dispatchers.IO) { if (passage.repo.exists()) passage.store.entries() else emptyList() }
    }
  }

  /** Runs a remote git operation, asking the user about unknown host keys. */
  private fun remote(label: String, onSuccess: () -> Unit = {}, op: () -> Unit) {
    if (busy != null) return
    busy = label
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) { op() }
        refresh()
        onSuccess()
      } catch (e: Exception) {
        when (val problem = hostKeyProblem(e)) {
          is UnknownHostKeyException -> hostKeyQuestion = HostKeyQuestion(problem.hostKey) {
            hostKeyQuestion = null
            passage.knownHosts.trust(problem.hostKey)
            remote(label, onSuccess, op)
          }
          is HostKeyChangedException -> error =
            "The host key of ${problem.hostKey.host} has CHANGED to ${problem.hostKey.type} " +
              "${problem.hostKey.fingerprint}. Someone could be intercepting the connection. Not connecting."
          else -> error = describe(e)
        }
      } finally {
        busy = null
      }
    }
  }

  fun clone(url: String) = remote("Cloning…", onSuccess = { screen = Screen.Entries }) { passage.clone(url) }

  fun sync() = remote("Syncing…") { passage.repo.pull() }
}

/** A message for the user: the most specific one in the cause chain. */
fun describe(e: Throwable): String =
  generateSequence(e) { it.cause }.mapNotNull { it.message }.lastOrNull() ?: e.toString()
