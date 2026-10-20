package com.jadenjsj.betterflow

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

class HistorySyncQueueTest {
    private class Serial : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queued.addLast(command) }
        fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
    }

    @Test
    fun refreshCoalescesWithPendingKeyboardWorkAndAcknowledgesAfterHandoff() {
        val executor = Serial()
        val queue = HistorySyncQueue(executor)
        val events = mutableListOf<String>()
        queue.request({ events += "old" })
        queue.request({ events += "latest handoff" }, { events += "app refresh acknowledged" })
        executor.runAll()
        assertEquals(listOf("latest handoff", "app refresh acknowledged"), events)
    }

    @Test
    fun resultArrivingDuringSyncIsDrainedBeforeRefreshAcknowledgement() {
        val executor = Serial()
        val queue = HistorySyncQueue(executor)
        val events = mutableListOf<String>()
        queue.request({
            events += "audio"
            queue.request({ events += "final transcript" })
        }, { events += "ack" })
        executor.runAll()
        assertEquals(listOf("audio", "final transcript", "ack"), events)
    }

    @Test
    fun corruptOutboxDoesNotWedgeLaterRefreshOrAcknowledgement() {
        val executor = Serial()
        val queue = HistorySyncQueue(executor)
        val events = mutableListOf<String>()
        queue.request({ error("unreadable outbox") }, { events += "first ack" })
        executor.runAll()
        queue.request({ events += "retry" }, { events += "second ack" })
        executor.runAll()
        assertEquals(listOf("first ack", "retry", "second ack"), events)
    }
}
