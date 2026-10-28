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

/**
 * An attribute map whose iteration signals [entered] and then waits for [release]. A span start reads its initial
 * attributes before taking the trace lock, so this parks a start at a known point for deterministic race tests.
 */
internal class BlockingMap(
    private val entered: CountDownLatch,
    private val release: CountDownLatch,
) : AbstractMap<String, Any?>() {
    override val entries: Set<Map.Entry<String, Any?>>
        get() {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "release latch timed out" }
            return emptySet()
        }

    // Non-empty, so the start doesn't skip iterating it.
    override val size: Int get() = 1

    override fun isEmpty(): Boolean = false
}
