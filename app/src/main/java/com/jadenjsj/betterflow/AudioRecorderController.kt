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
    fun start() {
        if (recording.get()) return
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 5 * 2)
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
        worker = Thread({ captureLoop(record, bufferSize) }, "betterflow-audio").also { it.start() }
    }

    private fun captureLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        while (recording.get()) {
            val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (count > 0) {
                synchronized(this) { pcm.write(buffer, 0, count) }
            }
        }
    }

    @Synchronized
    fun stopAndGetWav(): ByteArray {
        if (!recording.getAndSet(false)) return ByteArray(0)
        val record = audioRecord
        try { record?.stop() } catch (_: Throwable) {}
        worker?.join(1200)
        record?.release()
        audioRecord = null
        worker = null
        val raw = pcm.toByteArray()
        return wav(raw)
    }

    fun isRecording(): Boolean = recording.get()

    private fun wav(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size + 44)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + raw.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(raw.size)
        out.write(header.array())
        out.write(raw)
        return out.toByteArray()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
