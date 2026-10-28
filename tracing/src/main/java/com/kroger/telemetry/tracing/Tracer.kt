package com.kroger.telemetry.tracing

import com.kroger.telemetry.Telemeter
import kotlin.time.TimeSource

public interface Tracer {

    /** The [TraceRecorder] used to record span lifecycle events. Use [CompositeTraceRecorder] if you have multiple backends */
    public val traceRecorder: TraceRecorder

    /** The [TimeSource] used to measure span durations. */
    public val timeSource: TimeSource

    /**
     * Starts a new [Trace] with the given [name] and optional [attributes].
     *
     * Creates a root span via the [traceRecorder] and wraps it in a [Trace] that
     * owns a unique trace ID. The caller is responsible for ending the trace
     * (via [Trace.end]) when the traced operation completes.
     *
     * @param name Human-readable name describing the operation being traced.
     * @param attributes Initial key-value attributes to attach to the root span.
     * @return A new [Trace] representing the started operation.
     */
    public fun trace(
        name: String,
        attributes: Map<String, Any?> = emptyMap(),
    ): Trace

    public companion object {
        /**
         * Creates a [Telemeter] that adds tracing capabilities to an existing [Telemeter].
         *
         * @param telemeter The base [Telemeter] to extend with tracing. Could be a StandardTelemeter.
         * @param traceRecorder The [TraceRecorder] used to record span lifecycle events.
         * @param timeSource The [TimeSource] used to measure span durations.
         */
        public fun build(
            traceRecorder: TraceRecorder,
            timeSource: TimeSource,
        ): Tracer =
            StandardTracer(
                traceRecorder = traceRecorder,
                timeSource = timeSource,
            )
    }
}
