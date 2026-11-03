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
 * The live, writable handle for one unit of work. App code holds it while the work runs and passes it
 * to code that adds children or data. Every method is thread-safe. None throws an exception because of tracing: exceptions from listeners and sanitizers are contained and reported as diagnostics.
 * After [end], every method is a no-op except the `start*` methods, which return inert spans.
 * What was recorded can be read from [SpanData] in the exported [TraceFacet], not from this handle.
 */
@ExperimentalTracingApi
public sealed interface Span {
    /** The trace this span belongs to: 32 lowercase hex characters (W3C format). Useful for correlating logs with traces. */
    public val traceId: String

    /** This span's ID: 16 lowercase hex characters (W3C format). All zeros for an inert span. */
    public val spanId: String

    /**
     * Starts a child span. Returns an inert span if this span has already ended or the trace already has the maximum
     * of 256 spans, in which case the drop is counted in [TraceFacet.droppedSpanCount]. Prefer the [child] helper.
     */
    public fun startChild(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
    ): Span

    /**
     * Starts a child span for one outgoing HTTP request, named "HTTP <METHOD>" (e.g. "HTTP GET").
     * The URL is sanitized by the tracer's [UrlSanitizer] before anything is stored, and is never part of the name.
     * Returns an inert span if this span has already ended or the trace already has the maximum of 256 spans.
     */
    public fun startHttpRequest(
        method: String,
        url: String,
        attributes: Map<String, Any?> = emptyMap(),
    ): HttpRequestSpan

    /**
     * Sets an attribute that describes the span as a whole. Setting an existing key replaces its value, and only the
     * change in its text is charged to this span's text budget, so updating one attribute repeatedly doesn't use it up.
     * A write that would grow the span's text past its budget is dropped and counted; the old value stays.
     * Accepted values: String, Long, Double and Boolean. Int, Short and Byte become Long, Float becomes Double
     * through its decimal form (0.1f is stored as 0.1), and an Enum becomes its name. A `null` value is ignored. Other types are dropped and reported once.
     */
    public fun setAttribute(
        key: String,
        value: Any?,
    )

    /** Records that something happened at this moment, e.g. "cache_miss". Repeated names are kept as separate events. */
    public fun addEvent(name: String)

    /** Records an exception as an event named "exception". It doesn't change the status by itself. */
    public fun recordException(throwable: Throwable)

    /**
     * Sets the outcome explicitly. The last call wins, and a replaced description gives its text budget back.
     * Use it for errors that are returned rather than thrown.
     */
    public fun setStatus(status: SpanStatus)

    /** Ends this span and force-ends any descendants that are still open. Only the first call has an effect. */
    public fun end()
}

/** A [Span] for one HTTP request attempt. Created only by [Span.startHttpRequest]. */
@ExperimentalTracingApi
public sealed interface HttpRequestSpan : Span {
    /**
     * Headers to add to the outgoing request so that backends can associate it with this span.
     * Merged from the listeners in list order; on a duplicate name the first listener wins.
     * Empty once the span has ended. Never overwrite a header that's already on the request.
     */
    public fun propagationHeaders(): Map<String, String>

    /**
     * Records the response status code; a later call replaces an earlier one. If the latest code is 400 or above,
     * the span reads `Error("HTTP <code>")`, unless a status was set explicitly or the span was truncated. The
     * description is charged to the span's text budget when the code is recorded; if it doesn't fit, the span reads
     * `Error(null)`.
     */
    public fun setResponse(statusCode: Int)
}
