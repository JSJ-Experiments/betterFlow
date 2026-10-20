package com.jadenjsj.betterflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            RootShell.setBubbleBootEnabled(Prefs.bubbleVisible(this@MainActivity))
        }
        val openHistory = intent?.getBooleanExtra(HistoryNotifier.EXTRA_OPEN_HISTORY, false) == true
        setContent { MaterialTheme { SettingsScreen(openHistory) } }
    }
}

private fun refreshOverlayConfig(context: Context) {
    if (!Prefs.bubbleVisible(context)) return
    runCatching {
        context.startForegroundService(
            Intent(context, OverlayService::class.java)
                .setAction(OverlayService.ACTION_REFRESH_CONFIG),
        )
    }
}

@Composable
private fun SettingsScreen(openHistory: Boolean) {
    val context = LocalContext.current
    val auth = remember { AuthStore(context) }
    val client = remember { WisprClient(context) }
    val coroutine = rememberCoroutineScope()
    var session by remember { mutableStateOf(auth.load()) }
    var accounts by remember { mutableStateOf(auth.savedAccounts()) }
    var activeAccountId by remember { mutableStateOf(auth.activeAccountId()) }
    var removeAccountId by remember { mutableStateOf<String?>(null) }
    var backend by remember { mutableStateOf(Prefs.backend(context)) }
    var autoCopyTranscript by remember { mutableStateOf(Prefs.autoCopyTranscript(context)) }
    var gboardMicEnabled by remember { mutableStateOf(Prefs.gboardMicEnabled(context)) }
    var bubbleEnabled by remember { mutableStateOf(Prefs.bubbleVisible(context)) }
    var bubbleSizeDp by remember { mutableStateOf(Prefs.bubbleSizeDp(context)) }
    var bubbleOpacityPercent by remember { mutableStateOf(Prefs.bubbleOpacityPercent(context)) }
    var notificationPriority by remember { mutableStateOf(Prefs.notificationPriority(context)) }
    var legacyTranscription by remember { mutableStateOf(Prefs.legacyTranscription(context)) }
    var preservePreTapAudio by remember { mutableStateOf(Prefs.preservePreTapAudio(context)) }
    var audioDrainTimeoutMs by remember { mutableStateOf(Prefs.audioDrainTimeoutMs(context)) }
    var streamingKeyConfigured by remember { mutableStateOf(Prefs.streamingApiKey(context) != null || BuildConfig.WISPR_BASETEN_API_KEY.isNotBlank()) }
    var streamingApiKey by remember { mutableStateOf("") }
    var email by remember { mutableStateOf(session?.email.orEmpty()) }
    var password by remember { mutableStateOf("") }
    // Keep the short-lived challenge in memory only, never in preferences or history.
    var emailVerification by remember { mutableStateOf<WisprClient.EmailVerificationRequired?>(null) }
    var verificationCode by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var sessionJson by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready") }
    var rootStatus by remember { mutableStateOf("Not checked") }
    var showHistory by remember { mutableStateOf(openHistory) }

    fun reloadAccounts() {
        accounts = auth.savedAccounts()
        activeAccountId = auth.activeAccountId()
        session = auth.load()
    }

    removeAccountId?.let { id ->
        val account = accounts.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { removeAccountId = null },
            title = { Text("Remove saved account?") },
            text = { Text("Remove ${account?.session?.email.orEmpty()} from this device? Saved recordings and transcripts are kept. Other accounts are unchanged.") },
            confirmButton = { TextButton(onClick = {
                auth.removeAccount(id)
                removeAccountId = null
                reloadAccounts()
                email = session?.email.orEmpty()
                emailVerification = null
                verificationCode = ""
                status = "Saved account removed"
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeAccountId = null }) { Text("Cancel") } },
        )
    }

    if (showHistory) {
        HistoryScreen(onBack = { showHistory = false })
        return
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        status = if (result.values.all { it }) "Runtime permissions granted" else "Some runtime permissions were denied"
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("betterFlow", style = MaterialTheme.typography.headlineMedium)
        Text("Gboard mic voice typing + optional floating bubble. Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}).")
        Button(onClick = { showHistory = true }) { Text("History · retry, copy, play, export") }

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
                        Text("Preserve words before stop tap")
                        Text(
                            "Drain Android's pending microphone buffer, but discard every sample captured after you tap stop.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = preservePreTapAudio,
                        onCheckedChange = { enabled ->
                            preservePreTapAudio = enabled
                            Prefs.setPreservePreTapAudio(context, enabled)
                            status = if (enabled) "Pre-tap audio preservation enabled" else "Microphone will stop immediately"
                        },
                    )
                }

                Text("Pre-tap buffer drain timeout: $audioDrainTimeoutMs ms")
                Slider(
                    value = audioDrainTimeoutMs.toFloat(),
                    onValueChange = { audioDrainTimeoutMs = (it / 50f).roundToInt() * 50 },
                    onValueChangeFinished = {
                        Prefs.setAudioDrainTimeoutMs(context, audioDrainTimeoutMs)
                        status = "Pre-tap buffer drain timeout set to $audioDrainTimeoutMs ms"
                    },
                    valueRange = Prefs.MIN_AUDIO_DRAIN_TIMEOUT_MS.toFloat()..Prefs.MAX_AUDIO_DRAIN_TIMEOUT_MS.toFloat(),
                    enabled = preservePreTapAudio,
                )
                Text(
                    "This is a maximum drain wait, not extra recorded time. The transcript cutoff remains the stop-tap timestamp.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Whole-file transcription engine")
                        Text("Send the completed WAV over HTTP instead of streaming. Streaming mode also uses this fallback if the live stream fails.", style = MaterialTheme.typography.bodySmall)
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
                        Text(
                            "Optional fallback. Drag it partly past the left/right edge to tuck it away while keeping a handle visible.",
                            style = MaterialTheme.typography.bodySmall,
                        )
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
                                coroutine.launch { RootShell.setBubbleBootEnabled(enabled) }
                                val serviceIntent = Intent(context, OverlayService::class.java)
                                if (enabled) {
                                    context.startForegroundService(serviceIntent.setAction(OverlayService.ACTION_SHOW))
                                } else {
                                    context.stopService(serviceIntent)
                                }
                                status = if (enabled) "Floating microphone enabled" else "Floating microphone disabled"
                            }
                        },
                    )
                }

                Text("Floating button size: $bubbleSizeDp dp")
                Slider(
                    value = bubbleSizeDp.toFloat(),
                    onValueChange = { bubbleSizeDp = it.roundToInt() },
                    onValueChangeFinished = {
                        Prefs.setBubbleSizeDp(context, bubbleSizeDp)
                        refreshOverlayConfig(context)
                        status = "Floating button size set to $bubbleSizeDp dp"
                    },
                    valueRange = Prefs.MIN_BUBBLE_SIZE_DP.toFloat()..Prefs.MAX_BUBBLE_SIZE_DP.toFloat(),
                )

                Text("Floating button opacity: $bubbleOpacityPercent%")
                Slider(
                    value = bubbleOpacityPercent.toFloat(),
                    onValueChange = { bubbleOpacityPercent = it.roundToInt() },
                    onValueChangeFinished = {
                        Prefs.setBubbleOpacityPercent(context, bubbleOpacityPercent)
                        refreshOverlayConfig(context)
                        status = "Floating button opacity set to $bubbleOpacityPercent%"
                    },
                    valueRange = Prefs.MIN_BUBBLE_OPACITY_PERCENT.toFloat()..Prefs.MAX_BUBBLE_OPACITY_PERCENT.toFloat(),
                )

                Spacer(Modifier.height(2.dp))
                Text("Foreground notification priority")
                Text(
                    "Choose how prominently Android posts betterFlow's persistent service notification.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NotificationPriority.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = notificationPriority == option,
                            onClick = {
                                notificationPriority = option
                                Prefs.setNotificationPriority(context, option)
                                refreshOverlayConfig(context)
                                status = "Notification priority set to ${option.displayName}"
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(option.displayName)
                            Text(
                                when (option) {
                                    NotificationPriority.MIN -> "Least prominent / silent where Android permits it."
                                    NotificationPriority.LOW -> "Quiet persistent notification (current default)."
                                    NotificationPriority.DEFAULT -> "Normal notification importance."
                                    NotificationPriority.HIGH -> "Most prominent; may produce a heads-up alert."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    "If you manually change a betterFlow channel in Android notification settings, Android may keep that manual override.",
                    style = MaterialTheme.typography.bodySmall,
                )
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-copy completed transcripts")
                        Text("Also copy successful bubble, Gboard, and History retry results to the clipboard. This replaces your current clipboard; insertion is unchanged.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = autoCopyTranscript, onCheckedChange = {
                        autoCopyTranscript = it
                        Prefs.setAutoCopyTranscript(context, it)
                    })
                }
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
                Text("Saved accounts", style = MaterialTheme.typography.titleSmall)
                Text("Select an account for new transcriptions. Each account has its own weekly allowance. Finish recording/processing before switching; an in-flight request will not be moved to another account.", style = MaterialTheme.typography.bodySmall)
                accounts.forEach { account ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = account.id == activeAccountId,
                            enabled = !signingIn,
                            onClick = {
                                auth.selectAccount(account.id)
                                reloadAccounts()
                                email = session?.email.orEmpty()
                                password = ""
                                emailVerification = null
                                verificationCode = ""
                                status = "Switched to ${session?.email}"
                            },
                        )
                        Text(account.session.email, Modifier.weight(1f))
                        TextButton(enabled = !signingIn, onClick = { removeAccountId = account.id }) { Text("Remove") }
                    }
                }
                Text("Add another account by signing in or importing its session below. Saved sessions stay private on this device.", style = MaterialTheme.typography.bodySmall)
                BrowserSignInControls(
                    busy = signingIn,
                    onBusyChanged = { signingIn = it },
                    onAuthenticated = { signedIn ->
                        auth.activate(signedIn)
                        reloadAccounts()
                        email = signedIn.email
                        password = ""
                        emailVerification = null
                        verificationCode = ""
                    },
                    onStatus = { status = it },
                )
                Text("Existing email/password sign-in", style = MaterialTheme.typography.titleSmall)
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
                            signingIn = true
                            emailVerification = null
                            verificationCode = ""
                            status = "Signing in…"
                            try {
                                session = client.login(email.trim(), password)
                                reloadAccounts()
                                password = ""
                                status = "Wispr session saved"
                            } catch (challenge: WisprClient.EmailVerificationRequired) {
                                password = ""
                                emailVerification = challenge
                                status = challenge.message.orEmpty()
                            } catch (t: Throwable) {
                                status = "Login failed: ${t.message}"
                            } finally {
                                signingIn = false
                            }
                        }
                    },
                    enabled = !signingIn && email.isNotBlank() && password.isNotBlank(),
                ) { Text("Sign in / add account") }

                emailVerification?.let { challenge ->
                    Text("Enter the code sent to ${challenge.email}.")
                    OutlinedTextField(
                        value = verificationCode,
                        onValueChange = { verificationCode = it },
                        label = { Text("Email verification code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = !signingIn && verificationCode.isNotBlank(),
                        onClick = {
                            coroutine.launch {
                                signingIn = true
                                status = "Verifying…"
                                try {
                                    session = client.verifyEmailOtp(challenge.email, challenge.pendingToken, verificationCode)
                                    reloadAccounts()
                                    emailVerification = null
                                    verificationCode = ""
                                    status = "Wispr session saved"
                                } catch (next: WisprClient.EmailVerificationRequired) {
                                    emailVerification = next
                                    verificationCode = ""
                                    status = next.message.orEmpty()
                                } catch (t: Throwable) {
                                    status = "Verification failed: ${t.message}. Try again or sign in for a new code."
                                } finally {
                                    signingIn = false
                                }
                            }
                        },
                    ) { Text("Verify email") }
                }

                Spacer(Modifier.height(4.dp))
                Text("Or import an existing Wispr session JSON (access_token, refresh_token, and optional provider/user_id):")
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
                            reloadAccounts()
                            sessionJson = ""
                            emailVerification = null
                            verificationCode = ""
                            status = "Imported Wispr session"
                        } catch (t: Throwable) {
                            status = "Import failed: ${t.message}"
                        }
                    }, enabled = !signingIn && sessionJson.isNotBlank()) { Text("Import session") }
                    TextButton(enabled = !signingIn, onClick = {
                        auth.clear()
                        reloadAccounts()
                        email = session?.email.orEmpty()
                        emailVerification = null
                        verificationCode = ""
                        status = "Active saved account removed"
                    }) { Text("Sign out active account") }
                }
            }
        }

        WisprUsageCard(session, client)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Status", style = MaterialTheme.typography.titleMedium)
                Text(status)
            }
        }
    }
}
