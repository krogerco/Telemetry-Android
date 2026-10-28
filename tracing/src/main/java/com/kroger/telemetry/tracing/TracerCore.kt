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

import com.kroger.telemetry.Event
import com.kroger.telemetry.Telemeter
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.SANITIZER_THREW
import java.util.concurrent.ConcurrentHashMap

/**
 * The tracer's configuration, shared by every trace it starts, plus the tracer-wide [reported] set that deduplicates
 * diagnostics. It never references a trace, so an abandoned trace is collectable along with its spans.
 */
internal class TracerCore(
    private val telemeter: Telemeter?,
    /** Unchecked cast at build: a listener only ever receives state it returned itself. */
    val listeners: List<SpanListener<Any>>,
    private val sanitizer: UrlSanitizer,
    val clock: TraceClock,
) {
    /**
     * (source, kind, detail, exception class) of every diagnostic already recorded. Bounded: every part of a key is a
     * class name, a [Limit] name or a kind constant, never caller-supplied text.
     */
    private val reported = ConcurrentHashMap.newKeySet<DiagnosticKey>()

    /** Creates the trace's own [TraceState] and starts its root. */
    fun startTrace(
        name: String,
        attributes: Map<String, Any?>,
    ): Span {
        val state = TraceState(Ids.newTraceId(), this, clock.nowEpochNanos(), clock.markNow())
        return state.start(parent = null, name, attributes)
    }

    /**
     * Runs the configured sanitizer, then the length cap. Contains exceptions from the sanitizer; no lock may be held.
     *
     * If the sanitizer throws, the URL is omitted (stored as ""). Falling back to a weaker sanitizer would undo
     * whatever stricter rule the custom one enforces, such as redacting identifiers in paths.
     */
    fun sanitize(
        url: String,
        pending: PendingDiagnostics,
    ): String =
        try {
            // Capping inside the try also catches a null returned by a Java sanitizer.
            sanitizer.sanitize(url).capTo(TraceLimits.MAX_URL_LENGTH, Limit.URL, "URL", pending)
        } catch (t: Exception) {
            // Class names only: the exception's message could contain the raw URL.
            pending.report(
                SANITIZER_THREW,
                "URL sanitizer threw ${t.javaClass.name}; the URL was omitted",
                source = sanitizer.javaClass.name,
            )
            ""
        }

    /**
     * Records pending diagnostics, then the [completion] event if the root ended, so a diagnostic raised while ending
     * reaches the telemeter before the trace. Contains exceptions from the telemeter; no lock may be held.
     */
    fun emit(
        pending: PendingDiagnostics,
        completion: TraceFacet? = null,
    ) {
        val telemeter = telemeter ?: return
        pending.diagnostics?.forEach { diagnostic ->
            if (reported.add(diagnostic.key)) recordSafely(telemeter, diagnostic.toEvent())
        }
        completion?.let { trace ->
            recordSafely(telemeter, TracingEvent("Trace: ${trace.root.name}", listOf(trace, Significance.VERBOSE)))
        }
    }

    /** A telemeter that throws an exception must not stop the rest from being recorded. An [Error] still propagates. */
    private fun recordSafely(
        telemeter: Telemeter,
        event: Event,
    ) {
        try {
            telemeter.record(event)
        } catch (_: Exception) {
        }
    }
}
