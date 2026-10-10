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
}
