package com.jadenjsj.betterflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InputInjector(private val context: Context) {
    data class Result(val success: Boolean, val backend: InputBackend, val detail: String)
    private data class LsposedAttempt(val acknowledged: Boolean, val committed: Boolean)

    suspend fun inject(text: String): Result {
        val backend = Prefs.backend(context)
        return when (backend) {
            InputBackend.LSPOSED -> {
                val attempt = tryLsposed(text)
                Result(
                    attempt.committed,
                    backend,
                    when {
                        attempt.committed -> "committed through active IME"
                        attempt.acknowledged -> "active IME bridge rejected the commit"
                        else -> "LSPosed IME bridge did not acknowledge"
                    },
                )
            }
            InputBackend.CLIPBOARD_PASTE -> paste(text)
            InputBackend.AUTO -> {
                val attempt = tryLsposed(text)
                if (attempt.committed) {
                    Result(true, InputBackend.LSPOSED, "committed through active IME")
                } else {
                    paste(text)
                }
            }
        }
    }

    private suspend fun tryLsposed(text: String): LsposedAttempt = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val deadline = SystemClock.elapsedRealtime() + LSPOSED_COMMIT_DEADLINE_MS
        val latch = CountDownLatch(1)
        var acknowledged = false
        var success = false
        // A null Handler lets the cross-process reply arrive directly on a Binder
        // thread instead of waiting behind app UI work. AUTO can then decide whether
        // a paste fallback is safe without racing a late InputConnection commit.
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                acknowledged = true
                success = resultCode == RESULT_OK
                latch.countDown()
            }
        }
        val intent = Intent(ACTION_COMMIT_TEXT)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_DEADLINE_ELAPSED_REALTIME, deadline)
            .putExtra(EXTRA_RESULT_RECEIVER, receiver)
        currentImePackage()?.let(intent::setPackage)
        context.sendBroadcast(intent)
        val replied = latch.await(LSPOSED_REPLY_WAIT_MS, TimeUnit.MILLISECONDS)
        Log.d(TAG, "LSPosed commit request=$requestId replied=$replied acknowledged=$acknowledged success=$success")
        LsposedAttempt(replied && acknowledged, replied && success)
    }

    private suspend fun paste(text: String): Result {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("betterFlow", text))
        val ok = RootShell.pasteKeyEvent()
        return Result(
            success = ok,
            backend = InputBackend.CLIPBOARD_PASTE,
            detail = if (ok) "clipboard set + root KEYCODE_PASTE" else "root paste failed; transcript remains in clipboard",
        )
    }

    private fun currentImePackage(): String? {
        val flattened = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return null
        return ComponentName.unflattenFromString(flattened)?.packageName
    }

    companion object {
        const val ACTION_COMMIT_TEXT = "com.jadenjsj.betterflow.action.COMMIT_TEXT"
        const val COMMIT_PERMISSION = "com.jadenjsj.betterflow.permission.COMMIT_TEXT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_DEADLINE_ELAPSED_REALTIME = "deadline_elapsed_realtime"
        const val EXTRA_RESULT_RECEIVER = "result_receiver"
        const val RESULT_OK = 1
        const val RESULT_FAILED = 0
        private const val LSPOSED_COMMIT_DEADLINE_MS = 1_000L
        private const val LSPOSED_REPLY_WAIT_MS = 1_250L
        private const val TAG = "betterFlow/InputInjector"
    }
}
