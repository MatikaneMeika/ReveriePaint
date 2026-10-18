// SPDX-License-Identifier: GPL-3.0-or-later
package com.reverie.paint.model

/**
 * Single engine writer, UI reader. Odd versions mean a write is in progress.
 * This guards presentation acknowledgement only; it does not grant bitmap reuse rights.
 * The counter belongs to one grid and allocates no objects when advancing or checking it.
 * Short metadata locks order the checks; no lock is held while writing or drawing pixels.
 */
internal class DisplayTileFrameVersion {
    private var version = 0L

    @Synchronized fun beginWrite() { version++ }

    @Synchronized fun endWrite() { version++ }

    @Synchronized fun capture(): Long = version

    @Synchronized fun isCurrent(candidate: Long): Boolean = candidate and 1L == 0L && candidate == version
}
