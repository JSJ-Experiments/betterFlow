package com.jadenjsj.betterflow

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            startForegroundService(
                Intent(this, OverlayService::class.java)
                    .setAction(OverlayService.ACTION_WAKE),
            )
        }
        setContent { MaterialTheme { SettingsScreen() } }
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val auth = remember { AuthStore(context) }
    val client = remember { WisprClient(context) }
    val coroutine = rememberCoroutineScope()
    var session by remember { mutableStateOf(auth.load()) }
    var backend by remember { mutableStateOf(Prefs.backend(context)) }
    var gboardMicEnabled by remember { mutableStateOf(Prefs.gboardMicEnabled(context)) }
    var bubbleEnabled by remember { mutableStateOf(Prefs.bubbleVisible(context)) }
    var legacyTranscription by remember { mutableStateOf(Prefs.legacyTranscription(context)) }
    var streamingKeyConfigured by remember { mutableStateOf(Prefs.streamingApiKey(context) != null || BuildConfig.WISPR_BASETEN_API_KEY.isNotBlank()) }
    var streamingApiKey by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(session?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var sessionJson by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready") }
    var rootStatus by remember { mutableStateOf("Not checked") }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        status = if (result.values.all { it }) "Runtime permissions granted" else "Some runtime permissions were denied"
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("betterFlow", style = MaterialTheme.typography.headlineMedium)
        Text("Gboard mic voice typing + optional floating bubble. Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}).")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice trigger", style = MaterialTheme.typography.titleMedium)
                Text("Gboard mic is the primary trigger. Realtime mode streams 16 kHz mono PCM16 to Wispr in 100 ms chunks. The mic is matched semantically, so floating, split, full-width, rotation, and ordinary toolbar movement do not rely on fixed coordinates.")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Use Gboard microphone")
                        Text("Tap Gboard's voice key to start/finish betterFlow.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = gboardMicEnabled,
                        onCheckedChange = { enabled ->
                            gboardMicEnabled = enabled
                            Prefs.setGboardMicEnabled(context, enabled)
                            InputInjector.notifyConfigChanged(context)
                            status = if (enabled) "Gboard mic trigger enabled" else "Gboard mic restored to original behavior"
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Legacy transcription engine")
                        Text("Send the completed WAV over the old HTTP endpoint instead of streaming. Streaming mode still falls back here automatically if the live stream fails.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = legacyTranscription,
                        onCheckedChange = { enabled ->
                            legacyTranscription = enabled
                            Prefs.setLegacyTranscription(context, enabled)
                            status = if (enabled) "Legacy whole-file transcription enabled" else "Realtime streaming enabled"
                        },
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Streaming credential: ${if (streamingKeyConfigured) "configured" else "missing"}")
                    OutlinedTextField(
                        value = streamingApiKey,
                        onValueChange = { streamingApiKey = it },
                        label = { Text("Streaming API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                Prefs.setStreamingApiKey(context, streamingApiKey)
                                streamingKeyConfigured = streamingApiKey.isNotBlank() || BuildConfig.WISPR_BASETEN_API_KEY.isNotBlank()
                                streamingApiKey = ""
                                status = "Streaming credential saved privately on this device"
                            },
                            enabled = streamingApiKey.isNotBlank(),
                        ) { Text("Save streaming key") }
                        TextButton(onClick = {
                            Prefs.setStreamingApiKey(context, null)
                            streamingApiKey = ""
                            streamingKeyConfigured = BuildConfig.WISPR_BASETEN_API_KEY.isNotBlank()
                            status = "Device streaming credential cleared"
                        }) { Text("Clear") }
                    }
                    Text("Stored only in betterFlow's private app data. It is never added to Git or release metadata.", style = MaterialTheme.typography.bodySmall)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Floating microphone")
                        Text("Legacy always-on-top trigger; optional fallback.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = bubbleEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !Settings.canDrawOverlays(context)) {
                                status = "Overlay permission is required only for the floating microphone"
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            } else {
                                bubbleEnabled = enabled
                                Prefs.setBubbleVisible(context, enabled)
                                val serviceIntent = Intent(context, OverlayService::class.java)
                                    .setAction(if (enabled) OverlayService.ACTION_SHOW else OverlayService.ACTION_HIDE)
                                context.startForegroundService(serviceIntent)
                                status = if (enabled) "Floating microphone enabled" else "Floating microphone disabled"
                            }
                        },
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Runtime", style = MaterialTheme.typography.titleMedium)
                Text("Microphone permission is required for both triggers. Overlay permission is needed only when the floating microphone is enabled.")
                Text("Overlay permission: ${if (Settings.canDrawOverlays(context)) "granted" else "not granted (optional)"}")
                Text("Root: $rootStatus")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val permissions = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }.toTypedArray()
                        permissionLauncher.launch(permissions)
                    }) { Text("Grant runtime perms") }
                    TextButton(onClick = {
                        coroutine.launch { rootStatus = if (RootShell.hasRoot()) "available" else "not granted" }
                    }) { Text("Check root") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Text insertion", style = MaterialTheme.typography.titleMedium)
                Text("Auto prefers direct InputConnection through LSPosed and falls back to clipboard + root KEYCODE_PASTE.")
                InputBackend.entries.forEach { option ->
                    Row {
                        RadioButton(
                            selected = backend == option,
                            onClick = {
                                backend = option
                                Prefs.setBackend(context, option)
                            },
                        )
                        Text(
                            when (option) {
                                InputBackend.AUTO -> "Auto (recommended)"
                                InputBackend.LSPOSED -> "LSPosed InputConnection only"
                                InputBackend.CLIPBOARD_PASTE -> "Clipboard + root paste"
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                Text("For direct mode and the Gboard mic trigger, enable this APK as an LSPosed module and scope it to Gboard. Restarting Gboard reloads hook-code updates without rebooting Android.")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wispr authentication", style = MaterialTheme.typography.titleMedium)
                Text(if (session != null) "Signed in as ${session?.email}" else "Not signed in")
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (not stored)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        coroutine.launch {
                            status = "Signing in…"
                            try {
                                session = client.login(email.trim(), password)
                                password = ""
                                status = "Wispr session saved"
                            } catch (t: Throwable) {
                                status = "Login failed: ${t.message}"
                            }
                        }
                    },
                    enabled = email.isNotBlank() && password.isNotBlank(),
                ) { Text("Email login") }

                Spacer(Modifier.height(4.dp))
                Text("Or paste the existing ~/.config/wispr-linux/session.json from the dev machine:")
                OutlinedTextField(
                    value = sessionJson,
                    onValueChange = { sessionJson = it },
                    label = { Text("Session JSON") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        try {
                            session = auth.importJson(sessionJson)
                            sessionJson = ""
                            status = "Imported Wispr session"
                        } catch (t: Throwable) {
                            status = "Import failed: ${t.message}"
                        }
                    }, enabled = sessionJson.isNotBlank()) { Text("Import session") }
                    TextButton(onClick = {
                        auth.clear()
                        session = null
                        status = "Wispr session cleared"
                    }) { Text("Clear") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text(status)
            }
        }
    }
}
