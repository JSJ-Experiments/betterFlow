package com.jadenjsj.betterflow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InputInjector(private val context: Context) {
    data class Result(val success: Boolean, val backend: InputBackend, val detail: String)

    suspend fun inject(text: String): Result {
        val backend = Prefs.backend(context)
        return when (backend) {
            InputBackend.LSPOSED -> {
                val ok = tryLsposed(text)
                Result(ok, backend, if (ok) "committed through active IME" else "LSPosed IME bridge did not acknowledge")
            }
            InputBackend.CLIPBOARD_PASTE -> paste(text)
            InputBackend.AUTO -> {
                if (tryLsposed(text)) {
                    Result(true, InputBackend.LSPOSED, "committed through active IME")
                } else {
                    paste(text)
                }
            }
        }
    }

    private suspend fun tryLsposed(text: String): Boolean = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        var success = false
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                success = resultCode == RESULT_OK
                latch.countDown()
            }
        }
        val intent = Intent(ACTION_COMMIT_TEXT)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(EXTRA_REQUEST_ID, requestId)
            .putExtra(EXTRA_RESULT_RECEIVER, receiver)
        currentImePackage()?.let(intent::setPackage)
        context.sendBroadcast(intent)
        latch.await(450, TimeUnit.MILLISECONDS)
        success
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
        const val EXTRA_RESULT_RECEIVER = "result_receiver"
        const val RESULT_OK = 1
        const val RESULT_FAILED = 0
    }
}
