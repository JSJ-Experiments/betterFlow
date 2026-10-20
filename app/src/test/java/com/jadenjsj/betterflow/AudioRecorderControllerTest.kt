package com.jadenjsj.betterflow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AudioRecorderControllerTest {
    private class Input : AudioRecorderController.PcmInput {
        val reads = AtomicInteger()
        val stops = AtomicInteger()
        val releases = AtomicInteger()
        val enteredRead = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val released = CountDownLatch(1)
        val uncaught = AtomicReference<Throwable?>()
        @Volatile var captureThread: Thread? = null
        var startError: Throwable? = null
        var readAction: (ByteArray, Int, Int) -> Int = { _, _, _ ->
            check(stopped.await(5, TimeUnit.SECONDS))
            -3
        }
        var timestampAction: () -> AudioRecorderController.FrameTimestamp? = { null }
        override fun start() { startError?.let { throw it } }
        override fun read(buffer: ByteArray, offset: Int, size: Int): Int {
            captureThread = Thread.currentThread().also {
                it.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) }
            }
            reads.incrementAndGet()
            enteredRead.countDown()
            return readAction(buffer, offset, size)
        }
        override fun stop() { stops.incrementAndGet(); stopped.countDown() }
        override fun release() { releases.incrementAndGet(); released.countDown() }
        override fun timestamp() = timestampAction()
        fun awaitFinished() {
            assertTrue("microphone released", released.await(5, TimeUnit.SECONDS))
            captureThread?.join(5_000)
            assertFalse("capture thread exits", captureThread?.isAlive == true)
            assertEquals(1, stops.get())
            assertEquals(1, releases.get())
        }
    }

    @Test fun failedAndEmptyBlockingReadsExitRatherThanBusyLoop() {
        for (result in listOf(0, -1, -2, -3, -6)) {
            val input = Input().apply { readAction = { _, _, _ -> result } }
            val recorder = AudioRecorderController { input }
            recorder.start()
            input.awaitFinished()
            assertEquals(1, input.reads.get())
            assertFalse(recorder.isRecording())
            assertArrayEquals(byteArrayOf(), recorder.stopAndGetPcm())
            recorder.stopAndDiscard()
            assertEquals(1, input.releases.get())
        }
    }

    @Test fun failedStartReleasesInputAndAllowsAnotherRecording() {
        val failed = Input().apply { startError = IllegalStateException("cannot start") }
        val next = Input()
        var attempt = 0
        val recorder = AudioRecorderController { if (attempt++ == 0) failed else next }
        assertThrows(IllegalStateException::class.java) { recorder.start() }
        failed.awaitFinished()
        assertFalse(recorder.isRecording())
        recorder.start()
        assertTrue(next.enteredRead.await(5, TimeUnit.SECONDS))
        recorder.stopAndDiscard()
        next.awaitFinished()
    }

    @Test fun stopUnblocksReadAndReleasesExactlyOnce() {
        val input = Input()
        val recorder = AudioRecorderController { input }
        recorder.start()
        assertTrue(input.enteredRead.await(5, TimeUnit.SECONDS))
        recorder.stopAndGetPcm()
        input.awaitFinished()
        recorder.stopAndGetPcm()
        recorder.stopAndDiscard()
        assertFalse(recorder.isRecording())
        assertEquals(1, input.releases.get())
    }

    @Test fun callbackFailureStillReleasesInputWithoutRedeliveringChunk() {
        val input = Input().apply {
            readAction = { bytes, offset, size ->
                bytes.fill(1, offset, offset + size)
                size
            }
        }
        val calls = AtomicInteger()
        val recorder = AudioRecorderController { input }
        recorder.start(onPcmChunk = { calls.incrementAndGet(); error("callback failed") }, chunkBytes = 4)
        input.awaitFinished()
        assertEquals(1, calls.get())
        assertFalse(recorder.isRecording())
        assertArrayEquals(byteArrayOf(1, 1, 1, 1), recorder.stopAndGetPcm())
        assertEquals("callback failed", input.uncaught.get()?.message)
    }

    @Test fun readExceptionStillReleasesInput() {
        val input = Input().apply { readAction = { _, _, _ -> error("read failed") } }
        val recorder = AudioRecorderController { input }
        recorder.start()
        input.awaitFinished()
        assertFalse(recorder.isRecording())
        assertEquals("read failed", input.uncaught.get()?.message)
    }

    @Test fun errorPreservesPartialAudioAndDeliversTailOnce() {
        val input = Input()
        input.readAction = { bytes, offset, _ ->
            if (input.reads.get() == 1) {
                bytes[offset] = 1; bytes[offset + 1] = 2
                2
            } else -6
        }
        val chunks = mutableListOf<ByteArray>()
        val recorder = AudioRecorderController { input }
        recorder.start(onPcmChunk = { chunks.add(it) }, chunkBytes = 4)
        input.awaitFinished()
        assertArrayEquals(byteArrayOf(1, 2), recorder.stopAndGetPcm())
        assertEquals(1, chunks.size)
        assertArrayEquals(byteArrayOf(1, 2), chunks.single())
    }

    @Test fun preTapDrainKeepsCutoffAndReleasesAfterLoopStopsItself() {
        val allowRead = CountDownLatch(1)
        val cutoff = System.nanoTime()
        lateinit var tailRecorder: AudioRecorderController
        val draining = Input().apply {
            timestampAction = {
                allowRead.countDown()
                AudioRecorderController.FrameTimestamp(2, cutoff)
            }
            readAction = { bytes, offset, size ->
                check(allowRead.await(5, TimeUnit.SECONDS))
                // timestamp() runs while stop owns the controller monitor.
                // Wait until it publishes the cutoff, not merely until it starts
                // estimating it; otherwise this fixture races pre-stop delivery.
                synchronized(tailRecorder) { }
                bytes.fill(7, offset, offset + size)
                size
            }
        }
        tailRecorder = AudioRecorderController { draining }
        tailRecorder.start(chunkBytes = 8)
        assertTrue(draining.enteredRead.await(5, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(7, 7, 7, 7), tailRecorder.stopAndGetPcm(
            preservePreTapTail = true, drainTimeoutMs = 1000, cutoffNanos = cutoff,
        ))
        draining.awaitFinished()
        assertFalse(tailRecorder.isRecording())
        tailRecorder.stopAndDiscard()
        assertEquals(1, draining.releases.get())
    }
}
