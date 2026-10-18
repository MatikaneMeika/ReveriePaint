// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import org.junit.Assert.*
import org.junit.Test

class LiquifyPresentationFenceTest {
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
