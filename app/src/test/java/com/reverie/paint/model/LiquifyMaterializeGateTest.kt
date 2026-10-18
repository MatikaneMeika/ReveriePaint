// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class LiquifyMaterializeGateTest {
    @Test fun `slow engine keeps only one idle tick pending and accepts work after completion`() {
        val gate = LiquifyMaterializeGate()
        assertTrue(gate.tryQueue())
        repeat(120) { assertFalse(gate.tryQueue()) }
        gate.finish()
        assertTrue(gate.tryQueue())
        try {
            throw IllegalStateException("simulated native failure")
        } catch (_: IllegalStateException) {
            // The engine runnable must release its gate on this path too.
        } finally {
            gate.finish()
        }
        assertTrue(gate.tryQueue())
    }

    @Test fun `concurrent producers cannot queue duplicate ticks`() {
        val gate = LiquifyMaterializeGate()
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val workers = List(8) {
            Thread {
                start.await()
                repeat(100) { if (gate.tryQueue()) accepted.incrementAndGet() }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(1, accepted.get())
        gate.finish()
        assertTrue(gate.tryQueue())
    }
}
