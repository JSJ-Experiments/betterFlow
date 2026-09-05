package com.jadenjsj.betterflow

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorderController {
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var pcm = ByteArrayOutputStream()

    @Synchronized
    fun start(onPcmChunk: ((ByteArray) -> Unit)? = null, chunkBytes: Int = STREAM_CHUNK_BYTES) {
        if (recording.get()) return
        require(chunkBytes > 0) { "chunkBytes must be > 0" }
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, STREAM_CHUNK_BYTES * 2)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        pcm = ByteArrayOutputStream()
        audioRecord = record
        recording.set(true)
        record.startRecording()
        worker = Thread(
            { captureLoop(record, chunkBytes, onPcmChunk) },
            "betterflow-audio",
        ).also { it.start() }
    }

    private fun captureLoop(
        record: AudioRecord,
        chunkBytes: Int,
        onPcmChunk: ((ByteArray) -> Unit)?,
    ) {
        val chunk = ByteArray(chunkBytes)
        var filled = 0
        try {
            while (recording.get()) {
                val count = record.read(
                    chunk,
                    filled,
                    chunk.size - filled,
                    AudioRecord.READ_BLOCKING,
                )
                if (count <= 0) continue
                synchronized(this) { pcm.write(chunk, filled, count) }
                filled += count
                if (filled == chunk.size) {
                    onPcmChunk?.invoke(chunk.copyOf())
                    filled = 0
                }
            }
        } finally {
            if (filled > 0) onPcmChunk?.invoke(chunk.copyOf(filled))
        }
    }

    fun stopAndGetPcm(): ByteArray {
        val record: AudioRecord?
        val captureWorker: Thread?
        synchronized(this) {
            if (!recording.getAndSet(false)) return pcm.toByteArray()
            record = audioRecord
            captureWorker = worker
        }

        // AudioRecord.stop() unblocks READ_BLOCKING. Do not hold this object's
        // monitor while joining: captureLoop needs the same monitor for its
        // final PCM write before it can exit.
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        captureWorker?.join(1200)
        record?.release()

        synchronized(this) {
            if (audioRecord === record) audioRecord = null
            if (worker === captureWorker) worker = null
            return pcm.toByteArray()
        }
    }

    fun stopAndGetWav(): ByteArray = pcmToWav(stopAndGetPcm())

    fun stopAndDiscard() {
        stopAndGetPcm()
        synchronized(this) { pcm = ByteArrayOutputStream() }
    }

    fun isRecording(): Boolean = recording.get()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val SAMPLE_WIDTH_BYTES = 2
        const val STREAM_CHUNK_MS = 100
        const val STREAM_CHUNK_BYTES = SAMPLE_RATE * CHANNELS * SAMPLE_WIDTH_BYTES * STREAM_CHUNK_MS / 1000

        fun pcmToWav(raw: ByteArray): ByteArray {
            val evenSize = raw.size - (raw.size % SAMPLE_WIDTH_BYTES)
            val out = ByteArrayOutputStream(evenSize + 44)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + evenSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(CHANNELS.toShort())
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * CHANNELS * SAMPLE_WIDTH_BYTES)
            header.putShort((CHANNELS * SAMPLE_WIDTH_BYTES).toShort())
            header.putShort((SAMPLE_WIDTH_BYTES * 8).toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(evenSize)
            out.write(header.array())
            out.write(raw, 0, evenSize)
            return out.toByteArray()
        }
    }
}
