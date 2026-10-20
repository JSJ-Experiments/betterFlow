package com.jadenjsj.betterflow

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject
import okhttp3.OkHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Explicit broadcasts work from Gboard even when Android hides our package's provider. */
class HistoryBridge : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        val reply = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_REPLY, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<ResultReceiver>(EXTRA_REPLY)
        } ?: return
        val data = intent.getBundleExtra(EXTRA_DATA) ?: Bundle.EMPTY
        if (data.getString("token") != token(context)) {
            reply.send(RESULT_ERROR, Bundle().apply { putString("error", "Invalid history token") })
            return
        }
        val method = intent.getStringExtra(EXTRA_METHOD).orEmpty()
        val pending = goAsync()
        try {
            (if (method == "auth_refresh") authWorker else worker).execute {
                try {
                    val result = execute(context.applicationContext, method, data)
                    reply.send(RESULT_OK, result)
                    if (method in setOf("create", "finish", "attempt", "result", "failure", "insertion", "sync_state") ||
                        (method == "sync_state_part" && result.getBoolean("complete"))) context.sendBroadcast(
                        Intent(ACTION_CHANGED).setPackage(context.packageName))
                } catch (t: Throwable) {
                    reply.send(RESULT_ERROR, Bundle().apply {
                        putString("error", t.message ?: t.javaClass.simpleName)
                    })
                } finally {
                    pending.finish()
                }
            }
        } catch (t: Throwable) {
            reply.send(RESULT_ERROR, Bundle().apply { putString("error", t.message) })
            pending.finish()
        }
    }

    private fun execute(context: Context, method: String, data: Bundle): Bundle {
        val store = HistoryStore(context)
        return when (method) {
            "auth_refresh" -> {
                val auth = AuthStore(context)
                val active = auth.load() ?: error("Wispr is signed out; open betterFlow")
                val expectedEmail = data.getString("expected_email")
                val expectedUser = data.getString("expected_user_id")
                val expectedProvider = data.getString("expected_provider")
                val rejectedSubject = data.getString("rejected_access_token")?.let(AuthStore::jwtSubject)
                val activeSubject = AuthStore.jwtSubject(active.accessToken)
                check((expectedEmail == null || active.email == expectedEmail) &&
                    (expectedUser == null || active.userId == expectedUser) &&
                    (expectedProvider == null || active.provider?.wireName == expectedProvider) &&
                    (rejectedSubject == null || activeSubject == null || rejectedSubject == activeSubject)) {
                    "Active Wispr account changed; start a new recording"
                }
                val session = runBlocking {
                    withTimeout(8_000L) {
                        WisprClient(auth, client = authHttpClient).refreshStreamingSession(data.getString("rejected_access_token"), expectedSession = active)
                    }
                }
                Bundle().apply {
                    putString("email", session.email)
                    putString("access_token", session.accessToken)
                    putString("refresh_token", session.refreshToken)
                    putString("provider", session.provider?.wireName)
                    putString("user_id", session.userId)
                    putLong("expires_at", session.expiresAt ?: 0L)
                }
            }
            "create" -> {
                val id = data.getString("id")?.let { store.create("gboard", it, data.getLong("audio_bytes")) }
                    ?: store.create(data.getString("origin") ?: "gboard")
                Bundle().apply {
                    putString("id", id)
                    putLong("offset", store.captureBytes(id))
                    putBoolean("finished", store.get(id).status != "capturing")
                }
            }
            "sync_state_part" -> {
                val id = requireNotNull(data.getString("id"))
                val complete = store.receiveHookState(id, data.getLong("revision"), data.getLong("offset"),
                    data.getLong("total"), requireNotNull(data.getByteArray("bytes")))
                if (complete) notifyState(context, store, id)
                Bundle().apply { putBoolean("complete", complete) }
            }
            "sync_state" -> {
                val id = requireNotNull(data.getString("id"))
                val state = JSONObject(requireNotNull(data.getString("state")))
                store.syncHookState(id, state)
                notifyState(context, store, id)
                Bundle.EMPTY
            }
            "append" -> {
                store.append(requireNotNull(data.getString("id")), data.getLong("offset"), requireNotNull(data.getByteArray("pcm")))
                Bundle.EMPTY
            }
            "finish" -> { store.finishCapture(requireNotNull(data.getString("id"))); Bundle.EMPTY }
            "attempt" -> { store.beginAttempt(requireNotNull(data.getString("id"))); Bundle.EMPTY }
            "result" -> {
                val id = requireNotNull(data.getString("id"))
                store.result(id, data.getString("text").orEmpty(), data.getString("engine").orEmpty(), data.getString("raw").orEmpty())
                HistoryNotifier.clear(context, id)
                Bundle.EMPTY
            }
            "failure" -> {
                val id = requireNotNull(data.getString("id"))
                store.failure(id, data.getString("error").orEmpty(), data.getString("raw").orEmpty())
                HistoryNotifier.show(context, id, "Transcription failed. Tap to retry the saved audio.")
                Bundle.EMPTY
            }
            "insertion" -> {
                val id = requireNotNull(data.getString("id"))
                val status = data.getString("status").orEmpty()
                store.insertion(id, status)
                if (status.contains("failed")) HistoryNotifier.show(context, id, "Text was not inserted. Tap to copy it from History.")
                Bundle.EMPTY
            }
            else -> throw IllegalArgumentException("Unknown history operation")
        }
    }

    private fun notifyState(context: Context, store: HistoryStore, id: String) {
        val current = store.get(id)
        when {
            current.status == "failed" -> HistoryNotifier.show(context, id,
                "${current.error.ifBlank { "Transcription failed" }}. Audio saved in History.")
            current.insertion.contains("failed") -> HistoryNotifier.show(context, id, "Text was not inserted. Tap to copy it from History.")
            current.status == "ready" -> HistoryNotifier.clear(context, id)
        }
    }

    companion object {
        const val ACTION_CHANGED = "com.jadenjsj.betterflow.action.HISTORY_CHANGED"
        const val ACTION_COMMAND = "com.jadenjsj.betterflow.action.HISTORY_COMMAND"
        const val EXTRA_DATA = "data"
        const val EXTRA_METHOD = "method"
        const val EXTRA_REPLY = "reply"
        const val RESULT_OK = 1
        const val RESULT_ERROR = 0
        private const val PREF = "history_bridge"
        private const val KEY = "token"
        private val authHttpClient = OkHttpClient.Builder()
            .callTimeout(7, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .writeTimeout(7, TimeUnit.SECONDS)
            .build()
        private val authWorker = ThreadPoolExecutor(
            0, 1, 20, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
        ).apply { allowCoreThreadTimeOut(true) }
        private val worker = ThreadPoolExecutor(
            0, 1, 20, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
        ).apply { allowCoreThreadTimeOut(true) }

        @Synchronized fun token(context: Context): String {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.getString(KEY, null)?.let { return it }
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val value = bytes.joinToString("") { "%02x".format(it) }
            check(prefs.edit().putString(KEY, value).commit()) { "Could not save history token" }
            return value
        }
    }
}

/** Durable, event-driven handoff: transcription never waits for companion IPC. */
class HookHistoryBridge(private val context: Context, private val token: String) {
    private val outbox = HookCaptureOutbox(File(context.filesDir, "betterflow_history_outbox"))

    fun save(pcm: ByteArray): String {
        val id = synchronized(outboxLock) { outbox.create(pcm) }
        retryPending()
        return id
    }

    fun attempt(id: String) = update(id) {
        it.put("status", "transcribing").put("attempts", it.optInt("attempts") + 1)
    }
    fun result(id: String, text: String, engine: String, raw: String = "") = update(id) {
        it.put("status", "ready").put("transcript", text).put("engine", engine)
            .put("raw_result", raw).put("error", "")
    }
    fun failure(id: String, error: String, raw: String = "") = update(id) {
        it.put("status", "failed").put("error", error).put("raw_result", raw)
    }
    fun insertion(id: String, status: String) = update(id) { it.put("insertion", status) }

    fun refreshSession(rejectedAccessToken: String?, expected: WisprSession): WisprSession {
        val result = call("auth_refresh", Bundle().apply {
            putString("rejected_access_token", rejectedAccessToken)
            putString("expected_email", expected.email)
            putString("expected_user_id", expected.userId)
            putString("expected_provider", expected.provider?.wireName)
        })
        return WisprSession(
            email = result.getString("email") ?: "wispr-user",
            accessToken = requireNotNull(result.getString("access_token")),
            refreshToken = result.getString("refresh_token"),
            expiresAt = result.getLong("expires_at", 0L).takeIf { it > 0 },
            provider = WisprAuthProvider.fromWireName(result.getString("provider")),
            userId = result.getString("user_id"),
        )
    }

    private fun update(id: String, block: (JSONObject) -> Unit) {
        synchronized(outboxLock) { outbox.update(id, block) }
        retryPending()
    }

    /** Called by keyboard lifecycle/capture events only, never by a timer. */
    fun retryPending() {
        synchronized(requestLock) {
            requestedBridge = this
            if (syncScheduled) return
            syncScheduled = true
            syncWorker.execute {
                while (true) {
                    val bridge = synchronized(requestLock) {
                        val next = requestedBridge
                        requestedBridge = null
                        if (next == null) syncScheduled = false
                        next
                    } ?: break
                    val ids = synchronized(outboxLock) { bridge.outbox.ids() }
                    for (id in ids) {
                        runCatching { bridge.sync(id) }.onFailure {
                            // Avoid logging audio, transcript, authentication, or provider response.
                            Log.w("betterFlow/History", "Gboard history sync pending (${it.javaClass.simpleName})")
                        }
                    }
                }
            }
        }
    }

    private fun sync(id: String) {
        // Snapshot under the short local lock; IPC must not block transcription/state writes.
        val state = synchronized(outboxLock) {
            if (id !in outbox.ids()) return
            outbox.state(id)
        }
        val audio = outbox.audio(id)
        val created = call("create", Bundle().apply { putString("id", id); putLong("audio_bytes", audio.length()) })
        var offset = created.getLong("offset")
        require(offset in 0..audio.length()) { "Invalid history audio offset" }
        if (!created.getBoolean("finished")) {
            RandomAccessFile(audio, "r").use { input ->
                input.seek(offset)
                while (offset < input.length()) {
                    val chunk = ByteArray(minOf(HistoryStore.MAX_CHUNK_BYTES.toLong(), input.length() - offset).toInt())
                    input.readFully(chunk)
                    call("append", Bundle().apply {
                        putString("id", id); putLong("offset", offset); putByteArray("pcm", chunk)
                    })
                    offset += chunk.size
                }
            }
            call("finish", Bundle().apply { putString("id", id) })
        } else {
            check(offset == audio.length()) { "Incomplete history audio" }
        }
        val metadata = state.toString().toByteArray(Charsets.UTF_8)
        var metadataOffset = 0
        var metadataAcknowledged = false
        while (metadataOffset < metadata.size) {
            val end = minOf(metadata.size, metadataOffset + HistoryStore.MAX_CHUNK_BYTES)
            val ack = call("sync_state_part", Bundle().apply {
                putString("id", id)
                putLong("revision", state.getLong("revision"))
                putLong("offset", metadataOffset.toLong())
                putLong("total", metadata.size.toLong())
                putByteArray("bytes", metadata.copyOfRange(metadataOffset, end))
            })
            if (ack.getBoolean("complete")) {
                metadataAcknowledged = true
                break
            }
            metadataOffset = end
        }
        check(metadataAcknowledged) { "History metadata was not acknowledged" }
        val complete = state.optString("status") == "failed" ||
            (state.optString("status") == "ready" && state.optString("insertion") != "not_attempted")
        if (complete) synchronized(outboxLock) {
            if (outbox.state(id).getLong("revision") == state.getLong("revision")) outbox.remove(id)
        }
    }

    private fun call(method: String, data: Bundle): Bundle {
        data.putString("token", token)
        val latch = CountDownLatch(1)
        val status = AtomicInteger(HistoryBridge.RESULT_ERROR)
        val response = AtomicReference<Bundle?>(null)
        val reply = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                status.set(resultCode)
                response.set(resultData)
                latch.countDown()
            }
        }
        context.sendBroadcast(Intent(HistoryBridge.ACTION_COMMAND)
            .setComponent(ComponentName(BuildConfig.APPLICATION_ID, HistoryBridge::class.java.name))
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(HistoryBridge.EXTRA_METHOD, method)
            .putExtra(HistoryBridge.EXTRA_DATA, data)
            .putExtra(HistoryBridge.EXTRA_REPLY, reply))
        check(latch.await(10, TimeUnit.SECONDS)) { "History handoff timed out" }
        val result = response.get() ?: Bundle.EMPTY
        check(status.get() == HistoryBridge.RESULT_OK) { result.getString("error") ?: "History handoff failed" }
        return result
    }

    companion object {
        private val outboxLock = Any()
        private val requestLock = Any()
        private var requestedBridge: HookHistoryBridge? = null
        private var syncScheduled = false
        private val syncWorker = ThreadPoolExecutor(
            0, 1, 20, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
