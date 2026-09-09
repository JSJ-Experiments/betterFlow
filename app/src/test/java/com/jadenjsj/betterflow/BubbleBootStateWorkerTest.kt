package com.jadenjsj.betterflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BubbleBootStateWorkerTest {
    @Test
    fun rapidEnableThenDisableLeavesMarkerDisabled() {
        val markerEnabled = AtomicBoolean(false)
        val updates = Collections.synchronizedList(mutableListOf<Boolean>())
        val enableStarted = CountDownLatch(1)
        val releaseEnable = CountDownLatch(1)
        val disableFinished = CountDownLatch(1)
        val disableSucceeded = AtomicBoolean(false)
        val worker = BubbleBootStateWorker { enabled ->
            updates += enabled
            if (enabled) {
                enableStarted.countDown()
                check(releaseEnable.await(5, TimeUnit.SECONDS))
            }
            markerEnabled.set(enabled)
            true
        }

        worker.setAsync(true)
        assertTrue(enableStarted.await(5, TimeUnit.SECONDS))

        Thread {
            disableSucceeded.set(worker.set(false))
            disableFinished.countDown()
        }.start()
        releaseEnable.countDown()

        assertTrue(disableFinished.await(5, TimeUnit.SECONDS))
        assertTrue(disableSucceeded.get())
        assertEquals(listOf(true, false), updates)
        assertFalse(markerEnabled.get())
    }
}
