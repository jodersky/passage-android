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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.crashbox.passage.store.EntryContent
import io.crashbox.passage.store.PasswordGenerator
import io.crashbox.passage.store.PasswordStore
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
  val snackbar = remember { SnackbarHostState() }
  LaunchedEffect(vm.notice) {
    vm.notice?.let {
      snackbar.showSnackbar(it, withDismissAction = true)
      vm.notice = null
    }
  }

  when (val screen = vm.screen) {
    Screen.Entries -> EntriesScreen(vm, snackbar)
    is Screen.Entry -> {
      BackHandler { vm.screen = Screen.Entries }
      EntryScreen(vm, snackbar, screen.name)
    }
    is Screen.Editor -> EditorScreen(vm, snackbar, screen)
    Screen.Settings -> {
      if (vm.passage.repo.exists()) BackHandler { vm.screen = Screen.Entries }
      SettingsScreen(vm, snackbar)
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
  snackbar: SnackbarHostState,
  onBack: (() -> Unit)? = null,
  actions: @Composable () -> Unit = {},
  fab: @Composable () -> Unit = {},
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
    floatingActionButton = fab,
    snackbarHost = { SnackbarHost(snackbar) },
  ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
private fun EntriesScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
  var query by rememberSaveable { mutableStateOf("") }
  val shown = vm.entries.filter { it.contains(query.trim(), ignoreCase = true) }
  Page(
    "Passage",
    vm,
    snackbar,
    actions = {
      IconButton(onClick = { vm.sync() }, enabled = vm.busy == null) { Icon(Icons.Default.Refresh, "Sync") }
      IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Default.Settings, "Settings") }
    },
    fab = {
      FloatingActionButton(onClick = { vm.screen = Screen.Editor(null, "") }) { Icon(Icons.Default.Add, "Add") }
    },
  ) { modifier ->
    Column(modifier.fillMaxSize()) {
      if (vm.unpushed && vm.busy == null) {
        Text(
          "Some changes are not pushed yet. Sync to retry.",
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(12.dp),
        )
      }
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
private fun EntryScreen(vm: MainViewModel, snackbar: SnackbarHostState, name: String) {
  val activity = LocalContext.current as ComponentActivity
  // the decrypted content only lives in this screen's state: it is gone
  // when leaving the screen
  var attempt by remember { mutableStateOf(0) }
  var state: Decryption by remember { mutableStateOf(Decryption.Running) }
  var revealed by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }

  LaunchedEffect(name, attempt) {
    state = Decryption.Running
    val authenticator = UserAuthenticator(activity, "Decrypt password", name)
    state = try {
      Decryption.Done(withContext(Dispatchers.IO) { vm.passage.decrypt(name, authenticator::authorize) })
    } catch (e: Exception) {
      Decryption.Failed(describe(e))
    }
  }

  Page(
    name,
    vm,
    snackbar,
    onBack = { vm.screen = Screen.Entries },
    actions = {
      (state as? Decryption.Done)?.let { done ->
        IconButton(onClick = { vm.screen = Screen.Editor(name, done.content) }) { Icon(Icons.Default.Edit, "Edit") }
      }
      // deleting does not need the content, so it is possible even for
      // entries this phone cannot decrypt
      IconButton(onClick = { confirmDelete = true }, enabled = vm.busy == null) { Icon(Icons.Default.Delete, "Delete") }
    },
  ) { modifier ->
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
          val password = EntryContent.password(s.content)
          val notes = EntryContent.notes(s.content)
          Text(
            if (revealed) password else "••••••••••••",
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = FontFamily.Monospace,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copySecret(activity, password) }) { Text("Copy") }
            OutlinedButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") }
          }
          if (notes.isNotEmpty()) {
            HorizontalDivider()
            SelectionContainer { Text(notes, fontFamily = FontFamily.Monospace) }
          }
        }
      }
    }
  }

  if (confirmDelete) {
    AlertDialog(
      onDismissRequest = { confirmDelete = false },
      title = { Text("Delete $name?") },
      text = { Text("The entry is removed from the store, for all devices once synced. It can still be recovered from the git history.") },
      confirmButton = {
        TextButton(onClick = {
          confirmDelete = false
          vm.delete(name)
        }) { Text("Delete") }
      },
      dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun EditorScreen(vm: MainViewModel, snackbar: SnackbarHostState, editor: Screen.Editor) {
  val isNew = editor.name == null
  // not rememberSaveable: the content must not end up in saved instance state
  var name by remember { mutableStateOf(editor.name ?: "") }
  var password by remember { mutableStateOf(EntryContent.password(editor.content)) }
  var notes by remember { mutableStateOf(EntryContent.notes(editor.content)) }
  var revealed by remember { mutableStateOf(isNew) }
  var confirmDiscard by remember { mutableStateOf(false) }

  val dirty = name != (editor.name ?: "") ||
    password != EntryContent.password(editor.content) ||
    notes != EntryContent.notes(editor.content)
  val nameValid = PasswordStore.isValidName(name)
  val back = { if (dirty) confirmDiscard = true else vm.screen = if (isNew) Screen.Entries else Screen.Entry(editor.name!!) }
  BackHandler(onBack = back)

  Page(
    if (isNew) "New entry" else "Edit ${editor.name}",
    vm,
    snackbar,
    onBack = back,
    actions = {
      TextButton(
        onClick = { vm.save(name.trim(), EntryContent.of(password, notes), isNew) },
        enabled = nameValid && password.isNotEmpty() && vm.busy == null,
      ) { Text("Save") }
    },
  ) { modifier ->
    Column(
      modifier.fillMaxSize().imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (isNew) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it.trim() },
          label = { Text("Name") },
          placeholder = { Text("web/example.com") },
          supportingText = { Text("Use / to put the entry in a folder") },
          isError = name.isNotEmpty() && !nameValid,
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
          modifier = Modifier.fillMaxWidth(),
        )
      }
      OutlinedTextField(
        value = password,
        onValueChange = { password = it.replace("\n", "") },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { password = PasswordGenerator.generate() }) { Text("Generate") }
        OutlinedButton(onClick = { password = PasswordGenerator.generate(symbols = false) }) { Text("Generate (no symbols)") }
      }
      OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Notes") },
        placeholder = { Text("user: me\nurl: https://example.com") },
        minLines = 4,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
      )
      Text(
        "The entry is encrypted to the recipients in the nearest .age-recipients file, then committed and pushed.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }

  if (confirmDiscard) {
    AlertDialog(
      onDismissRequest = { confirmDiscard = false },
      title = { Text("Discard changes?") },
      confirmButton = {
        TextButton(onClick = {
          confirmDiscard = false
          vm.screen = if (isNew) Screen.Entries else Screen.Entry(editor.name!!)
        }) { Text("Discard") }
      },
      dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
    )
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
private fun SettingsScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
  val context = LocalContext.current
  val hasRepo = vm.passage.repo.exists()
  var url by rememberSaveable { mutableStateOf(vm.passage.remoteUrl ?: "") }
  var authorName by rememberSaveable { mutableStateOf(vm.passage.authorName) }
  var authorEmail by rememberSaveable { mutableStateOf(vm.passage.authorEmail) }
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

  Page("Settings", vm, snackbar, onBack = if (hasRepo) ({ vm.screen = Screen.Entries }) else null) { modifier ->
    Column(
      modifier.fillMaxSize().imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
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
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
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

      HorizontalDivider()
      Text("Commit author", style = MaterialTheme.typography.titleMedium)
      OutlinedTextField(
        value = authorName,
        onValueChange = { authorName = it; vm.passage.authorName = it.trim() },
        label = { Text("Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = authorEmail,
        onValueChange = { authorEmail = it; vm.passage.authorEmail = it.trim() },
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}
