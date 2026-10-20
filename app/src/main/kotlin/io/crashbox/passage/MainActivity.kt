package io.crashbox.passage

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // no screenshots, and a blank preview in recent apps
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    enableEdgeToEdge()
    setContent {
      val context = LocalContext.current
      val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      MaterialTheme(colorScheme = colors) {
        PassageUi(viewModel())
      }
    }
  }
}

@Composable
fun PassageUi(vm: MainViewModel) {
  when (val screen = vm.screen) {
    Screen.Entries -> EntriesScreen(vm)
    is Screen.Entry -> {
      BackHandler { vm.screen = Screen.Entries }
      EntryScreen(vm, screen.name)
    }
    Screen.Settings -> {
      if (vm.passage.repo.exists()) BackHandler { vm.screen = Screen.Entries }
      SettingsScreen(vm)
    }
  }

  vm.hostKeyQuestion?.let { q ->
    AlertDialog(
      onDismissRequest = { vm.hostKeyQuestion = null },
      title = { Text("Unknown server") },
      text = {
        Text(
          "First connection to ${q.hostKey.host}. Check that its host key fingerprint is:\n\n" +
            "${q.hostKey.type}\n${q.hostKey.fingerprint}\n\nTrust this server?"
        )
      },
      confirmButton = { TextButton(onClick = q.trust) { Text("Trust") } },
      dismissButton = { TextButton(onClick = { vm.hostKeyQuestion = null }) { Text("Cancel") } },
    )
  }
  vm.error?.let { message ->
    AlertDialog(
      onDismissRequest = { vm.error = null },
      title = { Text("Error") },
      text = { Text(message) },
      confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(
  title: String,
  vm: MainViewModel,
  onBack: (() -> Unit)? = null,
  actions: @Composable () -> Unit = {},
  content: @Composable (Modifier) -> Unit,
) {
  Scaffold(
    topBar = {
      Column {
        TopAppBar(
          title = { Text(title) },
          navigationIcon = {
            if (onBack != null) {
              IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
          },
          actions = { actions() },
        )
        if (vm.busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
      }
    },
  ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
private fun EntriesScreen(vm: MainViewModel) {
  var query by rememberSaveable { mutableStateOf("") }
  val shown = vm.entries.filter { it.contains(query.trim(), ignoreCase = true) }
  Page(
    "Passage",
    vm,
    actions = {
      IconButton(onClick = vm::sync, enabled = vm.busy == null) { Icon(Icons.Default.Refresh, "Sync") }
      IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Default.Settings, "Settings") }
    },
  ) { modifier ->
    Column(modifier.fillMaxSize()) {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        placeholder = { Text("Search") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      )
      LazyColumn {
        items(shown, key = { it }) { name ->
          val dir = name.substringBeforeLast('/', "")
          ListItem(
            headlineContent = { Text(name.substringAfterLast('/')) },
            supportingContent = if (dir.isEmpty()) null else ({ Text(dir) }),
            modifier = Modifier.clickable { vm.screen = Screen.Entry(name) },
          )
          HorizontalDivider()
        }
      }
    }
  }
}

private sealed interface Decryption {
  data object Running : Decryption
  class Done(val content: String) : Decryption
  class Failed(val message: String) : Decryption
}

@Composable
private fun EntryScreen(vm: MainViewModel, name: String) {
  val activity = LocalContext.current as ComponentActivity
  // the decrypted content only lives in this screen's state: it is gone
  // when leaving the screen
  var attempt by remember { mutableStateOf(0) }
  var state: Decryption by remember { mutableStateOf(Decryption.Running) }
  var revealed by remember { mutableStateOf(false) }

  LaunchedEffect(name, attempt) {
    state = Decryption.Running
    val authenticator = UserAuthenticator(activity, "Decrypt password", name)
    state = try {
      Decryption.Done(withContext(Dispatchers.IO) { vm.passage.decrypt(name, authenticator::authorize) })
    } catch (e: Exception) {
      Decryption.Failed(describe(e))
    }
  }

  Page(name, vm, onBack = { vm.screen = Screen.Entries }) { modifier ->
    Column(
      modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      when (val s = state) {
        Decryption.Running -> CircularProgressIndicator()
        is Decryption.Failed -> {
          Text(s.message, color = MaterialTheme.colorScheme.error)
          Button(onClick = { attempt++ }) { Text("Try again") }
        }
        is Decryption.Done -> {
          val password = s.content.substringBefore('\n')
          val rest = s.content.substringAfter('\n', "").trimEnd()
          Text(
            if (revealed) password else "••••••••••••",
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copySecret(activity, password) }) { Text("Copy") }
            OutlinedButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") }
          }
          if (rest.isNotEmpty()) {
            HorizontalDivider()
            SelectionContainer { Text(rest, fontFamily = FontFamily.Monospace) }
          }
        }
      }
    }
  }
}

private const val CLIP_LABEL = "passage"
private const val CLIP_CLEAR_MS = 45_000L

/** Copies a secret, hidden from the clipboard preview, and clears it after a while. */
private fun copySecret(context: Context, secret: String) {
  val clipboard = context.getSystemService(ClipboardManager::class.java)
  val clip = ClipData.newPlainText(CLIP_LABEL, secret).apply {
    description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
  }
  clipboard.setPrimaryClip(clip)
  val copiedAt = clipboard.primaryClipDescription?.timestamp
  android.os.Handler(context.mainLooper).postDelayed({
    // only clear if nothing else was copied since
    if (clipboard.primaryClipDescription?.timestamp == copiedAt) clipboard.clearPrimaryClip()
  }, CLIP_CLEAR_MS)
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
  val context = LocalContext.current
  val hasRepo = vm.passage.repo.exists()
  var url by rememberSaveable { mutableStateOf(vm.passage.remoteUrl ?: "") }
  // generating the keys the first time may take a moment
  var keys by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) }
  LaunchedEffect(Unit) {
    keys = withContext(Dispatchers.IO) {
      listOf(
        Triple("age recipient", Keys.ageRecipient.toString(), Keys.ageSecurityLevel),
        Triple("SSH public key", Keys.sshPublicKey, Keys.sshSecurityLevel),
      )
    }
  }

  Page("Settings", vm, onBack = if (hasRepo) ({ vm.screen = Screen.Entries }) else null) { modifier ->
    Column(
      modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text("This phone's keys", style = MaterialTheme.typography.titleMedium)
      Text(
        "Add the age recipient to your store's .age-recipients and re-encrypt it with passage. " +
          "Add the SSH public key to your git server. Both keys are stored in secure hardware and cannot be exported.",
        style = MaterialTheme.typography.bodyMedium,
      )
      val k = keys
      if (k == null) CircularProgressIndicator()
      else for ((label, value, level) in k) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("$label ($level)", style = MaterialTheme.typography.labelLarge)
          SelectionContainer { Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
              context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
            }) { Text("Copy") }
            OutlinedButton(onClick = {
              val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
              context.startActivity(Intent.createChooser(send, label))
            }) { Text("Share") }
          }
        }
      }

      HorizontalDivider()
      Text("Git repository", style = MaterialTheme.typography.titleMedium)
      OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("URL") },
        placeholder = { Text("git@github.com:me/passwords.git") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      if (hasRepo) {
        Text(
          "Cloning again replaces the local copy. Local changes that were not pushed are lost.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
      Button(onClick = { vm.clone(url.trim()) }, enabled = url.isNotBlank() && vm.busy == null) {
        Text(if (hasRepo) "Clone again" else "Clone")
      }
      vm.busy?.let { Text(it) }
    }
  }
}
