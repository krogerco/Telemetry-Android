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

import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.LISTENER_THREW
import java.util.Locale

/*
 * The realtime tier's fan-out. Every call runs outside the trace lock and is isolated per listener: a throw is
 * reported once per (listener, exception class) and never reaches other listeners or the caller.
 *
 * A span's listener states are an array parallel to TracerCore.listeners: the state each listener returned from
 * onStart, or null where it returned null or threw.
 */

/** Calls every listener's [SpanListener.onStart] in list order and returns their states. */
internal fun TracerCore.dispatchStart(
    view: SpanStart,
    parentStates: Array<Any?>?,
    pending: PendingDiagnostics,
): Array<Any?> =
    Array(listeners.size) { i ->
        val listener = listeners[i]
        try {
            listener.onStart(view, parentStates?.get(i))
        } catch (t: Exception) {
            pending.listenerThrew(listener, "onStart", t)
            null
        }
    }

/** Calls [SpanListener.onEnd] in list order for every listener that holds state for the span. */
internal fun TracerCore.dispatchEnd(
    states: Array<Any?>,
    data: SpanData,
    pending: PendingDiagnostics,
) {
    for (i in states.indices) {
        val state = states[i] ?: continue
        val listener = listeners[i]
        try {
            listener.onEnd(state, data)
        } catch (t: Exception) {
            pending.listenerThrew(listener, "onEnd", t)
        }
    }
}

/**
 * Merges [SpanListener.propagationHeaders] from every listener that holds state, in list order. Names compare
 * case-insensitively and the first listener wins. Null or empty names and values are skipped. A listener whose call,
 * or the copying of whose result, throws loses only its own contribution.
 */
internal fun TracerCore.collectHeaders(
    states: Array<Any?>,
    pending: PendingDiagnostics,
): Map<String, String> {
    var merged: LinkedHashMap<String, String>? = null
    var seen: HashSet<String>? = null
    for (i in states.indices) {
        val state = states[i] ?: continue
        val listener = listeners[i]
        val contribution =
            try {
                copyHeaders(listener.propagationHeaders(state))
            } catch (t: Exception) {
                pending.listenerThrew(listener, "propagationHeaders", t)
                continue
            }
        for ((name, value) in contribution) {
            val names = seen ?: HashSet<String>().also { seen = it }
            if (names.add(name.lowercase(Locale.ROOT))) {
                val result = merged ?: LinkedHashMap<String, String>().also { merged = it }
                result[name] = value
            }
        }
    }
    return merged ?: emptyMap()
}

/**
 * Copies a listener's headers before merging any of them, so a map that throws partway through contributes nothing.
 */
private fun copyHeaders(headers: Map<*, *>?): List<Pair<String, String>> {
    if (headers.isNullOrEmpty()) return emptyList()
    val copy = ArrayList<Pair<String, String>>(headers.size)
    for ((name, value) in headers) {
        if (name is String && name.isNotEmpty() && value is String && value.isNotEmpty()) copy += name to value
    }
    return copy
}

private fun PendingDiagnostics.listenerThrew(
    listener: SpanListener<*>,
    callback: String,
    throwable: Throwable,
) {
    // Class names only: an exception message could carry URLs or personal data. The recorded throwable is a redacted
    // copy (class name and stack trace; see RedactedThrowable), made when the diagnostic is emitted.
    report(
        LISTENER_THREW,
        "Span listener threw ${throwable.javaClass.name} from $callback",
        source = listener.javaClass.name,
        throwable = throwable,
    )
}
