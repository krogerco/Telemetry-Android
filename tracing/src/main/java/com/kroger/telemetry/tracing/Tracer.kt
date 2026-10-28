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

import com.kroger.telemetry.Telemeter
import kotlin.time.TimeSource

public interface Tracer {

    /** The [TraceRecorder] used to record span lifecycle events. Use [CompositeTraceRecorder] if you have multiple backends */
    public val traceRecorder: TraceRecorder

    /** The [TimeSource] used to measure span durations. */
    public val timeSource: TimeSource

    /**
     * Starts a new [Trace] with the given [name] and optional [attributes].
     *
     * Creates a root span via the [traceRecorder] and wraps it in a [Trace] that
     * owns a unique trace ID. The caller is responsible for ending the trace
     * (via [Trace.end]) when the traced operation completes.
     *
     * @param name Human-readable name describing the operation being traced.
     * @param attributes Initial key-value attributes to attach to the root span.
     * @return A new [Trace] representing the started operation.
     */
    public fun trace(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
    ): Trace

    public companion object {
        /**
         * Creates a [Telemeter] that adds tracing capabilities to an existing [Telemeter].
         *
         * @param telemeter The base [Telemeter] to extend with tracing. Could be a StandardTelemeter.
         * @param traceRecorder The [TraceRecorder] used to record span lifecycle events.
         * @param timeSource The [TimeSource] used to measure span durations.
         */
        public fun build(
            traceRecorder: TraceRecorder,
            timeSource: TimeSource,
        ): Tracer =
            StandardTracer(
                traceRecorder = traceRecorder,
                timeSource = timeSource,
            )
    }
}
