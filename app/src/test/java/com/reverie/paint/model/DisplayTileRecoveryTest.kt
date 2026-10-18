// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class DisplayTileRecoveryTest {
    @Test fun `retry exhaustion preserves failed tiles and successful neighbors`() {
        val recovery = DisplayTileRecovery()
        recovery.reset(3)
        recovery.pending[1] = false
        repeat(3) { assertTrue(recovery.shouldRetry(true)) }
        repeat(10) { assertFalse(recovery.shouldRetry(true)) }
        assertArrayEquals(booleanArrayOf(true, false, true), recovery.pending)
        recovery.pending.fill(false) // A later external render successfully repairs the frame.
        assertFalse(recovery.shouldRetry(false))
        recovery.pending[2] = true
        assertTrue(recovery.shouldRetry(true))
        assertArrayEquals(booleanArrayOf(false, false, true), recovery.pending)
    }

    @Test fun `new document resets retry budget and requires every tile`() {
        val recovery = DisplayTileRecovery()
        recovery.reset(2)
        repeat(4) { recovery.shouldRetry(true) }
        recovery.reset(4)
        assertArrayEquals(booleanArrayOf(true, true, true, true), recovery.pending)
        assertTrue(recovery.shouldRetry(true))
        recovery.reset(0)
        assertTrue(recovery.pending.isEmpty())
    }
}
