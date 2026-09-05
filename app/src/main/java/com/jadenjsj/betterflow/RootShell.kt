package com.jadenjsj.betterflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object RootShell {
    suspend fun hasRoot(): Boolean = withContext(Dispatchers.IO) {
        val result = runFixed("id -u")
        result.first == 0 && result.second.trim() == "0"
    }

    suspend fun pasteKeyEvent(): Boolean = withContext(Dispatchers.IO) {
        runFixed("input keyevent 279").first == 0
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
