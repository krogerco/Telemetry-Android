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

import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

/*
 * Inline ABI: these helpers are inlined into consumer bytecode, which then references Tracer.startTrace,
 * Span.startChild, Span.end and applyThrowable (JVM facade SpanHelpersKt). Never rename, move or change the
 * signature of them without a release-notes entry.
 */

/**
 * Runs [block] inside a new trace and always ends the root, including on exceptions and non-local returns.
 *
 * An exception that escapes [block] sets the span's status: a timeout is `Error("timeout")`, any other cancellation is
 * `Cancelled`, and anything else is `Error(<exception class name>)`. This overrides an unset status or an explicit
 * `Ok`, but never an explicit `Error` or `Cancelled`, so a status you set with [Span.setStatus] before throwing is kept.
 *
 * Only thrown exceptions are detected. If [block] catches a failure and returns it as a value (for example a
 * `Result.failure` or an error response), the span ends as `Ok` unless you call [Span.setStatus] yourself.
 */
@ExperimentalTracingApi
public inline fun <T> Tracer.trace(
    name: String,
    attributes: Map<String, Any?> = emptyMap(),
    block: (Span) -> T,
): T {
    val span = startTrace(name, attributes)
    try {
        return block(span)
    } catch (t: Throwable) {
        span.applyThrowable(t)
        throw t
    } finally {
        span.end()
    }
}

/**
 * Runs [block] inside a child span of this span and always ends it. Same status rules as [trace].
 * For parallel children, wrap the `async` calls in `coroutineScope { }` inside the parent's block.
 */
@ExperimentalTracingApi
public inline fun <T> Span.child(
    name: String,
    attributes: Map<String, Any?> = emptyMap(),
    block: (Span) -> T,
): T {
    val span = startChild(name, attributes)
    try {
        return block(span)
    } catch (t: Throwable) {
        span.applyThrowable(t)
        throw t
    } finally {
        span.end()
    }
}

/**
 * Classifies a throwable escaping a helper block. Lives here, not inline, so the rules can change without recompiling
 * callers and callers' bytecode never references coroutine exception classes. Does not throw an exception.
 */
@PublishedApi
@ExperimentalTracingApi
internal fun Span.applyThrowable(throwable: Throwable) {
    if (this !is SpanImpl) return
    when (throwable) {
        is TimeoutCancellationException -> applyThrown(SpanStatus.Error("timeout"), throwable)
        is CancellationException -> applyThrown(SpanStatus.Cancelled, throwable = null)
        // The class name only, never the message, which can carry URLs or personal data.
        else -> applyThrown(SpanStatus.Error(throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name }), throwable)
    }
}
