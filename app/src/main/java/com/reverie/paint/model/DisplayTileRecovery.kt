// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

/** Engine-thread recovery ledger. Exhausting retries must not discard invalid pixels. */
internal class DisplayTileRecovery {
    var pending = BooleanArray(0)
        private set
    private var consecutiveFailures = 0

    fun reset(tileCount: Int) {
        pending = BooleanArray(tileCount) { true }
        consecutiveFailures = 0
    }

    fun shouldRetry(failed: Boolean): Boolean {
        if (!failed) {
            consecutiveFailures = 0
            return false
        }
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(4)
        return consecutiveFailures <= 3
    }
}
