package com.jadenjsj.betterflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.math.roundToInt

/** Fetch on screen resume or explicit refresh, never through a periodic usage poll. */
@Composable
fun WisprUsageCard(session: WisprSession?, client: WisprClient) {
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var usage by remember(session?.email, session?.userId) { mutableStateOf<WisprUsage?>(null) }
    var error by remember(session?.email, session?.userId) { mutableStateOf<String?>(null) }
    var checkedAt by remember(session?.email, session?.userId) { mutableStateOf<Long?>(null) }
    var loading by remember(session?.email, session?.userId) { mutableStateOf(false) }
    var refreshJob by remember(session?.email, session?.userId) { mutableStateOf<Job?>(null) }

    fun refresh() {
        if (session == null || loading) return
        loading = true
        refreshJob = scope.launch {
            try {
                usage = client.usage()
                checkedAt = System.currentTimeMillis()
                error = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "Could not load weekly usage"
            } finally {
                loading = false
            }
        }
    }

    // Token renewal does not change account identity or restart a running usage request.
    DisposableEffect(owner, session?.email, session?.userId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            refreshJob?.cancel()
            loading = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Wispr weekly usage", style = MaterialTheme.typography.titleMedium)
            if (session == null) {
                Text("Sign in to see your weekly allowance.")
            } else {
                usage?.let { value ->
                    value.plan?.let { Text("Plan: $it") }
                    Text("Words this week: ${value.usedWords?.toString() ?: "unavailable"}")
                    value.usageFraction?.let { fraction ->
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (value.exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text("${(fraction * 100).roundToInt()}% of weekly allowance used",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    when (value.isLimited) {
                        true -> {
                            Text("Weekly allowance: ${value.limitWords?.toString() ?: "unavailable"} words")
                            value.remainingWords?.let { Text("Remaining: $it words") }
                        }
                        false -> Text("No Basic-plan weekly word cap.")
                        null -> Text("Plan limit could not be verified; usage is not assumed to be unlimited.")
                    }
                    if (value.exhausted) {
                        Text(
                            if (value.hardLimitReached) "Weekly usage limit reached. Wait for reset or change your Wispr plan. Your saved recordings can be retried later."
                            else "Weekly allowance used up. A temporary grace allowance may remain; check your Wispr plan before continuing.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("Weekly reset: ${utcDate(value.resetAtEpochSeconds * 1000L)}")
                }
                if (loading) Text("Refreshing usage…")
                error?.let {
                    Text("Usage refresh failed: $it", color = MaterialTheme.colorScheme.error)
                    if (usage != null) Text("The values above are from the last successful check.")
                }
                checkedAt?.let { Text("Last checked: ${utcDate(it)}", style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = ::refresh, enabled = !loading) { Text("Refresh usage") }
            }
        }
    }
}

private fun utcDate(epochMillis: Long): String = DateFormat.getDateTimeInstance().apply {
    timeZone = TimeZone.getTimeZone("UTC")
}.format(Date(epochMillis)) + " UTC"
