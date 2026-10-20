package com.jadenjsj.betterflow

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** On-demand routing only: no idle timer, background polling, or credentials on discovery. */
internal class WisprStreamingEndpoint(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .followRedirects(false)
        .build(),
    private val routingUrl: String = WisprProtocol.ROUTING_URL,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var host = WisprProtocol.DEFAULT_STREAMING_HOST
    private var checkedAt: Long? = null

    @Synchronized
    fun resolve(): String {
        val now = nowNanos()
        if (checkedAt?.let { now - it < TimeUnit.MINUTES.toNanos(5) } == true) return host
        checkedAt = now
        try {
            client.newCall(Request.Builder().url(routingUrl).build()).execute().use { response ->
                if (response.isSuccessful) {
                    // Discovery cannot redirect session credentials to an arbitrary host.
                    WisprProtocol.streamingHost(response.peekBody(512).string())?.let { host = it }
                }
            }
        } catch (_: IOException) {
            // Keep the last valid host, initially the shipped default, when discovery is offline.
        }
        return host
    }
}
