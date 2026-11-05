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

/**
 * The outcome of a span. Frozen once shipped, because adding a subtype would break consumers' exhaustive `when`.
 * There will never be a Timeout subtype (timeouts are `Error("timeout")`) or an Unset subtype (tracked internally).
 */
@ExperimentalTracingApi
public sealed interface SpanStatus {
    /** Completed normally. This is also what a span reads as if no status was set. */
    public data object Ok : SpanStatus

    /** Stopped before completing, e.g. the user left the screen or the span was truncated. Not a failure. */
    public data object Cancelled : SpanStatus

    /**
     * Failed. [description] is short, low-cardinality text such as "HTTP 503" or "IOException".
     * Never put user data or exception messages in it.
     */
    public class Error(
        public val description: String? = null,
    ) : SpanStatus {
        override fun equals(other: Any?): Boolean = other is Error && other.description == description

        override fun hashCode(): Int = description.hashCode()

        override fun toString(): String = if (description == null) "Error" else "Error($description)"
    }
}
