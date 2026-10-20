package io.crashbox.passage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    // `adb shell am start -n io.crashbox.passage/.MainActivity --ez run true`
                    SpikeScreen(Modifier.padding(padding), autoRun = intent.getBooleanExtra("run", false))
                }
            }
        }
    }
}

@Composable
fun SpikeScreen(modifier: Modifier = Modifier, autoRun: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Spike.Check>>(emptyList()) }
    fun runChecks() {
        running = true
        scope.launch {
            results = withContext(Dispatchers.Default) { Spike.run(context) }
            running = false
        }
    }
    LaunchedEffect(Unit) { if (autoRun) runChecks() }

    Column(
        modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            enabled = !running,
            onClick = { runChecks() },
        ) {
            Text(if (running) "Running…" else "Run checks")
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (r in results) {
                    Column {
                        Text(
                            "${if (r.ok) "✓" else "✗"} ${r.name}",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(r.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
