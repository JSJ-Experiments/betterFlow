package com.jadenjsj.betterflow

import java.util.concurrent.Executor

/** Coalesces outbox drains on a serial executor; acknowledgement runs behind the drain. */
internal class HistorySyncQueue(private val executor: Executor) {
    private val lock = Any()
    private var requested: (() -> Unit)? = null
    private var scheduled = false

    fun request(work: () -> Unit, acknowledged: (() -> Unit)? = null) = synchronized(lock) {
        requested = work
        if (!scheduled) {
            scheduled = true
            executor.execute {
                while (true) {
                    val next = synchronized(lock) {
                        val value = requested
                        requested = null
                        if (value == null) scheduled = false
                        value
                    } ?: break
                    // One corrupt entry/failed handoff must not leave scheduled=true forever.
                    runCatching { next() }
                }
            }
        }
        if (acknowledged != null) executor.execute { runCatching { acknowledged() } }
    }
}
