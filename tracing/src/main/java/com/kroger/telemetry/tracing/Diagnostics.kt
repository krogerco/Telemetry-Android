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

import com.kroger.telemetry.Event
import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.Significance

/**
 * The diagnostics one public operation must emit once it has released the lock. Holds at most one diagnostic per dedup
 * key, so an operation over a huge input can't accumulate unbounded diagnostics.
 */
internal class PendingDiagnostics {
    private var list: ArrayList<Diagnostic>? = null
    val diagnostics: List<Diagnostic>? get() = list
    private var keys: HashSet<DiagnosticKey>? = null

    /** [detail] narrows deduplication within a kind (e.g. which limit) and is never exposed. */
    fun report(
        kind: String,
        message: String,
        source: String? = null,
        throwable: Throwable? = null,
        detail: String? = null,
    ) {
        val diagnostic = Diagnostic(kind, message, source, throwable, detail)
        val seen = keys ?: HashSet<DiagnosticKey>().also { keys = it }
        if (!seen.add(diagnostic.key)) return
        val pending = list ?: ArrayList<Diagnostic>(2).also { list = it }
        pending += diagnostic
    }

    /** Discards everything gathered so far: a start that ends up inert reports only why, and a late call reports nothing. */
    fun clear() {
        list = null
        keys = null
    }
}

/**
 * A diagnostic waiting to be recorded. Keeps the original [throwable] until [toEvent], so the redacted copy is made
 * only if the diagnostic survives deduplication.
 */
internal class Diagnostic(
    private val kind: String,
    private val message: String,
    private val source: String?,
    private val throwable: Throwable?,
    private val detail: String?,
) {
    val key: DiagnosticKey get() = DiagnosticKey(source, kind, detail, throwable?.javaClass?.name)

    fun toEvent(): Event {
        // A redacted copy, made only for diagnostics that are actually recorded: the original's message or causes
        // could carry data the library mustn't export, such as an unsanitized URL or a credential.
        val throwable = throwable?.let(::RedactedThrowable)
        val diagnostic = TracingDiagnostic(kind, message, source, throwable)
        // Failure only for real exceptions: relays turn every Failure into a crash report.
        val facets =
            if (throwable == null) {
                listOf(diagnostic, Significance.WARNING)
            } else {
                listOf(diagnostic, Significance.WARNING, Failure(message, throwable))
            }
        return TracingEvent("Tracing diagnostic: $kind", facets)
    }
}

/** What identifies a diagnostic for deduplication, per [PendingDiagnostics] and per tracer. */
internal data class DiagnosticKey(
    val source: String?,
    val kind: String,
    val detail: String?,
    val exceptionClass: String?,
)
