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

package com.kroger.telemetry.tracing.util

import com.kroger.telemetry.tracing.TraceClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.TestTimeSource
import kotlin.time.TimeMark

/**
 * A controllable [TraceClock]. [time] drives monotonic offsets; [wallNanos] is what the next trace reads as its anchor.
 * [gateNextOffsetRead] parks the next thread that reads an offset, which happens while it holds the trace lock.
 */
internal class FakeClock(
    var wallNanos: Long = 1_700_000_000_000_000_000L,
) : TraceClock {
    val time = TestTimeSource()

    @Volatile
    private var gate: Gate? = null

    override fun nowEpochNanos(): Long = wallNanos

    override fun markNow(): TimeMark {
        val mark = time.markNow()
        return object : TimeMark {
            override fun elapsedNow(): Duration {
                gate?.pass()
                return mark.elapsedNow()
            }
        }
    }

    /** The next offset read signals [entered] and then waits for [release]. One-shot. */
    fun gateNextOffsetRead(
        entered: CountDownLatch,
        release: CountDownLatch,
    ) {
        gate = Gate(entered, release)
    }

    private inner class Gate(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) {
        private val used = AtomicBoolean(false)

        fun pass() {
            if (!used.compareAndSet(false, true)) return
            gate = null
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "release latch timed out" }
        }
    }
}
