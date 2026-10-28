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

import com.kroger.telemetry.facet.Facet

/**
 * A finished trace: every span under one root, delivered once when the root ends. Recorded on the tracer's
 * [Tracer.Builder.telemeter] so that relays can export it, e.g. `Relay.buildTypedRelay<TraceFacet> { }`.
 * A root that's never ended produces no [TraceFacet].
 */
@ExperimentalTracingApi
public class TraceFacet internal constructor(
    /** The trace ID shared by every span. */
    public val traceId: String,
    /** The root span. Its duration is the duration of the whole trace. */
    public val root: SpanData,
    /** Every recorded span, the root and truncated spans included, in start order. Children can be found by [SpanData.parentSpanId]. */
    public val spans: List<SpanData>,
    /** Spans that weren't recorded because of the span limit or the text budget. */
    public val droppedSpanCount: Int,
) : Facet {
    /** For logs only: the format may change in any release, so don't parse it. */
    override fun toString(): String =
        "TraceFacet(name=${root.name}, spans=${spans.size}, duration=${root.duration.inWholeMilliseconds}ms, status=${root.status}" +
            "${if (droppedSpanCount > 0) ", droppedSpans=$droppedSpanCount" else ""})"
}

/**
 * A problem inside tracing itself, e.g. a listener that threw or a dropped attribute. It isn't an app failure.
 * Recorded on the tracer's telemeter with `Significance.WARNING`, and with `Failure` only when [throwable] is non-null.
 *
 * Recorded as it happens, by the call that ran into the problem, not with the trace. Each distinct problem is
 * recorded only the first time in the tracer's lifetime, so diagnostics show that a problem occurs, not how
 * often. For counts, read [SpanData.droppedAttributeCount], [SpanData.droppedEventCount],
 * [TraceFacet.droppedSpanCount] and [SpanData.isTruncated].
 */
@ExperimentalTracingApi
public class TracingDiagnostic internal constructor(
    /** One of the [Kinds] constants. A String rather than an enum, so that kinds can be added without breaking anyone. */
    public val kind: String,
    /** A short human-readable explanation, built from class names and limit values. Never contains caller-supplied text, URLs or exception messages. */
    public val message: String,
    /** The class name of the listener or sanitizer involved, if any. */
    public val source: String?,
    /**
     * A redacted copy of the exception a listener threw: the original's class name (in `toString()`) and stack trace,
     * without its message or causes, which could carry an unsanitized URL, a credential or user data. Only set for
     * [Kinds.LISTENER_THREW] and [Kinds.ATTRIBUTES_UNREADABLE].
     */
    public val throwable: Throwable?,
) : Facet {
    override fun toString(): String = "TracingDiagnostic(kind=$kind, message=$message)"

    /** The values of [kind]. More may be added in any release, so handle unknown kinds. */
    public object Kinds {
        /** A listener threw. Reported once per (listener, exception class). */
        public const val LISTENER_THREW: String = "listener_threw"

        /**
         * A custom URL sanitizer threw, so the URL was omitted. Carries no exception, because it could contain the URL.
         * Reported once per sanitizer.
         */
        public const val SANITIZER_THREW: String = "sanitizer_threw"

        /** An attribute value had an unsupported type and was dropped. Reported once per value type. */
        public const val ATTRIBUTE_DROPPED: String = "attribute_dropped"

        /**
         * The map of initial attributes threw while being read.
         * Entries read before the failure are kept; the rest are lost and not counted as dropped. Reported once per
         * exception class.
         */
        public const val ATTRIBUTES_UNREADABLE: String = "attributes_unreadable"

        /** A count limit, length cap or the text budget dropped or truncated data. Reported once per limit. */
        public const val LIMIT_EXCEEDED: String = "limit_exceeded"
    }
}
