package com.jadenjsj.betterflow

import com.jadenjsj.betterflow.proto.Heartbeat
import com.jadenjsj.betterflow.proto.Response
import com.jadenjsj.betterflow.proto.Result
import com.jadenjsj.betterflow.proto.State
import com.jadenjsj.betterflow.proto.Text
import com.jadenjsj.betterflow.proto.Transcription
import org.junit.Assert.*
import org.junit.Test

class WisprStreamingProtocolTest {
    @Test fun discoveryAcceptsOnlyShippedHosts() {
        assertEquals("inference.wisprflow.com", WisprProtocol.streamingHost("  INFERENCE.WISPRFLOW.COM\n"))
        assertEquals("model-v31pl413.grpc.api.baseten.co", WisprProtocol.streamingHost("model-v31pl413.grpc.api.baseten.co"))
        for (value in listOf("https://inference.wisprflow.com", "inference.wisprflow.com.evil.test", "evil.test", "", "inference.wisprflow.com/path")) {
            assertNull(value, WisprProtocol.streamingHost(value))
        }
    }

    @Test fun initUsesCurrentVersionAndStableUserIdentity() {
        val request = WisprStreamingClient.buildInitRequest("invalid-token")
        val metadata = request.init.metadata
        assertEquals("00000000-0000-0000-0000-000000000000", metadata.userId)
        assertEquals(metadata.requestId, metadata.sessionId)
        assertEquals(1, metadata.audioEncoding)
        assertEquals(4, metadata.client.platform)
        assertEquals(2, metadata.client.version.major)
        assertEquals(8, metadata.client.version.minor)
        assertEquals(0, metadata.client.version.patch)
        val user = "260c54f6-e58e-4e85-a2da-c034ea48fae4"
        assertEquals(user, WisprStreamingClient.validUserId(user))
        assertEquals("00000000-0000-0000-0000-000000000000", WisprStreamingClient.validUserId("user_workos"))
    }

    @Test fun partialAndHeartbeatDoNotCompleteTranscription() {
        val partials = mutableListOf<String>()
        val responses = WisprStreamingResponses { partials += it }
        assertNull(responses.accept(Response.newBuilder().setState(State.newBuilder().setRawText(Text.newBuilder().setContent("partial"))).build()))
        assertNull(responses.accept(Response.newBuilder().setHeartbeat(Heartbeat.newBuilder().setAudioReceivedSeconds(2f)).build()))
        assertEquals(listOf("partial"), partials)
        val result = responses.accept(finalResponse("final", 1))!!
        assertEquals("final", result.text)
        assertEquals("partial", result.rawText)
        assertEquals(2f, result.audioReceivedSeconds)
    }

    @Test(expected = IllegalStateException::class)
    fun errorResultCannotCommitEvenWithText() {
        WisprStreamingResponses {}.accept(finalResponse("must not insert", 2))
    }

    @Test(expected = IllegalStateException::class)
    fun emptyFinalCannotCommitPartialText() {
        val responses = WisprStreamingResponses {}
        responses.accept(Response.newBuilder().setState(State.newBuilder().setRawText(Text.newBuilder().setContent("partial"))).build())
        responses.accept(finalResponse("", 1))
    }

    @Test fun rawTranscriptResultIsAccepted() {
        assertEquals("raw", WisprStreamingResponses {}.accept(finalResponse("raw", 3))!!.text)
    }

    private fun finalResponse(text: String, status: Int): Response = Response.newBuilder()
        .setResult(Result.newBuilder().setStatus(status).setOutput(Transcription.newBuilder().setPlaintext(text)))
        .build()
}
