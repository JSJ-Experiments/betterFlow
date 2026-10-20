package com.jadenjsj.betterflow

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Private, lossless, append-only capture archive. No polling or background worker is needed. */
class HistoryStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "history").apply { mkdirs() }

    data class Entry(
        val id: String,
        val createdAt: Long,
        val updatedAt: Long,
        val origin: String,
        val status: String,
        val transcript: String,
        val engine: String,
        val error: String,
        val attempts: Int,
        val audioBytes: Long,
        val insertion: String,
    )

    fun create(origin: String, id: String = UUID.randomUUID().toString(), expectedBytes: Long? = null): String {
        return synchronized(lock) {
            // A caller-generated ID makes a lost create acknowledgement safe to retry.
            if (File(directory(id), META).isFile) return@synchronized id
            val dir = directory(id).apply { mkdirs() }
            RandomAccessFile(File(dir, AUDIO), "rw").use { file ->
                file.write(wavHeader(0))
                file.fd.sync()
            }
            val now = System.currentTimeMillis()
            write(id, JSONObject()
                .put("schema_version", 1)
                .put("id", id)
                .put("created_at_ms", now)
                .put("updated_at_ms", now)
                .put("origin", origin)
                .put("status", "capturing")
                .put("audio_format", "wav_pcm_s16le_mono_16000hz")
                .put("sample_rate_hz", AudioRecorderController.SAMPLE_RATE)
                .put("channels", 1)
                .put("audio_file", AUDIO)
                .put("audio_path", "$id/$AUDIO")
                .put("audio_bytes", 0)
                .put("expected_audio_bytes", expectedBytes)
                .put("attempts", 0)
                .put("transcript", "")
                .put("engine", "")
                .put("error", "")
                .put("insertion", "not_attempted"))
            id
        }
    }

    fun append(id: String, offset: Long, pcm: ByteArray) {
        synchronized(lock) {
            require(pcm.size <= MAX_CHUNK_BYTES && pcm.size % 2 == 0) { "invalid PCM chunk" }
            val metadata = readJson(id)
            require(metadata.optString("status") == "capturing") { "capture already finalized" }
            val file = audioFile(id)
            RandomAccessFile(file, "rw").use { output ->
                val current = output.length() - WAV_HEADER_BYTES
                if (offset < current) {
                    require(offset >= 0 && offset + pcm.size <= current) { "unexpected audio offset" }
                    output.seek(WAV_HEADER_BYTES + offset)
                    val existing = ByteArray(pcm.size).also { output.readFully(it) }
                    require(existing.contentEquals(pcm)) { "conflicting audio chunk" }
                    return
                }
                require(offset == current) { "unexpected audio offset" }
                require(current + pcm.size <= MAX_AUDIO_BYTES) { "capture too long" }
                output.seek(output.length())
                output.write(pcm)
                output.fd.sync()
            }
            // Audio length is recovered from the file if the process dies before finalize.
        }
    }

    fun captureBytes(id: String): Long = synchronized(lock) {
        (audioFile(id).length() - WAV_HEADER_BYTES).coerceAtLeast(0)
    }

    fun finishCapture(id: String) {
        synchronized(lock) {
            // Lost acknowledgements must not reset ready/failed metadata on retry.
            if (readJson(id).optString("status") != "capturing") return
            val file = audioFile(id)
            RandomAccessFile(file, "rw").use { output ->
                val bytes = (output.length() - WAV_HEADER_BYTES).coerceAtLeast(0)
                require(bytes <= Int.MAX_VALUE - WAV_HEADER_BYTES) { "capture too long" }
                val metadata = readJson(id)
                if (metadata.has("expected_audio_bytes")) {
                    require(bytes == metadata.getLong("expected_audio_bytes")) { "History audio is not completely synced" }
                }
                output.seek(0)
                output.write(wavHeader(bytes.toInt()))
                output.fd.sync()
                update(id) {
                    it.put("audio_bytes", bytes)
                        .put("duration_ms", bytes * 1000 / (AudioRecorderController.SAMPLE_RATE * 2))
                        .put("status", if (bytes > 0) "captured" else "failed")
                        .put("error", if (bytes > 0) "" else "No audio captured")
                }
            }
        }
    }

    fun save(origin: String, pcm: ByteArray): String {
        return synchronized(lock) {
            val id = create(origin)
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(pcm.size, offset + MAX_CHUNK_BYTES)
                append(id, offset.toLong(), pcm.copyOfRange(offset, end))
                offset = end
            }
            finishCapture(id)
            id
        }
    }

    fun beginAttempt(id: String) = synchronized(lock) {
        update(id) {
            it.put("status", "transcribing")
                .put("attempts", it.optInt("attempts") + 1)
                .put("error", "")
        }
    }

    fun result(id: String, text: String, engine: String, raw: String = "") = synchronized(lock) {
        update(id) {
            it.put("status", "ready")
                .put("transcript", text)
                .put("engine", engine)
                .put("error", "")
                .put("raw_result", raw)
        }
    }

    fun failure(id: String, message: String, raw: String = "") = synchronized(lock) {
        update(id) {
            it.put("status", "failed").put("error", message)
            if (raw.isNotBlank()) it.put("raw_result", raw)
        }
    }

    fun insertion(id: String, status: String) = synchronized(lock) {
        update(id) {
            it.put("insertion", status)
        }
    }

    /** Snapshot from Gboard's durable outbox; revision suppresses late/replayed IPC. */
    fun syncHookState(id: String, state: JSONObject) = synchronized(lock) {
        update(id) { metadata ->
            val revision = state.getLong("revision")
            if (revision <= metadata.optLong("hook_revision", -1L)) return@update
            metadata.put("hook_revision", revision)
            // A delayed in-flight snapshot must not undo a manual History retry result.
            if (metadata.optString("status") in listOf("ready", "failed") &&
                state.optString("status") in listOf("captured", "transcribing")) return@update
            metadata.put("attempts", maxOf(metadata.optInt("attempts"), state.optInt("attempts")))
            listOf("status", "transcript", "engine", "error", "raw_result", "insertion").forEach { key ->
                if (state.has(key)) metadata.put(key, state.get(key))
            }
        }
    }

    /** Metadata uses the same bounded/acknowledged handoff as audio, never one huge Binder parcel. */
    fun receiveHookState(id: String, revision: Long, offset: Long, total: Long, bytes: ByteArray): Boolean = synchronized(lock) {
        require(revision >= 0 && total in 1..MAX_HOOK_METADATA_BYTES) { "Invalid history metadata" }
        require(bytes.size <= MAX_CHUNK_BYTES && offset >= 0 && offset + bytes.size <= total) { "Invalid metadata chunk" }
        if (readJson(id).optLong("hook_revision", -1L) >= revision) return@synchronized true
        val pending = File(directory(id), "hook-state-$revision.pending")
        if (!HistoryChunkFile.append(pending, offset, total, bytes)) return@synchronized false
        val state = JSONObject(pending.readText(Charsets.UTF_8))
        require(state.getLong("revision") == revision) { "Unexpected metadata revision" }
        syncHookState(id, state)
        directory(id).listFiles().orEmpty().filter {
            it.name.startsWith("hook-state-") && it.name.endsWith(".pending") &&
                (it.name.removePrefix("hook-state-").removeSuffix(".pending").toLongOrNull() ?: Long.MAX_VALUE) <= revision
        }.forEach { it.delete() }
        true
    }

    fun list(): List<Entry> = synchronized(lock) {
        root.listFiles().orEmpty().mapNotNull { dir ->
            runCatching {
                val json = readJson(dir.name)
                if (json.optString("status") == "capturing" &&
                    System.currentTimeMillis() - audioFile(dir.name).lastModified() > 60_000 &&
                    (!json.has("expected_audio_bytes") || captureBytes(dir.name) == json.getLong("expected_audio_bytes"))) {
                    finishCapture(dir.name)
                }
                entry(readJson(dir.name))
            }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    fun get(id: String): Entry = synchronized(lock) { entry(readJson(id)) }

    fun pcm(id: String): ByteArray {
        return synchronized(lock) {
            val file = audioFile(id)
            require(file.length() >= WAV_HEADER_BYTES) { "Audio file is missing" }
            require(file.length() <= MAX_AUDIO_BYTES + WAV_HEADER_BYTES) { "Audio file is too large" }
            RandomAccessFile(file, "r").use { input ->
                input.seek(WAV_HEADER_BYTES.toLong())
                ByteArray((input.length() - WAV_HEADER_BYTES).toInt()).also { input.readFully(it) }
            }
        }
    }

    fun wavFile(id: String): File = audioFile(id)

    /** Portable training bundle: one WAV + JSON per capture and a JSONL index. */
    fun export(output: java.io.OutputStream) {
        // Audio is immutable once finalized. Snapshot metadata under the shared monitor, then
        // release it before ZIP compression so a large export cannot stall bridge acknowledgements.
        val snapshot = synchronized(lock) {
            list().filter { it.status != "capturing" }.map { it.id to readJson(it.id) }
        }
        ZipOutputStream(output).use { zip ->
            snapshot.forEach { (id, json) ->
                zip.putNextEntry(ZipEntry("$id/record.json"))
                zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                val wav = audioFile(id)
                if (wav.isFile) {
                    zip.putNextEntry(ZipEntry("$id/$AUDIO"))
                    wav.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("dataset.jsonl"))
            snapshot.forEach { (_, json) ->
                zip.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
            zip.closeEntry()
        }
    }

    private fun directory(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid capture ID" }
        return File(root, id)
    }

    private fun audioFile(id: String) = File(directory(id), AUDIO)
    private fun readJson(id: String): JSONObject = AtomicFile(File(directory(id), META))
        .openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    private fun entry(json: JSONObject) = Entry(
        json.getString("id"), json.optLong("created_at_ms"), json.optLong("updated_at_ms"), json.optString("origin"),
        json.optString("status"), json.optString("transcript"), json.optString("engine"),
        json.optString("error"), json.optInt("attempts"), json.optLong("audio_bytes"),
        json.optString("insertion"),
    )

    private fun update(id: String, block: (JSONObject) -> Unit) {
        val json = readJson(id)
        block(json)
        json.put("updated_at_ms", System.currentTimeMillis())
        write(id, json)
    }

    private fun write(id: String, json: JSONObject) {
        val atomic = AtomicFile(File(directory(id), META))
        val output = atomic.startWrite()
        try {
            output.write(json.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (t: Throwable) {
            atomic.failWrite(output)
            throw t
        }
    }

    companion object {
        private val lock = Any()
        private const val META = "record.json"
        private const val AUDIO = "sample.wav"
        private const val WAV_HEADER_BYTES = 44
        const val MAX_CHUNK_BYTES = 128 * 1024
        private const val MAX_HOOK_METADATA_BYTES = 16L * 1024 * 1024
        private const val MAX_AUDIO_BYTES = 32000L * 60 * 60 // One hour, no silent pruning.

        private fun wavHeader(bytes: Int): ByteArray = ByteBuffer.allocate(WAV_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray())
            .putInt(36 + bytes)
            .put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1)
            .putInt(AudioRecorderController.SAMPLE_RATE)
            .putInt(AudioRecorderController.SAMPLE_RATE * 2)
            .putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes)
            .array()
    }
}
