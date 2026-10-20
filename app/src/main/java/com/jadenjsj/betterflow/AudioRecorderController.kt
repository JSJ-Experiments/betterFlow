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

class AudioRecorderController internal constructor(private val createInput: () -> PcmInput) {
    constructor() : this({ AndroidPcmInput() })

    private val recording = AtomicBoolean(false)
    private var audioRecord: PcmInput? = null
    private var worker: Thread? = null
    private var pcm = ByteArrayOutputStream()
    @Volatile private var stopping = false
    @Volatile private var cutoffFrameExclusive = NO_CUTOFF_FRAME
    @Volatile private var startedAtNanos = 0L
    @Volatile private var deliveredFrames = 0L

    @Synchronized
    fun start(onPcmChunk: ((ByteArray) -> Unit)? = null, chunkBytes: Int = STREAM_CHUNK_BYTES) {
        if (recording.get() || stopping || worker?.isAlive == true) return
        require(chunkBytes > 0) { "chunkBytes must be > 0" }
        val record = createInput()
        pcm = ByteArrayOutputStream()
        audioRecord = record
        cutoffFrameExclusive = NO_CUTOFF_FRAME
        deliveredFrames = 0L
        try {
            record.start()
            startedAtNanos = System.nanoTime()
            recording.set(true)
            worker = Thread(
                { captureLoop(record, chunkBytes, onPcmChunk) },
                "betterflow-audio",
            ).also { it.start() }
        } catch (t: Throwable) {
            recording.set(false)
            audioRecord = null
            worker = null
            runCatching { record.stop() }
            runCatching { record.release() }
            throw t
        }
    }

    private fun captureLoop(
        record: PcmInput,
        chunkBytes: Int,
        onPcmChunk: ((ByteArray) -> Unit)?,
    ) {
        val chunk = ByteArray(chunkBytes)
        var filled = 0
        var capturedFrames = 0L
        try {
            while (recording.get()) {
                val count = record.read(chunk, filled, chunk.size - filled)
                // A blocking read returning no data/error must not become a hot
                // retry loop (e.g. a dead audio server or revoked permission).
                if (count <= 0) break
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
                    filled = 0
                    onPcmChunk?.invoke(chunk.copyOf())
                }
                if (cutoff != NO_CUTOFF_FRAME && capturedFrames >= cutoff) {
                    recording.set(false)
                }
            }
        } finally {
            try {
                if (filled > 0) onPcmChunk?.invoke(chunk.copyOf(filled))
            } finally {
                // Also release the microphone on read/callback failure. An
                // explicit stop may already own cleanup; never stop a new input.
                stopInternal(false, 0, System.nanoTime(), expectedInput = record, includePcm = false)
            }
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
        expectedInput: PcmInput? = null,
        includePcm: Boolean = true,
    ): StopResult {
        // Worker cleanup/cancellation must not copy an entire recording just to
        // discard the result. Call only while holding this controller's monitor.
        fun snapshot(owned: Boolean) = StopResult(if (includePcm) pcm.toByteArray() else ByteArray(0), owned)
        val record: PcmInput
        val captureWorker: Thread?
        synchronized(this) {
            record = audioRecord ?: return snapshot(false)
            if (expectedInput != null && record !== expectedInput) {
                return snapshot(false)
            }
            captureWorker = worker
            // Claim this AudioRecord before waiting for the tail. Cancellation or
            // destruction may call stop again while the first caller is draining.
            audioRecord = null
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
        if (captureWorker !== Thread.currentThread()) runCatching { captureWorker?.join(1200) }
        runCatching { record.release() }

        synchronized(this) {
            stopping = false
            return snapshot(true)
        }
    }

    private data class StopResult(val pcm: ByteArray, val ownedRecorder: Boolean)

    private fun estimateCutoffFrame(record: PcmInput, cutoffNanos: Long): Long {
        val elapsedCutoffFrame = nanosToFrames((cutoffNanos - startedAtNanos).coerceAtLeast(0L))
        val timestamp = runCatching { record.timestamp() }.getOrNull()
        if (timestamp != null && timestamp.nanoTime > 0L) {
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
            includePcm = false,
        )
        if (result.ownedRecorder) synchronized(this) { pcm = ByteArrayOutputStream() }
    }

    fun isRecording(): Boolean = recording.get()

    /** Small input boundary lets JVM tests exercise failure/stop resource ownership. */
    internal interface PcmInput {
        fun start()
        fun read(buffer: ByteArray, offset: Int, size: Int): Int
        fun stop()
        fun release()
        fun timestamp(): FrameTimestamp? = null
    }

    internal data class FrameTimestamp(val framePosition: Long, val nanoTime: Long)

    private class AndroidPcmInput : PcmInput {
        private val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            ), STREAM_CHUNK_BYTES * 2))
            .build().also {
                if (it.state != AudioRecord.STATE_INITIALIZED) {
                    it.release()
                    error("AudioRecord failed to initialize")
                }
            }

        override fun start() {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord failed to start" }
        }
        override fun read(buffer: ByteArray, offset: Int, size: Int): Int =
            record.read(buffer, offset, size, AudioRecord.READ_BLOCKING)
        override fun stop() = record.stop()
        override fun release() = record.release()
        override fun timestamp(): FrameTimestamp? {
            val stamp = AudioTimestamp()
            return if (record.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                FrameTimestamp(stamp.framePosition, stamp.nanoTime)
            } else null
        }
    }

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
