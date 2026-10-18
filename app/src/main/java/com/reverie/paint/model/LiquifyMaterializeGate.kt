// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

import java.util.concurrent.atomic.AtomicBoolean

/** At most one idle materialization task may be queued or running. No edit inputs are dropped. */
internal class LiquifyMaterializeGate {
    private val pending = AtomicBoolean(false)

    fun tryQueue(): Boolean = pending.compareAndSet(false, true)

    fun finish() {
        pending.set(false)
    }
}
