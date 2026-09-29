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

    @Synchronized fun create(origin: String): String {
        val id = UUID.randomUUID().toString()
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
            .put("attempts", 0)
            .put("transcript", "")
            .put("engine", "")
            .put("error", "")
            .put("insertion", "not_attempted"))
        return id
    }

    @Synchronized fun append(id: String, offset: Long, pcm: ByteArray) {
        require(pcm.size <= MAX_CHUNK_BYTES && pcm.size % 2 == 0) { "invalid PCM chunk" }
        val metadata = readJson(id)
        require(metadata.optString("status") == "capturing") { "capture already finalized" }
        val file = audioFile(id)
        RandomAccessFile(file, "rw").use { output ->
            val current = output.length() - WAV_HEADER_BYTES
            require(offset == current) { "unexpected audio offset" }
            require(current + pcm.size <= MAX_AUDIO_BYTES) { "capture too long" }
            output.seek(output.length())
            output.write(pcm)
        }
        // Audio length is recovered from the file if the process dies before finalize.
    }

    @Synchronized fun finishCapture(id: String) {
        val file = audioFile(id)
        RandomAccessFile(file, "rw").use { output ->
            val bytes = (output.length() - WAV_HEADER_BYTES).coerceAtLeast(0)
            require(bytes <= Int.MAX_VALUE - WAV_HEADER_BYTES) { "capture too long" }
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

    @Synchronized fun save(origin: String, pcm: ByteArray): String {
        val id = create(origin)
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(pcm.size, offset + MAX_CHUNK_BYTES)
            append(id, offset.toLong(), pcm.copyOfRange(offset, end))
            offset = end
        }
        finishCapture(id)
        return id
    }

    @Synchronized fun beginAttempt(id: String) = update(id) {
        it.put("status", "transcribing")
            .put("attempts", it.optInt("attempts") + 1)
            .put("error", "")
    }

    @Synchronized fun result(id: String, text: String, engine: String, raw: String = "") = update(id) {
        it.put("status", "ready")
            .put("transcript", text)
            .put("engine", engine)
            .put("error", "")
            .put("raw_result", raw)
    }

    @Synchronized fun failure(id: String, message: String, raw: String = "") = update(id) {
        it.put("status", "failed").put("error", message)
        if (raw.isNotBlank()) it.put("raw_result", raw)
    }

    @Synchronized fun insertion(id: String, status: String) = update(id) {
        it.put("insertion", status)
    }

    @Synchronized fun list(): List<Entry> = root.listFiles().orEmpty().mapNotNull { dir ->
        runCatching {
            val json = readJson(dir.name)
            if (json.optString("status") == "capturing" &&
                System.currentTimeMillis() - audioFile(dir.name).lastModified() > 60_000) {
                finishCapture(dir.name)
            }
            entry(readJson(dir.name))
        }.getOrNull()
    }.sortedByDescending { it.createdAt }

    @Synchronized fun get(id: String): Entry = entry(readJson(id))

    @Synchronized fun pcm(id: String): ByteArray {
        val file = audioFile(id)
        require(file.length() >= WAV_HEADER_BYTES) { "Audio file is missing" }
        require(file.length() <= MAX_AUDIO_BYTES + WAV_HEADER_BYTES) { "Audio file is too large" }
        return RandomAccessFile(file, "r").use { input ->
            input.seek(WAV_HEADER_BYTES.toLong())
            ByteArray((input.length() - WAV_HEADER_BYTES).toInt()).also { input.readFully(it) }
        }
    }

    fun wavFile(id: String): File = audioFile(id)

    /** Portable training bundle: one WAV + JSON per capture and a JSONL index. */
    @Synchronized fun export(output: java.io.OutputStream) {
        ZipOutputStream(output).use { zip ->
            val entries = list().filter { it.status != "capturing" }
            entries.forEach { entry ->
                val json = readJson(entry.id)
                zip.putNextEntry(ZipEntry("${entry.id}/record.json"))
                zip.write(json.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                val wav = audioFile(entry.id)
                if (wav.isFile) {
                    zip.putNextEntry(ZipEntry("${entry.id}/$AUDIO"))
                    wav.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            zip.putNextEntry(ZipEntry("dataset.jsonl"))
            entries.forEach { entry ->
                zip.write((readJson(entry.id).toString() + "\n").toByteArray(Charsets.UTF_8))
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
        private const val META = "record.json"
        private const val AUDIO = "sample.wav"
        private const val WAV_HEADER_BYTES = 44
        const val MAX_CHUNK_BYTES = 128 * 1024
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
