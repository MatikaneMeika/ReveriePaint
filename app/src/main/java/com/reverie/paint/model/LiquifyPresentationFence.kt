// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

/** Retirement requires the committed bitmap or tile revision to reach the drawing consumer. */
class LiquifyPresentationFence<T : Any> {
    @Volatile private var gesture = -1L
    private var frame: T? = null
    private var frameVersion = 0L
    @Synchronized fun arm(id: Long) { gesture = id; frame = null }
    fun isPending(id: Long): Boolean = gesture == id
    @Synchronized fun cancel() { gesture = -1; frame = null }
    @Synchronized fun publish(id: Long, value: T, version: Long = 0L) {
        if (gesture == id) {
            frame = value
            frameVersion = version
        }
    }
    @Synchronized fun consume(id: Long, value: T, version: Long = 0L): Boolean {
        if (gesture != id) return false
        if (frame !== value || frameVersion != version) return false
        gesture = -1
        frame = null
        return true
    }
}
