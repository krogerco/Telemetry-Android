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

    /**
     * Whether a trace started now would be recorded, per [Builder.enabledWhen]. Always false for [NoOp].
     *
     * Evaluated on every read, so don't cache it. Use it to skip work that only feeds a trace, such as building an
     * attribute map, when missing that data on a recorded trace is acceptable. This is only an advisory check:
     * [startTrace] checks independently, so a later change can produce an inert trace or a recorded trace
     * without the data whose preparation was skipped.
     */
    public val isEnabled: Boolean
        get() = core?.isEnabled() ?: false

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

        /**
         * Decides each time a trace starts whether it is recorded. When it returns false, [Tracer.startTrace] returns
         * an inert span and nothing is recorded or sent to listeners. Defaults to always on.
         *
         * The decision is made once at the root. A trace that started enabled stays enabled if this later returns
         * false, and one that started inert never records. Use it for a switch that can change after the [Tracer] is
         * built, such as a remote config that loads after startup.
         *
         * It runs on every [Tracer.startTrace], possibly on the main thread, so it must be fast and must not block.
         * If it throws, the trace is inert and a [TracingDiagnostic.Kinds.ENABLED_WHEN_THREW] is recorded on [telemeter],
         * once per exception class. The same applies when [Tracer.isEnabled] is read.
         */
        public var enabledWhen: () -> Boolean = { true }

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
            return Tracer(
                core =
                    TracerCore(
                        telemeter = telemeter,
                        listeners = listeners,
                        sanitizer = urlSanitizer,
                        enabledWhen = enabledWhen,
                        clock = clock,
                    ),
            )
        }
    }

    public companion object {
        /** Builds a [Tracer]: `Tracer.build { telemeter = appTelemeter }`. */
        public fun build(configure: Builder.() -> Unit = {}): Tracer = Builder().apply(configure).build()

        /** A tracer whose spans are all inert: nothing is recorded, exported or sent to listeners. Use it in tests and when tracing is off. */
        public val NoOp: Tracer = Tracer(core = null)
    }
}
