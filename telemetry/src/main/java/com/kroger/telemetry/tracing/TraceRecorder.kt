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
 * Backend interface for recording span lifecycle events.
 *
 * Implementations translate these calls into provider-specific tracing operations
 * (e.g. Dynatrace actions, OpenTelemetry exports). The library interacts with spans
 * through opaque [SpanHandle] tokens — the recorder creates handles via [startSpan]
 * and receives them back on subsequent calls.
 *
 * Implementations do not need to guard against duplicate [endSpan] calls; that
 * responsibility belongs to [SpanImpl].
 */
public interface TraceRecorder {
    /**
     * Opaque identifier for this recorder, used to route network request tags back to
     * the recorder that produced them. Implementations should return a stable, non-empty
     * string (e.g. `"dynatrace"`).
     */
    public val recorderId: String

    /**
     * Starts a new span with the given [name] and optional [attributes].
     *
     * @param name Human-readable name for the span.
     * @param attributes Initial key-value attributes to attach to the span.
     * @param parent Handle of the parent span, or `null` to start a root span.
     * @return An opaque [SpanHandle] representing the new span.
     */
    public fun startSpan(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
        parent: SpanHandle? = null,
    ): SpanHandle

    /** Attaches a key-value attribute to the span identified by [span]. */
    public fun setAttribute(span: SpanHandle, key: String, value: Any?)

    /** Records a timestamped event with the given [name] on the span identified by [span]. */
    public fun addEvent(span: SpanHandle, name: String)

    /**
     * Ends the span identified by [span].
     *
     * @param span Handle of the span to end.
     * @param status The outcome status of the span.
     * @param duration The elapsed duration of the span, as measured by the caller.
     * @param error The error associated with the span, if [status] is [SpanStatus.Error].
     */
    public fun endSpan(span: SpanHandle, status: SpanStatus, duration: Duration, error: Throwable? = null)

    /**
     * Returns an opaque network trace context string for distributed trace propagation,
     * or `null` if this recorder does not support it.
     */
    public fun getNetworkTraceContext(span: SpanHandle): String?

    /**
     * Collects request tags from this recorder keyed by [recorderId].
     *
     * The default implementation wraps the single-tag [getNetworkTraceContext] result.
     * [CompositeTraceRecorder] overrides this to collect from all delegates.
     */
    public fun getNetworkTraceContexts(span: SpanHandle): Map<String, String> {
        val tag = getNetworkTraceContext(span) ?: return emptyMap()
        return mapOf(recorderId to tag)
    }

    /**
     * Returns the provider-specific header name used to propagate request tags,
     * or an empty string if this recorder does not support network request timing.
     */
    public fun getRequestTagHeaderName(): String = ""

    /**
     * Starts network request timing for the given [requestTag] and [url].
     *
     * @param requestTag The opaque request tag used to correlate the request with the trace.
     * @param url The full URL of the network request.
     * @return A [NetworkRequestTimingScope] to report the request outcome and finalize timing.
     */
    public fun startNetworkRequestTiming(
        requestTag: String,
        url: String,
    ): NetworkRequestTimingScope = NoOpNetworkRequestTimingScope

    /**
     * Returns all (recorderId → headerName) pairs from recorders that support
     * network request timing.
     *
     * The default implementation returns this recorder's own mapping if
     * [getRequestTagHeaderName] is non-empty. [CompositeTraceRecorder] overrides
     * this to collect from all delegates.
     */
    public fun getAllRequestTagHeaders(): Map<String, String> {
        val header = getRequestTagHeaderName()
        return if (header.isNotEmpty()) mapOf(recorderId to header) else emptyMap()
    }

    /**
     * Starts network request timing for each recorder whose [recorderId] appears
     * in [requestTags], routing each request tag to its owning recorder.
     *
     * The default implementation looks up this recorder's own [recorderId] in the map.
     * [CompositeTraceRecorder] overrides this to fan out across delegates.
     *
     * @param requestTags Map of recorderId → requestTag from the [NetworkTraceContext].
     * @param url The full URL of the network request.
     * @return A [NetworkRequestTimingScope] to report the request outcome and finalize timing.
     */
    public fun startAllNetworkRequestTimings(
        requestTags: Map<String, String>,
        url: String,
    ): NetworkRequestTimingScope {
        val requestTag = requestTags[recorderId] ?: return NoOpNetworkRequestTimingScope
        return startNetworkRequestTiming(requestTag, url)
    }
}

/**
 * Opaque handle representing an active span in a [TraceRecorder] backend.
 *
 * Created by [TraceRecorder.startSpan] and passed back to subsequent [TraceRecorder]
 * calls. The handle's concrete type is internal to the recorder implementation.
 */
public interface SpanHandle

/**
 * A [TraceRecorder] that silently discards all operations.
 *
 * Used as the default recorder when tracing is not configured, ensuring that
 * tracing calls are safe no-ops rather than crashes.
 */
internal object NoOpTraceRecorder : TraceRecorder {
    override val recorderId: String = "noop"

    private object NoOpHandle : SpanHandle

    override fun startSpan(name: String, attributes: Map<String, Any?>, parent: SpanHandle?): SpanHandle = NoOpHandle
    override fun setAttribute(span: SpanHandle, key: String, value: Any?) {}
    override fun addEvent(span: SpanHandle, name: String) {}
    override fun endSpan(span: SpanHandle, status: SpanStatus, duration: Duration, error: Throwable?) {}
    override fun getNetworkTraceContext(span: SpanHandle): String? = null
}
