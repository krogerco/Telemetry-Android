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
@file:OptIn(ExperimentalTracingApi::class)

package com.kroger.telemetry.tracing

import java.util.Collections
import kotlin.time.Duration.Companion.nanoseconds

/** The real span. Identity is immutable; everything else is guarded by `state.locked`. */
internal open class SpanImpl(
    val state: TraceState,
    final override val spanId: String,
    /** Internal only: the public model knows a parent by ID, so deep children can't walk up and end the root. */
    val parent: SpanImpl?,
    val name: String,
    /** Start order within the trace. Exported spans are sorted by it, and force-ends run latest first. */
    val sequence: Long,
    /** When the span started, as an offset from the trace's monotonic anchor. */
    private val startOffsetNanos: Long,
) : Span {
    final override val traceId: String get() = state.traceId

    /** Set once, under the lock, when the span closes. Every write checks it first. */
    var ended: Boolean = false
        private set
    private var truncated = false

    /** Set by [setStatus] or a helper. Null means unset: the status is derived when the span closes. */
    private var explicitStatus: SpanStatus? = null
    private val attributes = mutableMapOf<String, Any>()
    private val events = mutableListOf<SpanEvent>()
    private var droppedAttributes = 0
    private var droppedEvents = 0

    /** Set once, when the span ends. */
    var snapshot: SpanData? = null
        private set

    /**
     * Each listener's state (null entries where it declined or threw). Held only after `onStart` finishes and
     * until end delivery is claimed.
     */
    var listenerStates: Array<Any?>? = null

    override fun startChild(
        name: String,
        attributes: Map<String, Any?>,
    ): Span = state.start(this, name, attributes)

    override fun startHttpRequest(
        method: String,
        url: String,
        attributes: Map<String, Any?>,
    ): HttpRequestSpan = state.start(this, name = "", attributes, method, url) as HttpRequestSpan

    override fun setAttribute(
        key: String,
        value: Any?,
    ) {
        // Normalized outside the lock, which only guards span state. Its diagnostics are kept apart, and only reported
        // if the span is still open: writes to an ended span are silent.
        val normalizing = PendingDiagnostics()
        val normalized = normalizeAttribute(key, value, normalizing)
        val pending =
            state.locked {
                if (ended || normalized == null) {
                    null
                } else {
                    putAttributeLocked(normalized, normalizing)
                    normalizing
                }
            }
        if (pending != null) state.core.emit(pending)
    }

    override fun addEvent(name: String): Unit = write { pending -> addEventLocked(name, throwable = null, pending) }

    override fun recordException(throwable: Throwable): Unit = write { pending -> addEventLocked(EXCEPTION_EVENT, throwable, pending) }

    override fun setStatus(status: SpanStatus): Unit = write { pending -> explicitStatus = acceptStatusLocked(status, pending) }

    override fun end(): Unit = state.end(this)

    /**
     * Applies a thrown exception for the inline helpers, atomically: records [throwable] as an event when it's
     * non-null, then sets [status] unless an explicit `Error` or `Cancelled` is already set.
     */
    fun applyThrown(
        status: SpanStatus,
        throwable: Throwable?,
    ): Unit =
        write { pending ->
            if (throwable != null) addEventLocked(EXCEPTION_EVENT, throwable, pending)
            val current = explicitStatus
            if (current == null || current == SpanStatus.Ok) explicitStatus = acceptStatusLocked(status, pending)
        }

    /** Runs [block] under the lock if the span is still open, then emits. Writes to an ended span are silent. */
    protected inline fun write(crossinline block: (PendingDiagnostics) -> Unit) {
        val pending = PendingDiagnostics()
        state.locked { if (!ended) block(pending) }
        state.core.emit(pending)
    }

    /** Counts [count] initial attributes that were never read, past the scan bound. Caller holds the lock. */
    fun dropAttributesLocked(
        count: Int,
        pending: PendingDiagnostics,
    ) {
        if (count == 0) return
        droppedAttributes = droppedAttributes.saturatingPlus(count)
        pending.limitExceeded(
            Limit.INITIAL_ENTRIES_READ,
            "Only the first ${TraceLimits.MAX_INITIAL_ENTRIES_READ} initial attributes were read; the rest were dropped",
        )
    }

    /** Checks the count limit, then reserves budget, for an attribute already normalized. Caller holds the lock. */
    fun putAttributeLocked(
        normalized: NormalizedAttribute,
        pending: PendingDiagnostics,
    ) {
        val key = normalized.key
        val accepted = normalized.value
        when {
            accepted == null -> {
                droppedAttributes = droppedAttributes.saturatingPlus(1)
            }

            key !in attributes && attributes.size >= TraceLimits.MAX_ATTRIBUTES -> {
                droppedAttributes = droppedAttributes.saturatingPlus(1)
                pending.limitExceeded(
                    Limit.ATTRIBUTES,
                    "Attribute limit of ${TraceLimits.MAX_ATTRIBUTES} reached; attribute dropped",
                )
            }

            !state.reserve(normalized.cost) -> {
                droppedAttributes = droppedAttributes.saturatingPlus(1)
                pending.limitExceeded(Limit.TEXT_BUDGET, "Text budget exhausted; attribute dropped")
            }

            else -> {
                attributes[key] = accepted
            } // replacing a key takes no new count slot
        }
    }

    private fun addEventLocked(
        name: String,
        throwable: Throwable?,
        pending: PendingDiagnostics,
    ) {
        val eventName = name.capTo(TraceLimits.MAX_NAME_LENGTH, Limit.EVENT_NAME, "Event name", pending)
        when {
            events.size >= TraceLimits.MAX_EVENTS -> {
                droppedEvents = droppedEvents.saturatingPlus(1)
                pending.limitExceeded(
                    Limit.EVENTS,
                    "Event limit of ${TraceLimits.MAX_EVENTS} reached; event dropped",
                )
            }

            !state.reserve(eventName.length) -> {
                droppedEvents = droppedEvents.saturatingPlus(1)
                pending.limitExceeded(Limit.TEXT_BUDGET, "Text budget exhausted; event dropped")
            }

            else -> {
                events +=
                    SpanEvent(
                        eventName,
                        state.epochAnchorNanos + state.offsetNow(),
                        throwable,
                    )
            }
        }
    }

    /** Caps and charges an `Error` description; if it doesn't fit, the status is kept and the description dropped. */
    protected fun acceptStatusLocked(
        status: SpanStatus,
        pending: PendingDiagnostics,
    ): SpanStatus {
        val description = (status as? SpanStatus.Error)?.description ?: return status
        val capped = description.capTo(TraceLimits.MAX_DESCRIPTION_LENGTH, Limit.STATUS_DESCRIPTION, "Status description", pending)
        if (!state.reserve(capped.length)) {
            pending.limitExceeded(Limit.TEXT_BUDGET, "Text budget exhausted; status description dropped")
            return SpanStatus.Error(null)
        }
        return if (capped == description) status else SpanStatus.Error(capped)
    }

    /** True if [ancestor] is this span's parent, grandparent or further up. False for the span itself. */
    fun isDescendantOf(ancestor: SpanImpl): Boolean {
        var current = parent
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    /** Marks the span ended and builds its snapshot. Caller holds the lock. */
    fun closeLocked(
        closeOffsetNanos: Long,
        truncated: Boolean,
    ) {
        ended = true
        this.truncated = truncated
        snapshot =
            SpanData(
                traceId = state.traceId,
                spanId = spanId,
                parentSpanId = parent?.spanId,
                name = name,
                startEpochNanos = state.epochAnchorNanos + startOffsetNanos,
                duration = (closeOffsetNanos - startOffsetNanos).coerceAtLeast(0).nanoseconds,
                status = statusLocked(),
                isTruncated = truncated,
                attributes = Collections.unmodifiableMap(attributes.toMap()),
                events = Collections.unmodifiableList(events.toList()),
                http = httpInfoLocked(),
                droppedAttributeCount = droppedAttributes,
                droppedEventCount = droppedEvents,
            )
    }

    /** Precedence: explicit status, then truncation, then the automatic HTTP status, then `Ok`. */
    private fun statusLocked(): SpanStatus =
        explicitStatus
            ?: if (truncated) SpanStatus.Cancelled else automaticStatusLocked() ?: SpanStatus.Ok

    /** The status a span reads when nothing set one, and it wasn't truncated. Only HTTP spans have one. Caller holds the lock. */
    protected open fun automaticStatusLocked(): SpanStatus? = null

    /** The snapshot's [SpanData.http]. Only HTTP spans have one. Caller holds the lock. */
    protected open fun httpInfoLocked(): HttpRequestInfo? = null

    override fun toString(): String = "Span(name=$name, spanId=$spanId)"

    private companion object {
        const val EXCEPTION_EVENT = "exception"
    }
}

/** The real [HttpRequestSpan]. [url] is already sanitized and capped; [method] is upper-cased and capped. */
internal class HttpSpanImpl(
    state: TraceState,
    spanId: String,
    parent: SpanImpl?,
    name: String,
    sequence: Long,
    startOffsetNanos: Long,
    private val method: String,
    private val url: String,
) : SpanImpl(state, spanId, parent, name, sequence, startOffsetNanos),
    HttpRequestSpan {
    private var statusCode: Int? = null

    /** The automatic status for the latest code, with its description already charged to the text budget. */
    private var automaticStatus: SpanStatus? = null

    override fun propagationHeaders(): Map<String, String> {
        val states = state.locked { if (ended) null else listenerStates } ?: return emptyMap()
        // Outside the lock, so a request racing this span's end may call a listener after its onEnd.
        val pending = PendingDiagnostics()
        val headers = state.core.collectHeaders(states, pending)
        state.core.emit(pending)
        return headers
    }

    /**
     * Stores the code, and for 400 and above the automatic `Error("HTTP <code>")`. Its description is charged to the
     * text budget now, like any other description, so snapshots never add uncharged text. It's charged even when an
     * explicit status will take precedence, because which status wins is only known when the span ends.
     */
    override fun setResponse(statusCode: Int): Unit =
        write { pending ->
            this.statusCode = statusCode
            automaticStatus =
                if (statusCode >= HTTP_ERROR_THRESHOLD) acceptStatusLocked(SpanStatus.Error("HTTP $statusCode"), pending) else null
        }

    override fun automaticStatusLocked(): SpanStatus? = automaticStatus

    override fun httpInfoLocked(): HttpRequestInfo = HttpRequestInfo(method, url, statusCode)

    private companion object {
        const val HTTP_ERROR_THRESHOLD = 400
    }
}

/**
 * The no-op span for every case tracing ignores: an ended parent, a closed trace, the span limit, the text budget,
 * and [Tracer.NoOp]. Invisible to listeners and never exported.
 */
internal class InertSpan(
    override val traceId: String,
) : HttpRequestSpan {
    override val spanId: String get() = Ids.INVALID_SPAN_ID

    override fun startChild(
        name: String,
        attributes: Map<String, Any?>,
    ): Span = this

    override fun startHttpRequest(
        method: String,
        url: String,
        attributes: Map<String, Any?>,
    ): HttpRequestSpan = this

    override fun setAttribute(
        key: String,
        value: Any?,
    ) {}

    override fun addEvent(name: String) {}

    override fun recordException(throwable: Throwable) {}

    override fun setStatus(status: SpanStatus) {}

    override fun end() {}

    override fun propagationHeaders(): Map<String, String> = emptyMap()

    override fun setResponse(statusCode: Int) {}

    override fun toString(): String = "Span(inert)"

    companion object {
        /** The span every [Tracer.NoOp] trace returns. Its trace ID is all zeros. */
        val NoOp: InertSpan = InertSpan(Ids.INVALID_TRACE_ID)
    }
}
