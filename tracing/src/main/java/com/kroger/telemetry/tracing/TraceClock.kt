/**
 * MIT License
 *
 * Copyright (c) 2026 The Kroger Co. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.kroger.telemetry.tracing

import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Each trace reads the wall clock once, when its root starts, and measures everything else as monotonic offsets
 * from that anchor, so a wall-clock change mid-trace can't make an event appear before its span started.
 */
internal interface TraceClock {
    /** Wall-clock time in nanoseconds since the Unix epoch. Read once per trace, as its anchor. */
    fun nowEpochNanos(): Long

    /** A monotonic mark. Spans and events are timed as offsets from the one taken when the root started. */
    fun markNow(): TimeMark

    /** The production clock. Wall-clock precision is milliseconds, scaled to nanoseconds. */
    object System : TraceClock {
        override fun nowEpochNanos(): Long = java.lang.System.currentTimeMillis() * 1_000_000

        override fun markNow(): TimeMark = TimeSource.Monotonic.markNow()
    }
}
