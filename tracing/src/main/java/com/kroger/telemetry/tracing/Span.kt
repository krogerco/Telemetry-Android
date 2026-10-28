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

import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/**
 * Represents an active span in a trace.
 *
 * A span tracks a single unit of work within a [Trace]. Spans form a tree: each span
 * may have a [parent] and can create children via [startChildSpan]. All spans in the
 * same trace share a common [traceId], while each span has its own unique [spanId].
 *
 * A span is mutable while active — callers can attach [attributes][setAttribute] and
 * [events][addEvent]. Call [end] to finalize the span and record its [duration].
 */
public interface Span {
    /** Identifier shared by all spans in the same trace. */
    public val traceId: String

    /** Unique identifier for this individual span. */
    public val spanId: String

    /** Human-readable name describing the operation this span represents. */
    public val name: String

    /** The parent span, or `null` if this is a root span. */
    public val parent: Span?

    /** The elapsed duration of this span, or `null` if [end] has not yet been called. */
    public val duration: Duration?

    /**
     * Creates and starts a new child span with the given [name] and optional [attributes].
     * The child inherits this span's [traceId].
     */
    public fun startChildSpan(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
    ): Span

    /** Attaches a key-value attribute to this span. */
    public fun setAttribute(
        key: String,
        value: Any?,
    )

    /** Records a timestamped event with the given [name] on this span. */
    public fun addEvent(name: String)

    /**
     * Ends this span with the given [status] and optional [error].
     * After this call, [duration] will be set. Implementations should treat
     * duplicate calls as no-ops.
     */
    public fun end(
        status: SpanStatus = SpanStatus.Success,
        error: Throwable? = null,
    ): CompletedSpan

    /**
     * Returns a [NetworkTraceContext] for distributed trace propagation.
     *
     * If the underlying recorder does not support propagation, returns
     * [NoOpNetworkTraceContext].
     */
    public fun getNetworkTraceContext(): NetworkTraceContext
}

/**
 * Executes [block] as a child span of this span, automatically ending the child when
 * the block completes.
 *
 * - If [block] returns normally, the child span ends with [SpanStatus.Success].
 * - If [block] throws a [CancellationException], the child ends with [SpanStatus.Cancelled]
 *   and the exception is rethrown.
 * - If [block] throws any other exception, the child ends with [SpanStatus.Error] and
 *   the exception is rethrown.
 *
 * @param name The name for the child span.
 * @param attributes Optional attributes to attach to the child span at creation.
 * @param block The suspending work to execute. Receives the child [Span] as its parameter.
 * @return The result of [block].
 */
public suspend fun <T> Span.step(
    name: String,
    attributes: Map<String, Any?> = emptyMap(),
    block: suspend (Span) -> T,
): T {
    val child = startChildSpan(name, attributes)
    return try {
        block(child).also { child.end(SpanStatus.Success) }
    } catch (e: CancellationException) {
        child.end(SpanStatus.Cancelled)
        throw e
    } catch (e: Throwable) {
        child.end(SpanStatus.Error, e)
        throw e
    }
}

/**
 * Sets prefixed attributes on this span and returns a [NetworkTraceContext] for
 * distributed trace propagation.
 *
 * Each entry in [attributes] is set on the span with the key prefixed by [name] using
 * dot notation (e.g., `"network.url"`). After setting attributes, delegates to
 * [Span.getNetworkTraceContext] to obtain the context.
 *
 * @param name Prefix applied to each attribute key.
 * @param attributes Key-value pairs to set on the span (keys will be prefixed).
 * @return The [NetworkTraceContext], or [NoOpNetworkTraceContext] if unavailable.
 */
public fun Span.createNetworkTraceContext(
    name: String,
    attributes: Map<String, Any?> = emptyMap(),
): NetworkTraceContext {
    attributes.forEach { (key, value) -> setAttribute("$name.$key", value) }
    return getNetworkTraceContext()
}

/** The outcome status of a completed span. */
public enum class SpanStatus {
    /** The operation completed successfully. */
    Success,

    /** The operation failed with an error. */
    Error,

    /** The operation was cancelled before completion. */
    Cancelled,
}
