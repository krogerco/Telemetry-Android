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

/**
 * Entry point for tracing. Usually one instance per app, created once and injected wherever traces start.
 * Thread-safe. Holds configuration only; it keeps no record of the traces it has started.
 */
@ExperimentalTracingApi
public class Tracer internal constructor(
    /** Null only for [NoOp]. */
    private val core: TracerCore?,
) {
    /**
     * Starts a new, independent trace and returns its root span.
     * The caller must call [Span.end] on the root. Prefer the [trace] helper, which always does.
     * @param name low-cardinality operation name, e.g. "search". Never put IDs or user data in it.
     * @param attributes initial attributes; see [Span.setAttribute] for the accepted value types.
     */
    public fun startTrace(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
    ): Span = core?.startTrace(name, attributes) ?: InertSpan.NoOp

    /** Configuration for a [Tracer]. New options are added as new properties, which never breaks callers. */
    public class Builder {
        /** Where completed traces ([TraceFacet]) and diagnostics ([TracingDiagnostic]) are recorded. If null, neither is recorded. */
        public var telemeter: Telemeter? = null

        /**
         * Decides how much of each request URL is stored. Defaults to [UrlSanitizer.Default], which redacts credentials
         * and token/password parameter values but keeps the rest, query included. See [UrlSanitizer] for the
         * alternatives, such as [UrlSanitizer.StripQuery].
         */
        public var urlSanitizer: UrlSanitizer = UrlSanitizer.Default

        /** Realtime listeners, called in list order. Copied when [build] runs. */
        @RealtimeTracingApi
        public val listeners: MutableList<SpanListener<*>> = mutableListOf()

        /** Only this library's tests replace the clock. */
        internal var clock: TraceClock = TraceClock.System

        /** Creates a [Tracer] from the current values. Later changes to this builder don't affect it. */
        @OptIn(RealtimeTracingApi::class)
        public fun build(): Tracer {
            // A listener only ever receives state it returned itself, so erasing S is safe.
            @Suppress("UNCHECKED_CAST")
            val listeners = listeners.toList() as List<SpanListener<Any>>
            return Tracer(TracerCore(telemeter, listeners, urlSanitizer, clock))
        }
    }

    public companion object {
        /** Builds a [Tracer]: `Tracer.build { telemeter = appTelemeter }`. */
        public fun build(configure: Builder.() -> Unit = {}): Tracer = Builder().apply(configure).build()

        /** A tracer whose spans are all inert: nothing is recorded, exported or sent to listeners. Use it in tests and when tracing is off. */
        public val NoOp: Tracer = Tracer(core = null)
    }
}
