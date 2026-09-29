package com.jadenjsj.betterflow

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
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
        val reply = intent.getParcelableExtra(EXTRA_REPLY, ResultReceiver::class.java) ?: return
        val data = intent.getBundleExtra(EXTRA_DATA) ?: Bundle.EMPTY
        if (data.getString("token") != token(context)) {
            reply.send(RESULT_ERROR, Bundle().apply { putString("error", "Invalid history token") })
            return
        }
        val method = intent.getStringExtra(EXTRA_METHOD).orEmpty()
        val pending = goAsync()
        try {
            worker.execute {
                try {
                    reply.send(RESULT_OK, execute(context.applicationContext, method, data))
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
            "create" -> Bundle().apply { putString("id", store.create(data.getString("origin") ?: "gboard")) }
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

    companion object {
        const val ACTION_COMMAND = "com.jadenjsj.betterflow.action.HISTORY_COMMAND"
        const val EXTRA_DATA = "data"
        const val EXTRA_METHOD = "method"
        const val EXTRA_REPLY = "reply"
        const val RESULT_OK = 1
        const val RESULT_ERROR = 0
        private const val PREF = "history_bridge"
        private const val KEY = "token"
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

/** Sends audio only after recording stops; one acknowledged 128 KiB chunk at a time. */
class HookHistoryBridge(private val context: Context, private val token: String) {
    fun save(pcm: ByteArray): String {
        val id = call("create", Bundle().apply { putString("origin", "gboard") }).getString("id")!!
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(pcm.size, offset + HistoryStore.MAX_CHUNK_BYTES)
            call("append", Bundle().apply {
                putString("id", id)
                putLong("offset", offset.toLong())
                putByteArray("pcm", pcm.copyOfRange(offset, end))
            })
            offset = end
        }
        call("finish", Bundle().apply { putString("id", id) })
        return id
    }

    fun attempt(id: String) = call("attempt", Bundle().apply { putString("id", id) })
    fun result(id: String, text: String, engine: String, raw: String = "") = call("result", Bundle().apply {
        putString("id", id); putString("text", text); putString("engine", engine); putString("raw", raw)
    })
    fun failure(id: String, error: String, raw: String = "") = call("failure", Bundle().apply {
        putString("id", id); putString("error", error); putString("raw", raw)
    })
    fun insertion(id: String, status: String) = call("insertion", Bundle().apply { putString("id", id); putString("status", status) })

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
        check(latch.await(10, TimeUnit.SECONDS)) { "History write timed out" }
        val result = response.get() ?: Bundle.EMPTY
        check(status.get() == HistoryBridge.RESULT_OK) { result.getString("error") ?: "History write failed" }
        return result
    }
}
