package com.jadenjsj.betterflow

import android.content.ClipData
import android.content.ClipboardManager
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import java.text.DateFormat
import java.util.Date
import org.json.JSONArray

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { HistoryStore(context) }
    val client = remember { WisprClient(context) }
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<HistoryStore.Entry>>(emptyList()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() { entries = withContext(Dispatchers.IO) { store.list() } }
    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) {
        onDispose { player?.release() }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            message = "Exporting…"
            message = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use(store::export)
                        ?: error("Could not open export destination")
                }
                "Export complete"
            } catch (t: Throwable) { "Export failed: ${t.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("← Settings") }
            Button(onClick = { exportLauncher.launch("betterflow-history.zip") }, enabled = entries.isNotEmpty()) {
                Text("Export ZIP")
            }
        }
        Text("History", style = MaterialTheme.typography.headlineMedium)
        Text("Lossless WAV + transcript and capture metadata stay on this device until you remove the app. Export includes dataset.jsonl. Retries only run when tapped.", style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(DateFormat.getDateTimeInstance().format(Date(entry.createdAt)) + " · " + entry.origin,
                            style = MaterialTheme.typography.titleSmall)
                        Text("${entry.status} · ${entry.audioBytes / 32000.0}s · ${entry.attempts} attempt(s)" +
                            if (entry.engine.isNotBlank()) " · ${entry.engine}" else "",
                            style = MaterialTheme.typography.bodySmall)
                        if (entry.insertion != "not_attempted") Text("Insertion: ${entry.insertion}", style = MaterialTheme.typography.bodySmall)
                        if (entry.error.isNotBlank()) Text(entry.error, color = MaterialTheme.colorScheme.error)
                        if (entry.transcript.isNotBlank()) SelectionContainer { Text(entry.transcript) }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = {
                                if (playingId == entry.id) {
                                    player?.release(); player = null; playingId = null
                                } else try {
                                    player?.release()
                                    player = MediaPlayer().apply {
                                        setDataSource(store.wavFile(entry.id).absolutePath)
                                        setOnCompletionListener { it.release(); player = null; playingId = null }
                                        prepare()
                                        start()
                                    }
                                    playingId = entry.id
                                } catch (t: Throwable) { message = "Playback failed: ${t.message}" }
                            }, enabled = entry.audioBytes > 0) { Text(if (playingId == entry.id) "Stop" else "Play") }
                            TextButton(onClick = {
                                scope.launch {
                                    busyId = entry.id
                                    val raw = JSONArray()
                                    try {
                                        withContext(Dispatchers.IO) {
                                            store.beginAttempt(entry.id)
                                            val text = client.transcribeLegacyPcm(store.pcm(entry.id)) { raw.put(it) }.trim()
                                            check(text.isNotEmpty()) { "Wispr returned empty text" }
                                            store.result(entry.id, text, "legacy_http_retry", raw.toString())
                                            HistoryNotifier.clear(context, entry.id)
                                        }
                                        message = "Retry succeeded. Copy the text below."
                                    } catch (t: Throwable) {
                                        withContext(Dispatchers.IO) { store.failure(entry.id, t.message ?: "Retry failed", raw.toString()) }
                                        message = "Retry failed: ${t.message}"
                                    } finally {
                                        busyId = null
                                        refresh()
                                    }
                                }
                            }, enabled = entry.audioBytes > 0 && busyId == null &&
                                entry.status != "capturing" &&
                                (entry.status != "transcribing" || System.currentTimeMillis() - entry.updatedAt > 180_000)
                            ) { Text(if (busyId == entry.id) "Retrying…" else "Retry") }
                            if (entry.transcript.isNotBlank()) TextButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(ClipData.newPlainText("betterFlow transcript", entry.transcript))
                                Toast.makeText(context, "Transcript copied; paste it where needed", Toast.LENGTH_SHORT).show()
                            }) { Text("Copy text") }
                        }
                    }
                }
            }
        }
    }
}
