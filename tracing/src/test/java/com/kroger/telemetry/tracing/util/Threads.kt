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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal const val TIMEOUT_SECONDS = 5L

internal fun CountDownLatch.awaitOrFail() {
    check(await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "latch timed out" }
}

/** Starts [block] on a new thread; [join][TestThread.join] rethrows anything it threw. */
internal fun testThread(block: () -> Unit): TestThread = TestThread(block)

internal class TestThread(
    block: () -> Unit,
) {
    private val failure = AtomicReference<Throwable>()
    val thread: Thread = thread { runCatching(block).onFailure(failure::set) }

    /** Waits until the thread is blocked on a monitor, i.e. waiting for the trace lock. */
    fun awaitBlocked() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (thread.state != Thread.State.BLOCKED) {
            check(thread.isAlive) { "thread finished instead of blocking" }
            check(System.nanoTime() < deadline) { "thread never blocked; state ${thread.state}" }
            Thread.onSpinWait()
        }
    }

    fun join() {
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
        check(!thread.isAlive) { "thread did not finish" }
        failure.get()?.let { throw it }
    }
}
