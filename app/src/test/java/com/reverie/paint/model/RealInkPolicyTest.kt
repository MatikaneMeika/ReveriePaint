package com.reverie.paint.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealInkPolicyTest {
    @Test
    fun realInkDisablesPrediction() {
        assertFalse(RealInkPolicy.predictionAllowed(realInkOnly = true))
        assertTrue(RealInkPolicy.predictionAllowed(realInkOnly = false))
    }

    @Test
    fun scratchOnlyForNormalBrushesInRealInkMode() {
        assertTrue(RealInkPolicy.engineScratchEligible(true, true, "brush", "paintbrush"))
        assertFalse(RealInkPolicy.engineScratchEligible(false, true, "brush", "paintbrush"))
        assertFalse(RealInkPolicy.engineScratchEligible(true, false, "brush", "paintbrush"))
        assertFalse(RealInkPolicy.engineScratchEligible(true, true, "eraser", "paintbrush"))
        assertFalse(RealInkPolicy.engineScratchEligible(true, true, "brush", "colorsmudge"))
        assertFalse(RealInkPolicy.engineScratchEligible(true, true, "brush", "deformbrush"))
    }

    @Test
    fun failureLatchTripsAndResets() {
        val latch = RealInkPolicy.FailureLatch(threshold = 2)
        latch.onFailure()
        assertFalse(latch.tripped)
        latch.onFailure()
        assertTrue(latch.tripped)
        latch.onSuccess()
        assertFalse(latch.tripped)
    }

    @Test
    fun breakerIgnoresWarmupNulls() {
        val b = RealInkPolicy.StrokeBreaker(warmupMs = 250L, nullThreshold = 3)
        repeat(50) { b.onResult(false, 10L, 1.0) }
        assertFalse(b.tripped)
        repeat(2) { b.onResult(false, 300L, 1.0) }
        assertFalse(b.tripped)
        b.onResult(true, 300L, 1.0)
        repeat(2) { b.onResult(false, 300L, 1.0) }
        assertFalse(b.tripped)
        b.onResult(false, 300L, 1.0)
        assertTrue(b.tripped)
    }

    @Test
    fun breakerTripsOnSlowRenders() {
        val b = RealInkPolicy.StrokeBreaker(slowRenderMs = 12.0, slowThreshold = 2)
        b.onResult(true, 0L, 20.0)
        assertFalse(b.tripped)
        b.onResult(true, 0L, 20.0)
        assertTrue(b.tripped)
        b.reset()
        assertFalse(b.tripped)
    }

    @Test
    fun ineligibleReasons() {
        org.junit.Assert.assertNull(RealInkPolicy.ineligibleReason(true, true, "brush", "paintbrush", true))
        org.junit.Assert.assertEquals("realInkOff", RealInkPolicy.ineligibleReason(false, true, "brush", "paintbrush", true))
        org.junit.Assert.assertEquals("nativeMissing", RealInkPolicy.ineligibleReason(true, true, "brush", "paintbrush", false))
    }
}
