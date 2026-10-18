// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class LiquifyPresentationFenceTest {
    @Test fun `same grid identity with old version cannot retire new committed preview`() {
        val fence = LiquifyPresentationFence<Any>()
        val grid = Any()
        fence.arm(1)
        fence.publish(1, grid, 2L)
        fence.publish(1, grid, 4L)
        assertFalse(fence.consume(1, grid, 2L))
        assertFalse(fence.consume(1, grid))
        assertTrue(fence.isPending(1))
        assertTrue(fence.consume(1, grid, 4L))
    }

    @Test fun `replacement grid cannot reuse old grid version acknowledgement`() {
        val fence = LiquifyPresentationFence<Any>()
        val oldGrid = Any()
        val newGrid = Any()
        fence.arm(1)
        fence.publish(1, newGrid, 2L)
        assertFalse(fence.consume(1, oldGrid, 2L))
        assertTrue(fence.consume(1, newGrid, 2L))
    }

    @Test fun `failed tile update keeps preview until recovery publishes complete version`() {
        val fence = LiquifyPresentationFence<Any>()
        val grid = Any()
        val version = DisplayTileFrameVersion()
        val recovery = DisplayTileRecovery()
        recovery.reset(1)
        fence.arm(1)
        repeat(5) {
            version.beginWrite()
            version.endWrite()
            recovery.shouldRetry(true)
            // Renderer must not publish a version while full-write recovery is pending.
            if (!recovery.pending.any { it }) fence.publish(1, grid, version.capture())
            assertFalse(fence.consume(1, grid, version.capture()))
        }
        assertTrue(recovery.pending[0])
        version.beginWrite()
        recovery.pending[0] = false
        version.endWrite()
        fence.publish(1, grid, version.capture())
        assertTrue(fence.consume(1, grid, version.capture()))
    }

    @Test fun `old or partial frame cannot retire committed preview`() {
        val fence = LiquifyPresentationFence<Any>()
        val oldFrame = Any()
        val partialFrame = Any()
        val completeFrame = Any()
        fence.arm(1)
        assertFalse(fence.consume(1, partialFrame))
        fence.publish(1, completeFrame)
        assertFalse(fence.consume(1, oldFrame))
        assertFalse(fence.consume(1, partialFrame))
        assertTrue(fence.isPending(1))
        assertTrue(fence.consume(1, completeFrame))
        assertFalse(fence.consume(1, completeFrame))
    }

    @Test fun `late frame from previous gesture cannot replace new completion`() {
        val fence = LiquifyPresentationFence<Any>()
        val oldFrame = Any()
        val newFrame = Any()
        fence.arm(1)
        fence.publish(1, oldFrame)
        fence.arm(2)
        fence.publish(2, newFrame)
        fence.publish(1, oldFrame)
        assertFalse(fence.consume(1, oldFrame))
        assertFalse(fence.consume(2, oldFrame))
        assertTrue(fence.consume(2, newFrame))
        fence.arm(3)
        fence.publish(3, newFrame)
        fence.cancel()
        assertFalse(fence.consume(3, newFrame))
    }
}
