package com.jadenjsj.betterflow

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WisprStreamingClient(
    private val wispr: WisprClient,
    private val apiKeyProvider: () -> String,
) {
    constructor(context: Context) : this(
        wispr = WisprClient(context.applicationContext),
        apiKeyProvider = {
            Prefs.streamingApiKey(context.applicationContext) ?: BuildConfig.WISPR_BASETEN_API_KEY.trim()
        },
    )

    private val endpoint = sharedEndpoint

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
        val apiKey = apiKeyProvider().trim()
        Log.i(TAG, "opening Wispr stream: model=$modelId keyPresent=${apiKey.isNotBlank()}")
        if (apiKey.isBlank()) {
            throw IllegalStateException("Wispr streaming credential is unavailable in this build")
        }
        Log.i(TAG, "requesting fresh Wispr access token")
        val session = wispr.freshStreamingSession()
        val accessToken = session.accessToken
        Log.i(TAG, "fresh Wispr access token acquired length=${accessToken.length}")
        val host = withContext(Dispatchers.IO) { endpoint.resolve() }
        return Session(
            host = host,
            accessToken = accessToken,
            userId = session.userId ?: AuthStore.jwtUserId(accessToken),
            apiKey = apiKey,
            modelId = modelId,
            onPartial = onPartial,
        )
    }

    class Session internal constructor(
        host: String,
        accessToken: String,
        userId: String?,
        apiKey: String,
        private val modelId: String,
        private val onPartial: (String) -> Unit,
    ) {
        private val channel: ManagedChannel = OkHttpChannelBuilder
            .forAddress(host, 443)
            .useTransportSecurity()
            .build()
        private val result = CompletableDeferred<Result>()
        private val terminal = AtomicBoolean(false)
        private val lock = Any()

        @Volatile private var requestCall: ClientCallStreamObserver<Request>? = null
        @Volatile private var requestObserver: StreamObserver<Request>? = null
        private val responses = WisprStreamingResponses(onPartial)

        init {
            Log.i(TAG, "creating Wispr gRPC channel host=$host model=$modelId")
            val headers = Metadata().apply {
                put(AUTHORIZATION, "Bearer $accessToken")
                put(BASETEN_AUTHORIZATION, "Api-Key $apiKey")
                put(BASETEN_MODEL_ID, "model-$modelId")
                put(CLIENT_HEADER, WisprProtocol.CLIENT_HEADER)
            }
            val stub = TranscriptionServiceGrpc.newStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
            val responseObserver = object : ClientResponseObserver<Request, Response> {
                override fun beforeStart(requestStream: ClientCallStreamObserver<Request>) {
                    requestCall = requestStream
                }

                override fun onNext(response: Response) {
                    if (terminal.get()) return
                    try {
                        responses.accept(response)?.let { finishSuccessfully(it) }
                    } catch (t: IllegalStateException) {
                        finishExceptionally(t)
                    }
                }

                override fun onError(t: Throwable) {
                    if (terminal.get()) return
                    Log.e(TAG, "Wispr gRPC stream error: ${t.message}", t)
                    finishExceptionally(t)
                }

                override fun onCompleted() {
                    if (terminal.get()) return
                    // A closed stream with only partial state is not a committed result.
                    finishExceptionally(IllegalStateException("Wispr stream ended without a final result"))
                }
            }
            Log.i(TAG, "starting TranscriptionService/TranscribeStream")
            requestObserver = stub.transcribeStream(responseObserver)
            Log.i(TAG, "gRPC stream observer created; sending init")
            send(buildInitRequest(accessToken, userId))
            Log.i(TAG, "init sent")
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

        private fun finishSuccessfully(value: Result) {
            if (!terminal.compareAndSet(false, true)) return
            result.complete(value)
            // A final Result can arrive before the server half-closes. Do not leave
            // an active RPC/channel behind after the caller considers capture done.
            requestCall?.cancel("final result received", null)
            channel.shutdownNow()
        }

        private fun finishExceptionally(t: Throwable) {
            if (!terminal.compareAndSet(false, true)) return
            result.completeExceptionally(t)
            channel.shutdownNow()
        }

        companion object {
            private val CLIENT_HEADER = Metadata.Key.of("x-wispr-client", Metadata.ASCII_STRING_MARSHALLER)
            private val AUTHORIZATION = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
            private val BASETEN_AUTHORIZATION = Metadata.Key.of("baseten-authorization", Metadata.ASCII_STRING_MARSHALLER)
            private val BASETEN_MODEL_ID = Metadata.Key.of("baseten-model-id", Metadata.ASCII_STRING_MARSHALLER)
        }
    }

    companion object {
        private const val TAG = "betterFlow/WisprStreaming"
        const val DEFAULT_MODEL_ID = WisprProtocol.DEFAULT_MODEL_ID
        private val sharedEndpoint = WisprStreamingEndpoint()

        internal fun buildInitRequest(accessToken: String, userId: String? = null): Request {
            val transcriptionId = UUID.randomUUID().toString()
            return Request.newBuilder()
                .setInit(
                    com.jadenjsj.betterflow.proto.Init.newBuilder()
                        .setMetadata(
                            com.jadenjsj.betterflow.proto.Metadata.newBuilder()
                                .setUserId(validUserId(userId ?: AuthStore.jwtUserId(accessToken) ?: AuthStore.jwtSubject(accessToken)))
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
                                                .setMajor(2)
                                                .setMinor(8)
                                                .setPatch(0),
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

        internal fun validUserId(subject: String?): String = runCatching {
            UUID.fromString(subject).toString()
        }.getOrDefault("00000000-0000-0000-0000-000000000000")
    }
}
