package com.jadenjsj.betterflow

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.security.SecureRandom

/** Gboard's hook runs under the keyboard UID, so it submits captures to our private store here. */
class HistoryBridge : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val context = requireNotNull(context)
        val data = requireNotNull(extras)
        if (data.getString("token") != token(context)) throw SecurityException("Invalid history token")
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
        private const val PREF = "history_bridge"
        private const val KEY = "token"

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

/** Only called after recording stops; never sends audio over Binder while the mic is active. */
class HookHistoryBridge(private val context: Context, private val token: String) {
    private val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.history")

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
        return requireNotNull(context.contentResolver.call(uri, method, null, data)) { "History bridge unavailable" }
    }
}
