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

import kotlin.time.Duration

/**
 * An immutable snapshot of a span, built once when the span ends. Exported in [TraceFacet.spans].
 * Safe to keep and to read from any thread.
 */
@ExperimentalTracingApi
public class SpanData internal constructor(
    /** The trace this span belongs to. */
    public val traceId: String,
    /** This span's ID. */
    public val spanId: String,
    /** The parent's [spanId], or null for the root. Use it to rebuild the tree from [TraceFacet.spans]. */
    public val parentSpanId: String?,
    /** The name given when the span started (after the length cap). */
    public val name: String,
    /** Wall-clock start time, in nanoseconds since the Unix epoch. Derived from one wall-clock reading per trace plus monotonic time. */
    public val startEpochNanos: Long,
    /** How long the span ran, measured on a monotonic clock. Unaffected by wall-clock changes. */
    public val duration: Duration,
    /** The outcome. [SpanStatus.Ok] if nothing set it; [SpanStatus.Cancelled] if it was truncated with no explicit status. */
    public val status: SpanStatus,
    /** True if this span was force-ended because an ancestor ended first. Usually a structuring bug: a child that was never ended. */
    public val isTruncated: Boolean,
    /** The final attribute values. Values are only String, Long, Double or Boolean. */
    public val attributes: Map<String, Any>,
    /** Point-in-time events, in the order they were recorded. */
    public val events: List<SpanEvent>,
    /** Request details. Non-null only for spans created by [Span.startHttpRequest]. */
    public val http: HttpRequestInfo?,
    /**
     * Attributes dropped because of the count limit, the key length limit, an unsupported type, or the text budget,
     * plus initial attributes left unread because the map was larger than the read bound.
     */
    public val droppedAttributeCount: Int,
    /** Events dropped because of the count limit or the text budget. */
    public val droppedEventCount: Int,
) {
    override fun toString(): String =
        "SpanData(name=$name, duration=${duration.inWholeMilliseconds}ms, status=$status${if (isTruncated) ", truncated" else ""})"
}

/** Details of one HTTP request attempt, on [SpanData.http]. */
@ExperimentalTracingApi
public class HttpRequestInfo internal constructor(
    /** The request method, upper-cased, e.g. "GET". The same value appears in the span name. */
    public val method: String,
    /** The request URL after the tracer's [UrlSanitizer] ran. */
    public val url: String,
    /** The response status code, or null if no response arrived (failure or cancellation). */
    public val statusCode: Int?,
) {
    override fun toString(): String = "HttpRequestInfo(method=$method, status=${statusCode ?: "none"})"
}

/** One point-in-time occurrence during a span, recorded by [Span.addEvent] or [Span.recordException]. */
@ExperimentalTracingApi
public class SpanEvent internal constructor(
    /** What happened. "exception" for events recorded by [Span.recordException]. */
    public val name: String,
    /** When it happened, in nanoseconds since the Unix epoch, on the same clock as [SpanData.startEpochNanos]. */
    public val epochNanos: Long,
    /** The exception, only for events from [Span.recordException]. Borrowed, not copied: treat it as read-only. */
    public val throwable: Throwable?,
) {
    override fun toString(): String =
        if (throwable == null) "SpanEvent(name=$name)" else "SpanEvent(name=$name, throwable=${throwable.javaClass.simpleName})"
}
