package com.jadenjsj.betterflow

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/** Durable Gboard-private staging, retained until the companion acknowledges the final state. */
internal class HookCaptureOutbox(private val root: File) {
    fun create(pcm: ByteArray): String {
        val id = UUID.randomUUID().toString()
        val dir = directory(id).apply { check(mkdirs()) { "Could not stage recording" } }
        FileOutputStream(File(dir, AUDIO)).use { it.write(pcm); it.fd.sync() }
        write(id, JSONObject().put("revision", 0).put("status", "captured")
            .put("attempts", 0).put("insertion", "not_attempted").put("owner_process", PROCESS_ID))
        return id
    }

    fun ids(): List<String> = root.listFiles().orEmpty().mapNotNull { dir ->
        if (!dir.isDirectory || !File(dir, AUDIO).isFile) return@mapNotNull null
        if (!File(dir, STATE).isFile) {
            // The keyboard may have died between fsync(audio) and committing its first metadata.
            // Preserve complete PCM samples and surface a manual retry rather than orphaning audio.
            RandomAccessFile(File(dir, AUDIO), "rw").use {
                it.setLength(it.length() - it.length() % 2)
                it.fd.sync()
            }
            write(dir.name, JSONObject().put("revision", 0).put("status", "failed")
                .put("attempts", 0).put("insertion", "not_attempted")
                .put("error", "Gboard stopped before transcription; retry the saved audio"))
        }
        val state = state(dir.name)
        if (state.optString("owner_process") != PROCESS_ID) update(dir.name) {
            it.put("owner_process", PROCESS_ID)
            when (it.optString("status")) {
                "captured", "transcribing" -> it.put("status", "failed")
                    .put("error", "Gboard restarted before transcription completed; retry the saved audio")
                "ready" -> if (it.optString("insertion") == "not_attempted") it.put("insertion", "failed_interrupted")
            }
        }
        dir.name
    }.sorted()

    fun state(id: String): JSONObject = JSONObject(File(directory(id), STATE).readText())
    fun audio(id: String): File = File(directory(id), AUDIO)
    fun update(id: String, block: (JSONObject) -> Unit) {
        val state = state(id)
        block(state)
        state.put("revision", state.getLong("revision") + 1)
        write(id, state)
    }

    fun remove(id: String) { directory(id).deleteRecursively() }

    private fun write(id: String, state: JSONObject) {
        val dir = directory(id)
        val temporary = File(dir, "$STATE.tmp")
        FileOutputStream(temporary).use { it.write(state.toString().toByteArray()); it.fd.sync() }
        check(temporary.renameTo(File(dir, STATE))) { "Could not stage history metadata" }
    }

    private fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid capture ID" }
        return File(root, id)
    }

    companion object {
        private val PROCESS_ID = UUID.randomUUID().toString()
        private const val AUDIO = "sample.pcm"
        private const val STATE = "state.json"
    }
}
