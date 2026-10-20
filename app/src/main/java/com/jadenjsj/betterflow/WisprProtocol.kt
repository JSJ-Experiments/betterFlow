package com.jadenjsj.betterflow

/** Wire compatibility verified against Wispr Flow Android 2.8.0 (179). */
internal object WisprProtocol {
    const val CLIENT_HEADER_NAME = "X-Wispr-Client"
    const val CLIENT_HEADER = "android/2.8.0/179"
    const val DEFAULT_MODEL_ID = "v31pl413"
    const val DEFAULT_STREAMING_HOST = "inference.wisprflow.com"
    const val ROUTING_URL = "https://inference-info.wisprflow.com/dictation_url.txt"

    fun streamingHost(raw: String): String? = raw.trim().lowercase().takeIf {
        it in setOf(
            DEFAULT_STREAMING_HOST,
            "model-$DEFAULT_MODEL_ID.grpc.api.baseten.co",
            "wispr-ai-prod--prod-grpc-server-flowgrpcserver.us-east.modal.direct",
        )
    }
}
