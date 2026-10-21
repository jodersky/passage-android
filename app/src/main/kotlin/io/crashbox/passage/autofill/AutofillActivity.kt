package io.crashbox.passage.autofill

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.crashbox.passage.App
import io.crashbox.passage.UserAuthenticator
import io.crashbox.passage.describe
import io.crashbox.passage.store.EntryContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Started when the user picks a suggestion of [PassageAutofillService]:
 * lets the user pick an entry (for "Search Passage…"), asks them to
 * authenticate, decrypts the entry and returns the values to fill.
 */
class AutofillActivity : ComponentActivity() {
  private val passage by lazy { (application as App).passage }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    setResult(Activity.RESULT_CANCELED)
    val entry = intent.getStringExtra(EXTRA_ENTRY)
    if (entry != null) {
      fill(entry)
    } else {
      setContent {
        val context = LocalContext.current
        val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        MaterialTheme(colorScheme = colors) {
          Picker(passage.store.entries(), onPick = ::fill)
        }
      }
    }
  }

  private fun fill(name: String) {
    val target = intent.getStringExtra(EXTRA_TARGET) ?: "?"
    val usernameId = intent.getParcelableExtra(EXTRA_USERNAME_ID, AutofillId::class.java)
    val passwordId = intent.getParcelableExtra(EXTRA_PASSWORD_ID, AutofillId::class.java)
    // the prompt names the target, so that the user notices an app or site
    // that should not get this entry
    val authenticator = UserAuthenticator(this, "Fill $name", "into $target")
    lifecycleScope.launch {
      try {
        val content = withContext(Dispatchers.IO) { passage.decrypt(name, authenticator::authorize) }
        val username = usernameOf(content) ?: passage.fallbackUsername.takeIf { it.isNotBlank() }
        val dataset = Dataset.Builder()
        var any = false
        if (usernameId != null && username != null) {
          dataset.setField(usernameId, Field.Builder().setValue(AutofillValue.forText(username)).build())
          any = true
        }
        // entries with only a one-time password have no password to fill
        val password = EntryContent.parse(content).password
        if (passwordId != null && password.isNotEmpty()) {
          dataset.setField(passwordId, Field.Builder().setValue(AutofillValue.forText(password)).build())
          any = true
        }
        if (any) {
          setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset.build()))
        } else {
          Toast.makeText(this@AutofillActivity, "$name has nothing to fill here", Toast.LENGTH_LONG).show()
        }
      } catch (e: Exception) {
        Toast.makeText(this@AutofillActivity, describe(e), Toast.LENGTH_LONG).show()
      }
      finish()
    }
  }

  companion object {
    private const val EXTRA_ENTRY = "entry"
    private const val EXTRA_USERNAME_ID = "username_id"
    private const val EXTRA_PASSWORD_ID = "password_id"
    private const val EXTRA_TARGET = "target"

    /** @param entry the entry to fill, or null to let the user pick one */
    fun intent(context: Context, entry: String?, usernameId: AutofillId?, passwordId: AutofillId?, target: String): Intent =
      Intent(context, AutofillActivity::class.java)
        .putExtra(EXTRA_ENTRY, entry)
        .putExtra(EXTRA_USERNAME_ID, usernameId)
        .putExtra(EXTRA_PASSWORD_ID, passwordId)
        .putExtra(EXTRA_TARGET, target)
  }
}

@androidx.compose.runtime.Composable
private fun Picker(entries: List<String>, onPick: (String) -> Unit) {
  var query by remember { mutableStateOf("") }
  var picked by remember { mutableStateOf(false) }
  Surface(Modifier.fillMaxSize()) {
    Column {
      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Search Passage") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
      )
      LazyColumn {
        items(entries.filter { it.contains(query.trim(), ignoreCase = true) }, key = { it }) { name ->
          ListItem(
            headlineContent = { Text(name) },
            modifier = Modifier.clickable(enabled = !picked) {
              picked = true
              onPick(name)
            },
          )
          HorizontalDivider()
        }
      }
    }
  }
}
