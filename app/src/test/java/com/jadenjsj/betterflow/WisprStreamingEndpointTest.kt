package com.jadenjsj.betterflow

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class WisprStreamingEndpointTest {
    @Test fun routingIsCachedAndNeverSendsCredentials() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("model-v31pl413.grpc.api.baseten.co\n").build())
            server.enqueue(MockResponse.Builder().body("inference.wisprflow.com").build())
            var now = 0L
            val endpoint = WisprStreamingEndpoint(routingUrl = server.url("/dictation_url.txt").toString(), nowNanos = { now })
            assertEquals("model-v31pl413.grpc.api.baseten.co", endpoint.resolve())
            assertEquals("model-v31pl413.grpc.api.baseten.co", endpoint.resolve())
            assertEquals(1, server.requestCount)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["baseten-authorization"])
            now += TimeUnit.MINUTES.toNanos(6)
            assertEquals("inference.wisprflow.com", endpoint.resolve())
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun untrustedDiscoveryAndErrorsKeepLastValidHost() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("evil.test").build())
            server.enqueue(MockResponse.Builder().code(503).body("offline").build())
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://evil.test/").build())
            var now = 0L
            val endpoint = WisprStreamingEndpoint(routingUrl = server.url("/").toString(), nowNanos = { now })
            repeat(3) {
                assertEquals(WisprProtocol.DEFAULT_STREAMING_HOST, endpoint.resolve())
                now += TimeUnit.MINUTES.toNanos(6)
            }
            assertEquals(3, server.requestCount)
        }
    }
}
