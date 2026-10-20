package com.jadenjsj.betterflow

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun BrowserSignInControls(
    busy: Boolean,
    onBusyChanged: (Boolean) -> Unit,
    onAuthenticated: (WisprSession) -> Unit,
    onStatus: (String) -> Unit,
) {
    val context = LocalContext.current
    val client = remember { WisprBrowserAuth() }
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableStateOf<WisprBrowserAuth.Attempt?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun openBrowser(value: WisprBrowserAuth.Attempt) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value.loginUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
    }
    fun signIn(google: Boolean) {
        val value = WisprBrowserAuth.Attempt(google = google)
        attempt = value
        onBusyChanged(true)
        job = scope.launch {
            try {
                onStatus("Finish sign-in in your browser, then return here. Waiting up to 3 minutes…")
                try { openBrowser(value) } catch (_: Exception) {
                    throw IllegalStateException("No browser could open Wispr sign-in. Install/enable a browser or use email sign-in below.")
                }
                val session = client.awaitSession(value)
                onAuthenticated(session)
                onStatus("Browser sign-in complete. Wispr account saved.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onStatus(failure.message ?: "Browser sign-in failed. Try again or use the existing sign-in options.")
            } finally {
                // A cancelled attempt must not clear a newer attempt's state.
                if (attempt === value) { attempt = null; onBusyChanged(false) }
            }
        }
    }

    Button(enabled = !busy, onClick = { signIn(true) }) { Text("Sign in with Google") }
    TextButton(enabled = !busy, onClick = { signIn(false) }) { Text("Other browser sign-in") }
    attempt?.let { value ->
        Text("Waiting for browser approval. Your existing account stays active until sign-in succeeds.")
        TextButton(onClick = {
            runCatching { openBrowser(value) }.onFailure { onStatus("Could not reopen the browser.") }
        }) { Text("Reopen sign-in page") }
        TextButton(onClick = {
            job?.cancel()
            attempt = null
            onBusyChanged(false)
            onStatus("Browser sign-in cancelled. Existing accounts are unchanged.")
        }) { Text("Cancel browser sign-in") }
    }
}
