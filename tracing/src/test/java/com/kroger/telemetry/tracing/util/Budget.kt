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
@file:OptIn(ExperimentalTracingApi::class)

package com.kroger.telemetry.tracing.util

import com.kroger.telemetry.tracing.ExperimentalTracingApi
import com.kroger.telemetry.tracing.Span
import com.kroger.telemetry.tracing.SpanImpl
import com.kroger.telemetry.tracing.TraceLimits

/** Chars charged to this span's own text budget so far. */
internal val Span.budgetUsed: Int get() = (this as SpanImpl).textUsed

/** Chars left in this span's own text budget. */
internal val Span.budgetLeft: Int get() = TraceLimits.SPAN_TEXT_BUDGET - budgetUsed

/**
 * Spends this span's budget until exactly [leaving] chars remain, using string attributes for bulk and one event for
 * the remainder. The span should have no attributes yet.
 */
internal fun Span.fillBudget(leaving: Int) {
    var index = 0
    while (budgetLeft > leaving) {
        val need = budgetLeft - leaving
        val before = budgetUsed
        if (need <= TraceLimits.MAX_NAME_LENGTH) {
            addEvent("e".repeat(need))
        } else {
            val key = "f%02d".format(index++)
            setAttribute(key, "v".repeat(minOf(need - key.length, TraceLimits.MAX_STRING_VALUE_LENGTH)))
        }
        check(budgetUsed > before) { "fillBudget made no progress; the span is probably at a count limit" }
    }
}
