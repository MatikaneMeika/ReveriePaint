// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

class DisplayTileFrameVersionTest {
    @Test fun `draw overlapping a write cannot confirm presentation`() {
        val version = DisplayTileFrameVersion()
        val before = version.capture()
        assertTrue(version.isCurrent(before))
        version.beginWrite()
        val during = version.capture()
        assertFalse(version.isCurrent(before))
        assertFalse(version.isCurrent(during))
        version.endWrite()
        assertFalse(version.isCurrent(before))
        assertFalse(version.isCurrent(during))
        assertTrue(version.isCurrent(version.capture()))
    }

    @Test fun `later writes to same grid never reuse old presentation version`() {
        val version = DisplayTileFrameVersion()
        version.beginWrite()
        version.endWrite()
        val old = version.capture()
        repeat(10) {
            version.beginWrite()
            version.endWrite()
            assertFalse(version.isCurrent(old))
        }
    }

    @Test fun `engine finishing between UI capture and acknowledgement rejects stale draw`() {
        val version = DisplayTileFrameVersion()
        val writerStarted = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val writer = Thread {
            version.beginWrite()
            writerStarted.countDown()
            finishWrite.await()
            version.endWrite()
        }
        val before = version.capture()
        writer.start()
        writerStarted.await()
        val during = version.capture()
        try {
            assertFalse(version.isCurrent(before))
            assertFalse(version.isCurrent(during))
        } finally {
            finishWrite.countDown()
            writer.join()
        }
        assertFalse(version.isCurrent(during))
        assertTrue(version.isCurrent(version.capture()))
    }
}
