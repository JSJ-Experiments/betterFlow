package com.jadenjsj.betterflow

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class AudioRecorderController {
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private var pcm = ByteArrayOutputStream()
    @Volatile private var stopping = false
    @Volatile private var cutoffFrameExclusive = NO_CUTOFF_FRAME
    @Volatile private var startedAtNanos = 0L
    @Volatile private var deliveredFrames = 0L

    @Synchronized
    fun start(onPcmChunk: ((ByteArray) -> Unit)? = null, chunkBytes: Int = STREAM_CHUNK_BYTES) {
        if (recording.get() || stopping) return
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
        cutoffFrameExclusive = NO_CUTOFF_FRAME
        deliveredFrames = 0L
        recording.set(true)
        record.startRecording()
        startedAtNanos = System.nanoTime()
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
        var capturedFrames = 0L
        try {
            while (recording.get()) {
                val count = record.read(
                    chunk,
                    filled,
                    chunk.size - filled,
                    AudioRecord.READ_BLOCKING,
                )
                if (count <= 0) continue
                val cutoff = cutoffFrameExclusive
                val acceptedCount = if (cutoff == NO_CUTOFF_FRAME) {
                    count
                } else {
                    val framesRemaining = (cutoff - capturedFrames).coerceAtLeast(0L)
                    minOf(count, framesRemaining.times(SAMPLE_WIDTH_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
                if (acceptedCount > 0) {
                    synchronized(this) { pcm.write(chunk, filled, acceptedCount) }
                    filled += acceptedCount
                    capturedFrames += acceptedCount / SAMPLE_WIDTH_BYTES
                    deliveredFrames = capturedFrames
                }
                if (filled == chunk.size) {
                    onPcmChunk?.invoke(chunk.copyOf())
                    filled = 0
                }
                if (cutoff != NO_CUTOFF_FRAME && capturedFrames >= cutoff) {
                    recording.set(false)
                }
            }
        } finally {
            if (filled > 0) onPcmChunk?.invoke(chunk.copyOf(filled))
        }
    }

    /**
     * Stops at the instant this method is called while optionally draining audio
     * that Android captured before the tap but has not delivered to our read loop.
     * Samples captured after the call are truncated using AudioRecord's monotonic
     * frame timestamp; [drainTimeoutMs] is only how long we wait for old samples.
     */
    fun stopAndGetPcm(
        preservePreTapTail: Boolean = false,
        drainTimeoutMs: Int = 0,
        cutoffNanos: Long = System.nanoTime(),
    ): ByteArray = stopInternal(preservePreTapTail, drainTimeoutMs, cutoffNanos).pcm

    private fun stopInternal(
        preservePreTapTail: Boolean,
        drainTimeoutMs: Int,
        cutoffNanos: Long,
    ): StopResult {
        val record: AudioRecord
        val captureWorker: Thread?
        synchronized(this) {
            if (!recording.get()) return StopResult(pcm.toByteArray(), ownedRecorder = false)
            record = audioRecord ?: return StopResult(pcm.toByteArray(), ownedRecorder = false)
            captureWorker = worker
            // Claim this AudioRecord before waiting for the tail. Cancellation or
            // destruction may call stop again while the first caller is draining.
            audioRecord = null
            worker = null
            stopping = true
            if (preservePreTapTail && drainTimeoutMs > 0) {
                cutoffFrameExclusive = estimateCutoffFrame(record, cutoffNanos)
            } else {
                recording.set(false)
            }
        }

        if (preservePreTapTail && drainTimeoutMs > 0) {
            // The capture loop exits as soon as it has read through the tap frame.
            // This wait does not extend the transcript beyond that frame.
            runCatching { captureWorker?.join(drainTimeoutMs.coerceAtLeast(1).toLong()) }
        }
        recording.set(false)
        // AudioRecord.stop() unblocks READ_BLOCKING. Do not hold this object's
        // monitor while joining: captureLoop needs the same monitor for its
        // final PCM write before it can exit.
        try {
            record.stop()
        } catch (_: Throwable) {
        }
        runCatching { captureWorker?.join(1200) }
        runCatching { record.release() }

        synchronized(this) {
            stopping = false
            return StopResult(pcm.toByteArray(), ownedRecorder = true)
        }
    }

    private data class StopResult(val pcm: ByteArray, val ownedRecorder: Boolean)

    private fun estimateCutoffFrame(record: AudioRecord, cutoffNanos: Long): Long {
        val elapsedCutoffFrame = nanosToFrames((cutoffNanos - startedAtNanos).coerceAtLeast(0L))
        val timestamp = AudioTimestamp()
        val status = runCatching {
            record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
        }.getOrDefault(AudioRecord.ERROR_INVALID_OPERATION)
        if (status == AudioRecord.SUCCESS && timestamp.nanoTime > 0L) {
            val elapsedNanos = (cutoffNanos - timestamp.nanoTime).coerceAtLeast(0L)
            val timestampCutoffFrame = timestamp.framePosition + nanosToFrames(elapsedNanos)
            // Reject devices whose frame counter does not reset for this AudioRecord.
            // Already-delivered frames were necessarily captured before the tap.
            if (abs(timestampCutoffFrame - elapsedCutoffFrame) <= MAX_TIMESTAMP_SKEW_FRAMES) {
                return timestampCutoffFrame.coerceAtLeast(deliveredFrames)
            }
        }

        // Some devices do not expose AudioRecord timestamps. Frame zero begins at
        // startRecording(), so elapsed monotonic time is a safe cutoff fallback.
        return elapsedCutoffFrame.coerceAtLeast(deliveredFrames)
    }

    private fun nanosToFrames(nanos: Long): Long =
        ((nanos.toDouble() * SAMPLE_RATE.toDouble()) / NANOS_PER_SECOND).toLong()

    fun stopAndGetWav(): ByteArray = pcmToWav(stopAndGetPcm())

    fun stopAndDiscard() {
        val result = stopInternal(
            preservePreTapTail = false,
            drainTimeoutMs = 0,
            cutoffNanos = System.nanoTime(),
        )
        if (result.ownedRecorder) synchronized(this) { pcm = ByteArrayOutputStream() }
    }

    fun isRecording(): Boolean = recording.get()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val SAMPLE_WIDTH_BYTES = 2
        const val STREAM_CHUNK_MS = 100
        const val STREAM_CHUNK_BYTES = SAMPLE_RATE * CHANNELS * SAMPLE_WIDTH_BYTES * STREAM_CHUNK_MS / 1000
        private const val NO_CUTOFF_FRAME = Long.MAX_VALUE
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        // One 200 ms capture buffer is enough to absorb normal timestamp jitter.
        private const val MAX_TIMESTAMP_SKEW_FRAMES = SAMPLE_RATE / 5L

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
