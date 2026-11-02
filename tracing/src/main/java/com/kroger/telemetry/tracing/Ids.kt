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

import java.security.SecureRandom
import java.util.Random

/**
 * W3C-format IDs from a shared [SecureRandom].
 *
 * Generated IDs are never all zeros, which W3C reserves as invalid; only inert spans use the all zero IDs.
 */
internal object Ids {
    /** The trace ID of [Tracer.NoOp]'s inert spans. */
    const val INVALID_TRACE_ID: String = "00000000000000000000000000000000"

    /** The span ID of every inert span. */
    const val INVALID_SPAN_ID: String = "0000000000000000"

    private val HEX = "0123456789abcdef".toCharArray()

    private val sharedRandom: Random by lazy { SecureRandom() }

    /** 32 lowercase hex chars. */
    fun newTraceId(random: Random = sharedRandom): String {
        var high: Long
        var low: Long
        do {
            high = random.nextLong()
            low = random.nextLong()
        } while (high == 0L && low == 0L)
        val chars = CharArray(32)
        writeHex(high, chars, 0)
        writeHex(low, chars, 16)
        return String(chars)
    }

    /** 16 lowercase hex chars. */
    fun newSpanId(random: Random = sharedRandom): String {
        var id: Long
        do {
            id = random.nextLong()
        } while (id == 0L)
        val chars = CharArray(16)
        writeHex(id, chars, 0)
        return String(chars)
    }

    /** [value] as 16 lowercase hex chars, zero-padded, treating it as unsigned. */
    private fun writeHex(
        value: Long,
        dest: CharArray,
        offset: Int,
    ) {
        for (i in 0 until 16) {
            val shift = (15 - i) * 4
            dest[offset + i] = HEX[((value ushr shift) and 0xF).toInt()]
        }
    }
}
