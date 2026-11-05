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
@file:OptIn(ExperimentalTracingApi::class, RealtimeTracingApi::class)

package com.kroger.telemetry.tracing

import java.util.Collections
import kotlin.time.TimeMark

/**
 * Everything one trace shares. Reachable only from that trace's spans; the [Tracer] never references it.
 *
 * Every public operation has the same shape: change state under [locked] and capture what needs emitting in a
 * [PendingDiagnostics], release the lock, dispatch to listeners, then [TracerCore.emit]. No foreign code (listeners,
 * sanitizers, telemeters, caller-supplied maps) runs under the lock.
 * A literal `return` inside [locked] is a compile error, because it would skip the emit.
 */
internal class TraceState(
    val traceId: String,
    val core: TracerCore,
    /** Wall-clock time when the root started. Every exported timestamp is this plus a monotonic offset. */
    val epochAnchorNanos: Long,
    /** Taken when the root started; [offsetNow] measures from it. */
    private val monotonicAnchor: TimeMark,
) {
    /** Guards everything below, and every [SpanImpl]'s mutable fields. Take it only through [locked]. */
    @PublishedApi
    internal val lock = Any()

    /**
     * Runs [block] holding [lock]. [block] is `crossinline`, so a literal `return` inside it doesn't compile: it would
     * skip the caller's emit.
     */
    inline fun <T> locked(crossinline block: () -> T): T = synchronized(lock) { block() }

    /** True once the root has ended. Every later start returns an inert span. */
    private var closed = false

    /** The next [SpanImpl.sequence]: start order, which sorts exported spans and orders force-ends. */
    private var nextSequence = 0L

    /** Spans recorded so far, checked against [TraceLimits.MAX_SPANS]. */
    private var spanCount = 0

    /** Starts rejected by the span limit, exported as [TraceFacet.droppedSpanCount]. */
    private var droppedSpanCount = 0

    /** Started and not yet ended. Searched for descendants when a span ends. */
    private val open = mutableSetOf<SpanImpl>()

    /** Ended spans, kept until the root ends and they're exported together. */
    private val finished = mutableListOf<SpanImpl>()

    /** Nanoseconds since the root started, on the monotonic clock. */
    fun offsetNow(): Long = monotonicAnchor.elapsedNow().inWholeNanoseconds

    /**
     * Starts a root (null [parent]) or a child. With a non-null [method] the result is an HTTP span named
     * "HTTP <METHOD>", and [name] is ignored. Returns an inert span when the start is rejected.
     *
     * A start that ends up inert reports only why it was dropped (the span limit); a late start reports nothing. Diagnostics gathered along the way, such as a truncated name or a throwing sanitizer, are discarded.
     */
    fun start(
        parent: SpanImpl?,
        name: String,
        attributes: Map<String, Any?>,
        method: String? = null,
        rawUrl: String? = null,
    ): Span {
        val pending = PendingDiagnostics()
        val result = startOrReject(parent, name, attributes, method, rawUrl, pending)
        core.emit(pending)
        return result
    }

    private fun startOrReject(
        parent: SpanImpl?,
        name: String,
        attributes: Map<String, Any?>,
        method: String?,
        rawUrl: String?,
        pending: PendingDiagnostics,
    ): Span {
        // A doomed start never runs the sanitizer or reads the caller's map, which could be slow or blocking. The checks
        // repeat below, because the parent can still end, or the limit fill, while they run.
        val rejected = locked { inertSpanIfRejectedLocked(parent, pending) }
        if (rejected != null) return rejected
        // Outside the lock: the sanitizer is foreign code, and so is the caller's Map implementation.
        val url = rawUrl?.let { core.sanitize(it, pending) }
        val initialAttributes = readInitialAttributes(attributes, pending)
        var parentStates: Array<Any?>? = null
        val span =
            locked {
                inertSpanIfRejectedLocked(parent, pending)
                    ?: startLocked(parent, name, initialAttributes, method, url, pending)
                        .also { parentStates = parent?.listenerStates }
            }
        if (span is SpanImpl && core.listeners.isNotEmpty()) notifyListenersOfStart(span, parentStates, pending)
        return span
    }

    /**
     * Runs the listeners' `onStart` outside the lock, then publishes their states only if the span is still open.
     * Publication and closure share the lock, so exactly one side delivers `onEnd`: [end] if it closes the span
     * after publication, or this method if the span closed while `onStart` was running. Neither path leaves
     * listener states attached to a closed span.
     */
    private fun notifyListenersOfStart(
        span: SpanImpl,
        parentStates: Array<Any?>?,
        pending: PendingDiagnostics,
    ) {
        val view = SpanStart(traceId, span.spanId, isRoot = span.parent == null, span.name, isHttpRequest = span is HttpSpanImpl)
        val states = core.dispatchStart(view, parentStates, pending)
        val endedMeanwhile =
            locked {
                if (span.ended) {
                    span.snapshot
                } else {
                    span.listenerStates = states
                    null
                }
            }
        if (endedMeanwhile != null) core.dispatchEnd(states, endedMeanwhile, pending)
    }

    /** Returns an inert span if the start is late or over the span limit, otherwise null. Caller holds [lock]. */
    private fun inertSpanIfRejectedLocked(
        parent: SpanImpl?,
        pending: PendingDiagnostics,
    ): Span? =
        when {
            closed || parent?.ended == true -> {
                pending.clear() // late calls are silent
                InertSpan(traceId)
            }

            spanCount >= TraceLimits.MAX_SPANS -> {
                dropLocked(
                    Limit.SPANS,
                    "Span limit of ${TraceLimits.MAX_SPANS} reached; span dropped",
                    pending,
                )
            }

            else -> {
                null
            }
        }

    private fun dropLocked(
        limit: Limit,
        reason: String,
        pending: PendingDiagnostics,
    ): Span {
        droppedSpanCount = droppedSpanCount.saturatingPlus(1)
        pending.clear()
        pending.limitExceeded(limit, reason)
        return InertSpan(traceId)
    }

    private fun startLocked(
        parent: SpanImpl?,
        name: String,
        initialAttributes: InitialAttributes,
        method: String?,
        url: String?,
        pending: PendingDiagnostics,
    ): Span {
        val httpMethod = method?.uppercase()?.capTo(TraceLimits.MAX_METHOD_LENGTH, Limit.HTTP_METHOD, "HTTP method", pending)
        val spanName =
            (if (httpMethod != null) "HTTP $httpMethod" else name)
                .capTo(TraceLimits.MAX_NAME_LENGTH, Limit.SPAN_NAME, "Span name", pending)
        val span =
            if (httpMethod != null) {
                HttpSpanImpl(this, Ids.newSpanId(), parent, spanName, nextSequence++, offsetNow(), httpMethod, url.orEmpty())
            } else {
                SpanImpl(this, Ids.newSpanId(), parent, spanName, nextSequence++, offsetNow())
            }
        for (attribute in initialAttributes.entries) span.putAttributeLocked(attribute, pending)
        span.dropAttributesLocked(initialAttributes.unread, pending)
        open += span
        spanCount++
        return span
    }

    /** Ends [span], force-ending its open descendants with one shared closure time. Closes the trace for the root. */
    fun end(span: SpanImpl) {
        val pending = PendingDiagnostics()
        // Set only when this call ends the root; the guard on ended makes that happen once per trace.
        var completion: TraceFacet? = null
        // (states, snapshot) of each closed span whose onStart fan-out had finished, in closing order.
        var endWork: List<Pair<Array<Any?>, SpanData>> = emptyList()
        locked {
            if (!span.ended) {
                val closeOffset = offsetNow()
                // Deepest and latest first, then the span itself; siblings are unaffected.
                val targets = open.filter { it !== span && it.isDescendantOf(span) }.sortedByDescending { it.sequence } + span
                for (target in targets) {
                    target.closeLocked(closeOffset, truncated = target !== span)
                    open -= target
                    finished += target
                }
                if (span.parent == null) {
                    closed = true
                    val spans = finished.sortedBy { it.sequence }.map { it.snapshot!! }
                    completion =
                        TraceFacet(traceId, span.snapshot!!, Collections.unmodifiableList(spans), droppedSpanCount)
                    finished.clear()
                }
                // Spans still in their onStart fan-out (null states) get onEnd from the starting thread instead.
                if (core.listeners.isNotEmpty()) {
                    // Detach the arrays without clearing entries: in-flight starts and header reads may still use them.
                    endWork =
                        targets.mapNotNull { target ->
                            target.listenerStates?.let { states ->
                                target.listenerStates = null
                                states to target.snapshot!!
                            }
                        }
                }
            }
        }
        // Outside the lock, deepest and latest first, then the span itself; before the completion is recorded.
        for ((states, data) in endWork) core.dispatchEnd(states, data, pending)
        core.emit(pending, completion)
    }
}
