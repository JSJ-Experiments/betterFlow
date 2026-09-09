package com.jadenjsj.betterflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

object RootShell {
    private val bubbleBootStateWorker = BubbleBootStateWorker { enabled ->
        runFixed(bubbleBootCommand(enabled)).first == 0
    }

    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        val result = runFixed("id -u")
        result.first == 0 && result.second.trim() == "0"
    }

    suspend fun pasteKeyEvent(): Boolean = withContext(Dispatchers.IO) {
        runFixed("input keyevent 279").first == 0
    }

    suspend fun setBubbleBootEnabled(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        bubbleBootStateWorker.set(enabled)
    }

    fun setBubbleBootEnabledAsync(enabled: Boolean) {
        bubbleBootStateWorker.setAsync(enabled)
    }

    fun retireLegacyWatchdog() {
        Thread({
            runFixed(
                "setprop ctl.stop betterflow_watchdog 2>/dev/null; " +
                    "pid=\$(cat /data/adb/betterflow-data/watchdog.pid 2>/dev/null); " +
                    "case \"\$pid\" in ''|*[!0-9]*) ;; *) " +
                    "if tr '\\000' ' ' < /proc/\$pid/cmdline 2>/dev/null | " +
                    "grep -q '/betterflow/scripts/watchdog.sh'; then kill \$pid 2>/dev/null; fi;; esac; " +
                    "rm -f /data/adb/betterflow-data/watchdog.pid",
            )
        }, "betterflow-retire-watchdog").start()
    }

    private fun bubbleBootCommand(enabled: Boolean): String = if (enabled) {
        "mkdir -p /data/adb/betterflow-data && echo 1 > /data/adb/betterflow-data/bubble_enabled"
    } else {
        "rm -f /data/adb/betterflow-data/bubble_enabled"
    }

    private fun runFixed(command: String): Pair<Int, String> {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor() to output
        } catch (t: Throwable) {
            -1 to (t.message ?: "root command failed")
        }
    }
}

internal class BubbleBootStateWorker(
    private val updateState: (Boolean) -> Boolean,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "betterflow-bubble-boot-state").apply { isDaemon = true }
    }

    fun set(enabled: Boolean): Boolean = try {
        executor.submit<Boolean> { updateState(enabled) }.get()
    } catch (_: ExecutionException) {
        false
    } catch (_: RejectedExecutionException) {
        false
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    fun setAsync(enabled: Boolean) {
        try {
            executor.execute { updateState(enabled) }
        } catch (_: RejectedExecutionException) {
            // The process is already shutting down; there is no state to preserve.
        }
    }
}
