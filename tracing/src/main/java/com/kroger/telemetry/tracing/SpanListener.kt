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
 * Live callbacks for vendor SDKs that have to be driven while the work happens.
 * Register with [Tracer.Builder.listeners].
 *
 * [S] is this listener's own per-span state, typically the vendor object for the span. The tracer stores whatever
 * [onStart] returns and passes it back to the other callbacks for that span, and to [onStart] of its children.
 * The state lives exactly as long as the span, so the listener needs no maps or cleanup of its own.
 *
 * Best-effort contract, per listener and span:
 * - [onStart] is called at most once. If it returns non-null state, [onEnd] is called exactly once, whether the span
 *   ends explicitly, by truncation, or because its trace closed (unless the root is never ended).
 * - If [onStart] returns null or throws, nothing else is called for that span, and its children receive `parent = null`.
 * - Callbacks run synchronously on the thread that made the triggering call, before that call returns. The exception:
 *   a span truncated while its own [onStart] calls are still running gets its [onEnd] later, from the starting thread.
 * - A thrown exception, `CancellationException` included, is caught and reported once; it never affects other
 *   listeners or the caller.
 * - When app calls are ordered (sequential or structured concurrency), callbacks follow that order: a parent's
 *   [onStart] before its children's, and children's [onEnd] before the parent's. Unordered concurrent calls on one
 *   trace can reorder callbacks, so check the vendor's own "finished" state before each call instead of relying on
 *   how the vendor handles late calls.
 *
 * Keep callbacks fast, put the vendor's terminal call in `finally`, and don't call tracing APIs from a callback:
 * it can't deadlock, but its behavior isn't defined.
 */
@RealtimeTracingApi
@ExperimentalTracingApi
public interface SpanListener<S : Any> {
    /**
     * Called synchronously when a span starts. Return this listener's state for the span, or null to ignore it.
     * @param parent the state this listener returned for the parent span. Null for roots, and null when the listener
     *   ignored the parent or its [onStart] threw.
     */
    public fun onStart(
        span: SpanStart,
        parent: S?,
    ): S?

    /**
     * Called exactly once for every span whose [onStart] returned non-null state, when the span ends, whether it was
     * ended explicitly or truncated. [span] is the same snapshot that is exported. Put the vendor's terminal call in `finally`.
     */
    public fun onEnd(
        state: S,
        span: SpanData,
    ) {}

    /**
     * Called only for HTTP spans, when the integration asks for [HttpRequestSpan.propagationHeaders]. Return the headers
     * to add. It can be called after [onEnd] if a request races the end of its own span, so only read [state].
     */
    public fun propagationHeaders(state: S): Map<String, String> = emptyMap()
}

/** What a listener can see when a span starts. Immutable. */
@RealtimeTracingApi
@ExperimentalTracingApi
public class SpanStart internal constructor(
    /** The trace this span belongs to. */
    public val traceId: String,
    /** This span's ID. The same value appears later in [SpanData.spanId]. */
    public val spanId: String,
    /** True for the root span of a trace. */
    public val isRoot: Boolean,
    /** The span's name, as it will appear in [SpanData.name]. */
    public val name: String,
    /** True for spans created by [Span.startHttpRequest]. */
    public val isHttpRequest: Boolean,
) {
    override fun toString(): String = "SpanStart(name=$name, spanId=$spanId)"
}
