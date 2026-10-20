package com.jadenjsj.betterflow

import com.jadenjsj.betterflow.proto.Response

/** Partial state is display-only; only a non-error Result may complete a recording. */
internal class WisprStreamingResponses(private val onPartial: (String) -> Unit) {
    private var raw: String? = null
    private var formatted: String? = null
    private var receivedSeconds: Float? = null

    fun accept(response: Response): WisprStreamingClient.Result? {
        if (response.hasHeartbeat()) receivedSeconds = response.heartbeat.audioReceivedSeconds
        if (response.hasState()) {
            val state = response.state
            val nextRaw = state.takeIf { it.hasRawText() }?.rawText?.content?.takeIf { it.isNotBlank() }
            val nextFormatted = state.takeIf { it.hasFormattedText() }?.formattedText?.content?.takeIf { it.isNotBlank() }
            if (nextRaw != raw || nextFormatted != formatted) {
                raw = nextRaw
                formatted = nextFormatted
                (formatted ?: raw)?.let(onPartial)
            }
        }
        if (!response.hasResult()) return null
        val result = response.result
        check(result.status != 2) { "Wispr streaming transcription returned an error result" }
        val text = result.output.plaintext
        check(text.isNotBlank()) { "Wispr streaming transcription returned no text" }
        val duration = result.takeIf { it.hasAudioDuration() }?.audioDuration?.let {
            it.seconds.toDouble() + it.nanos.toDouble() / 1_000_000_000.0
        }
        return WisprStreamingClient.Result(text, raw, formatted, duration, receivedSeconds)
    }
}
