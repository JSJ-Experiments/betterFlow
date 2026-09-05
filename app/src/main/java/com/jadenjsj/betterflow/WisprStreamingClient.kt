package com.jadenjsj.betterflow

import android.content.Context
import com.google.protobuf.ByteString
import com.jadenjsj.betterflow.proto.Commit
import com.jadenjsj.betterflow.proto.Request
import com.jadenjsj.betterflow.proto.Response
import com.jadenjsj.betterflow.proto.TranscriptionServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WisprStreamingClient(context: Context) {
    private val wispr = WisprClient(context.applicationContext)

    data class Result(
        val text: String,
        val rawText: String?,
        val formattedText: String?,
        val audioDurationSeconds: Double?,
        val audioReceivedSeconds: Float?,
    )

    suspend fun open(
        modelId: String = DEFAULT_MODEL_ID,
        onPartial: (String) -> Unit = {},
    ): Session {
        val apiKey = BuildConfig.WISPR_BASETEN_API_KEY.trim()
        if (apiKey.isBlank()) {
            throw IllegalStateException("Wispr streaming credential is unavailable in this build")
        }
        val accessToken = wispr.freshAccessToken()
        return Session(
            accessToken = accessToken,
            apiKey = apiKey,
            modelId = modelId,
            onPartial = onPartial,
        )
    }

    class Session internal constructor(
        accessToken: String,
        apiKey: String,
        private val modelId: String,
        private val onPartial: (String) -> Unit,
    ) {
        private val channel: ManagedChannel = OkHttpChannelBuilder
            .forAddress("model-$modelId.grpc.api.baseten.co", 443)
            .useTransportSecurity()
            .build()
        private val result = CompletableDeferred<Result>()
        private val terminal = AtomicBoolean(false)
        private val lock = Any()

        @Volatile private var requestCall: ClientCallStreamObserver<Request>? = null
        @Volatile private var requestObserver: StreamObserver<Request>? = null
        @Volatile private var latestRawText: String? = null
        @Volatile private var latestFormattedText: String? = null
        @Volatile private var latestPlaintext: String = ""
        @Volatile private var latestAudioDurationSeconds: Double? = null
        @Volatile private var latestAudioReceivedSeconds: Float? = null

        init {
            val headers = Metadata().apply {
                put(AUTHORIZATION, "Bearer $accessToken")
                put(BASETEN_AUTHORIZATION, "Api-Key $apiKey")
                put(BASETEN_MODEL_ID, "model-$modelId")
            }
            val stub = TranscriptionServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            val responseObserver = object : ClientResponseObserver<Request, Response> {
                override fun beforeStart(requestStream: ClientCallStreamObserver<Request>) {
                    requestCall = requestStream
                }

                override fun onNext(response: Response) {
                    if (response.hasHeartbeat()) {
                        latestAudioReceivedSeconds = response.heartbeat.audioReceivedSeconds
                    }
                    if (response.hasState()) {
                        val raw = response.state.takeIf { it.hasRawText() }
                            ?.rawText?.content?.takeIf { it.isNotBlank() }
                        val formatted = response.state.takeIf { it.hasFormattedText() }
                            ?.formattedText?.content?.takeIf { it.isNotBlank() }
                        if (raw != latestRawText || formatted != latestFormattedText) {
                            latestRawText = raw
                            latestFormattedText = formatted
                            (formatted ?: raw)?.let(onPartial)
                        }
                    }
                    if (response.hasResult()) {
                        val output = response.result.output
                        if (output.plaintext.isNotBlank()) latestPlaintext = output.plaintext
                        if (response.result.hasAudioDuration()) {
                            val duration = response.result.audioDuration
                            latestAudioDurationSeconds = duration.seconds.toDouble() + duration.nanos.toDouble() / 1_000_000_000.0
                        }
                    }
                }

                override fun onError(t: Throwable) {
                    finishExceptionally(t)
                }

                override fun onCompleted() {
                    if (!terminal.compareAndSet(false, true)) return
                    val text = latestPlaintext.ifBlank {
                        latestFormattedText ?: latestRawText.orEmpty()
                    }
                    if (text.isBlank()) {
                        result.completeExceptionally(IllegalStateException("Wispr streaming transcription returned no text"))
                    } else {
                        result.complete(
                            Result(
                                text = text,
                                rawText = latestRawText,
                                formattedText = latestFormattedText,
                                audioDurationSeconds = latestAudioDurationSeconds,
                                audioReceivedSeconds = latestAudioReceivedSeconds,
                            ),
                        )
                    }
                    channel.shutdown()
                }
            }
            requestObserver = stub.transcribeStream(responseObserver)
            send(buildInitRequest())
        }

        fun sendAudio(chunk: ByteArray) {
            if (chunk.isEmpty()) return
            val request = Request.newBuilder()
                .setPayload(
                    com.jadenjsj.betterflow.proto.Payload.newBuilder()
                        .setAudioPackets(
                            com.jadenjsj.betterflow.proto.AudioPackets.newBuilder()
                                .addItems(ByteString.copyFrom(chunk)),
                        ),
                )
                .build()
            send(request)
        }

        fun commit() {
            synchronized(lock) {
                if (terminal.get()) return
                val observer = requestObserver ?: return
                observer.onNext(Request.newBuilder().setCommit(Commit.COMMIT_TRUE).build())
                observer.onCompleted()
            }
        }

        suspend fun awaitResult(): Result = result.await()

        fun cancel(reason: String = "cancelled by user") {
            if (!terminal.compareAndSet(false, true)) return
            requestCall?.cancel(reason, null)
            result.cancel()
            channel.shutdownNow()
            runCatching { channel.awaitTermination(250, TimeUnit.MILLISECONDS) }
        }

        private fun send(request: Request) {
            synchronized(lock) {
                if (terminal.get()) return
                requestObserver?.onNext(request)
            }
        }

        private fun finishExceptionally(t: Throwable) {
            if (!terminal.compareAndSet(false, true)) return
            result.completeExceptionally(t)
            channel.shutdownNow()
        }

        private fun buildInitRequest(): Request {
            val transcriptionId = UUID.randomUUID().toString()
            return Request.newBuilder()
                .setInit(
                    com.jadenjsj.betterflow.proto.Init.newBuilder()
                        .setMetadata(
                            com.jadenjsj.betterflow.proto.Metadata.newBuilder()
                                .setUserId(UUID.randomUUID().toString())
                                .setSessionId(transcriptionId)
                                .setRequestId(transcriptionId)
                                .setAudioEncoding(1)
                                .setEnvironment(1)
                                .setClient(
                                    com.jadenjsj.betterflow.proto.Client.newBuilder()
                                        .setName("Wispr Flow Android")
                                        .setPlatform(4)
                                        .setVersion(
                                            com.jadenjsj.betterflow.proto.Version.newBuilder()
                                                .setMajor(1)
                                                .setMinor(8)
                                                .setPatch(8),
                                        ),
                                )
                                .setDebug(false),
                        )
                        .setPreferences(
                            com.jadenjsj.betterflow.proto.Preferences.newBuilder()
                                .setStyleConfig(com.jadenjsj.betterflow.proto.StyleConfig.getDefaultInstance()),
                        )
                        .setState(com.jadenjsj.betterflow.proto.State.getDefaultInstance()),
                )
                .build()
        }

        companion object {
            private val AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
            private val BASETEN_AUTHORIZATION = Metadata.Key.of("baseten-authorization", Metadata.ASCII_STRING_MARSHALLER)
            private val BASETEN_MODEL_ID = Metadata.Key.of("baseten-model-id", Metadata.ASCII_STRING_MARSHALLER)
        }
    }

    companion object {
        const val DEFAULT_MODEL_ID = "v31pl413"
    }
}
