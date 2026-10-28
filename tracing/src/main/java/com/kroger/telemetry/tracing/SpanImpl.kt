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

import java.util.UUID
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Internal implementation of [Span] backed by a [TraceRecorder].
 *
 * Each [SpanImpl] wraps an opaque [SpanHandle] obtained from the recorder and delegates
 * all mutation operations (attributes, events, ending) to it. Timing is owned by this class:
 * a [TimeMark] is captured at construction, and the elapsed [Duration] is computed once when
 * [end] is called and forwarded to the recorder via [TraceRecorder.endSpan].
 *
 * Thread safety: [end] uses a synchronized guard so that only the first invocation
 * takes effect — duplicate calls are silently ignored, and the recorder receives exactly
 * one [TraceRecorder.endSpan] call per span. The [duration] field is assigned inside
 * the lock so that a concurrent caller cannot observe it as `null` after [end] returns.
 *
 * Child spans are created via [startChildSpan], which obtains a new [SpanHandle] from the
 * recorder with the current handle as parent. Children inherit the same [traceId],
 * [TraceRecorder], and [TimeSource] as their parent.
 *
 * @param handle Opaque handle representing this span in the [recorder] backend.
 * @param recorder The [TraceRecorder] that receives lifecycle events for this span.
 * @param timeSource The [TimeSource] used to measure span duration. Injected for testability.
 * @param traceId Identifier shared by all spans in the same trace.
 * @param name Human-readable name describing the operation this span represents.
 * @param parent The parent [Span], or `null` if this is a root span.
 */
internal class SpanImpl(
    private val handle: SpanHandle,
    private val recorder: TraceRecorder,
    private val timeSource: TimeSource,
    override val traceId: String,
    override val name: String,
    override val parent: Span?,
) : Span {
    /** Unique identifier for this span, generated as a random UUID. */
    override val spanId: String = UUID.randomUUID().toString()
    private val startMark: TimeMark = timeSource.markNow()
    private val lock = Any()
    private val childSpans = mutableListOf<SpanImpl>()

    internal val inProgressSpan =
        InProgressSpan(
            name = name,
            spanId = spanId,
            traceId = traceId,
            startMark = startMark,
        )

    internal var completedSpan: CompletedSpan? = null
        private set

    /**
     * The elapsed duration of this span, or `null` if [end] has not yet been called.
     * Once set, this value is frozen — subsequent calls to [end] do not update it.
     */
    override var duration: Duration? = null
        private set

    /**
     * Creates a child span with the given [name] and optional [attributes].
     *
     * The child shares this span's [traceId], [recorder], and [timeSource], and
     * sets this span as its [parent].
     */
    override fun startChildSpan(
        name: String,
        attributes: Map<String, Any?>,
    ): Span {
        val childHandle = recorder.startSpan(name, attributes, parent = handle)
        val span = SpanImpl(childHandle, recorder, timeSource, traceId, name, parent = this)
        childSpans.add(span)

        return span
    }

    /** Sets an attribute on this span via the recorder. */
    override fun setAttribute(
        key: String,
        value: Any?,
    ) {
        inProgressSpan.attributes[key] = value
        recorder.setAttribute(handle, key, value)
    }

    /** Adds a timestamped event with the given [name] to this span via the recorder. */
    override fun addEvent(name: String) {
        inProgressSpan.attributes[name] = null
        recorder.addEvent(handle, name)
    }

    /** Returns a [NetworkTraceContext] wrapping the recorder's context, or [NoOpNetworkTraceContext] if unavailable. */
    override fun getNetworkTraceContext(): NetworkTraceContext {
        val tags = recorder.getNetworkTraceContexts(handle)
        inProgressSpan.requestTags = tags
        return if (tags.isNotEmpty()) {
            object : NetworkTraceContext {
                override val requestTags: Map<String, String> = tags
            }
        } else {
            NoOpNetworkTraceContext
        }
    }

    /**
     * Ends this span with the given [status] and optional [error].
     *
     * Only the first call takes effect: the elapsed duration is computed from [timeSource],
     * stored in [duration], and forwarded to the recorder. Subsequent calls are no-ops.
     */
    override fun end(
        status: SpanStatus,
        error: Throwable?,
    ): CompletedSpan {
        val elapsed =
            synchronized(lock) {
                completedSpan?.let { return it } ?: run {
                    startMark.elapsedNow().also {
                        duration = it
                        completedSpan =
                            inProgressSpan.toCompletedSpan(
                                duration = it,
                                children =
                                    childSpans.map { child ->
                                        // TODO: Do we want to end dangling child spans?
                                        child.completedSpan ?: child.end(status, error)
                                    },
                            )
                    }
                }
            }
        recorder.endSpan(handle, status, elapsed, error)

        return completedSpan!!
    }
}

internal data class InProgressSpan(
    val name: String,
    val spanId: String,
    val traceId: String,
    val startMark: TimeMark,
    val attributes: MutableMap<String, Any?> = mutableMapOf(),
    var requestTags: Map<String, String> = emptyMap(),
    val parent: Span? = null,
)

public data class CompletedSpan(
    val name: String,
    val spanId: String,
    val traceId: String,
    val startMark: TimeMark,
    val duration: Duration,
    val attributes: Map<String, Any?> = emptyMap(),
    val requestTags: Map<String, String> = emptyMap(),
    val children: List<CompletedSpan> = emptyList(),
    val parent: Span? = null,
)

internal fun InProgressSpan.toCompletedSpan(
    duration: Duration,
    children: List<CompletedSpan> = emptyList(),
): CompletedSpan =
    CompletedSpan(
        name = name,
        spanId = spanId,
        traceId = traceId,
        startMark = startMark,
        duration = duration,
        attributes = attributes,
        requestTags = requestTags,
        children = children,
        parent = parent,
    )
