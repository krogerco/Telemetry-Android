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

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.time.Duration

/**
 * A [TraceRecorder] that fans out span operations to multiple delegate recorders.
 *
 * Each [startSpan] call creates a [SpanHandle] in every delegate recorder and bundles
 * them into a single composite handle. Subsequent operations ([setAttribute], [addEvent],
 * [endSpan]) are forwarded to all delegates.
 *
 * For child spans, attribute keys and event names are prefixed with the step name using
 * dot notation (e.g., `"step1.duration_ms"`). When a child span ends, its duration is
 * reported back to the parent's step-duration sum via a callback, enabling the root span
 * to report both `total_duration_ms` and `step_duration_ms_sum` as attributes.
 *
 * @param recorders The set of [TraceRecorder] delegates to fan out to.
 */
public class CompositeTraceRecorder @Inject constructor(
    private val recorders: Set<@JvmSuppressWildcards TraceRecorder>,
) : TraceRecorder {
    init {
        val recorderIds = recorders.map { it.recorderId }
        require(recorderIds.size == recorderIds.toSet().size) {
            "All TraceRecorder instances must have unique recorderIds. " +
                "Found duplicates: ${recorderIds.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}"
        }
    }

    override val recorderId: String = "composite"

    override fun startSpan(
        name: String,
        attributes: Map<String, Any?>,
        parent: SpanHandle?,
    ): SpanHandle {
        if (parent == null) {
            val recorderList = recorders.toList()
            val handles = recorderList.map { it.startSpan(name, attributes) }
            return CompositeSpanHandle(
                recorders = recorderList,
                handles = handles,
            )
        }

        val composite = requireCompositeHandle(parent)
        val prefixedAttributes = attributes.mapKeys { (key, _) -> buildStepKey(name, key) }
        val childHandles =
            composite.recorders.zip(composite.handles).map { (recorder, parentHandle) ->
                recorder.startSpan(name, prefixedAttributes, parent = parentHandle)
            }
        return CompositeSpanHandle(
            recorders = composite.recorders,
            handles = childHandles,
            stepName = name,
            parentOnComplete = { ms -> composite.stepDurationMsSum.addAndGet(ms) },
        )
    }

    override fun setAttribute(
        span: SpanHandle,
        key: String,
        value: Any?,
    ) {
        val composite = requireCompositeHandle(span)
        val prefixedKey = composite.stepifiedKey(key)
        composite.forEachRecorder { recorder, handle ->
            recorder.setAttribute(handle, prefixedKey, value)
        }
    }

    override fun addEvent(
        span: SpanHandle,
        name: String,
    ) {
        val composite = requireCompositeHandle(span)
        val prefixedName = composite.stepifiedKey(name)
        composite.forEachRecorder { recorder, handle ->
            recorder.addEvent(handle, prefixedName)
        }
    }

    override fun getNetworkTraceContext(span: SpanHandle): String? {
        val composite = requireCompositeHandle(span)
        composite.forEachRecorder { recorder, handle ->
            val tag = recorder.getNetworkTraceContext(handle)
            if (tag != null) return tag
        }
        return null
    }

    override fun getNetworkTraceContexts(span: SpanHandle): Map<String, String> {
        val composite = requireCompositeHandle(span)
        val result = mutableMapOf<String, String>()
        composite.forEachRecorder { recorder, handle ->
            result.putAll(recorder.getNetworkTraceContexts(handle))
        }
        return result
    }

    override fun getAllRequestTagHeaders(): Map<String, String> =
        recorders
            .flatMap { it.getAllRequestTagHeaders().entries }
            .associate { it.key to it.value }

    override fun startAllNetworkRequestTimings(
        requestTags: Map<String, String>,
        url: String,
    ): NetworkRequestTimingScope {
        val scopes = recorders.map { it.startAllNetworkRequestTimings(requestTags, url) }
        return CompositeNetworkRequestTimingScope(scopes)
    }

    override fun endSpan(
        span: SpanHandle,
        status: SpanStatus,
        duration: Duration,
        error: Throwable?,
    ) {
        val composite = requireCompositeHandle(span)
        val durationMs = duration.inWholeMilliseconds

        reportToRecorders(composite, KEY_STATUS, status.name.lowercase())

        if (composite.stepName == null) {
            // Root span: report total_duration_ms and the step aggregate.
            reportToRecorders(composite, KEY_TOTAL_DURATION_MS, durationMs)
            reportToRecorders(
                composite,
                KEY_STEP_DURATION_MS_SUM,
                composite.stepDurationMsSum.get(),
            )
        } else {
            // Child span: report duration_ms. Only emit step_duration_ms_sum when this
            // span itself had sub-steps, to avoid noisy 0-values.
            reportToRecorders(composite, KEY_DURATION_MS, durationMs)
            val childStepSum = composite.stepDurationMsSum.get()
            if (childStepSum > 0L) {
                reportToRecorders(composite, KEY_STEP_DURATION_MS_SUM, childStepSum)
            }
        }

        composite.parentOnComplete?.invoke(durationMs)

        composite.forEachRecorder { recorder, handle ->
            recorder.endSpan(handle, status, duration, error)
        }
    }

    private fun reportToRecorders(
        composite: CompositeSpanHandle,
        key: String,
        value: Any?,
    ) {
        val prefixedKey = composite.stepifiedKey(key)
        composite.forEachRecorder { recorder, handle ->
            recorder.setAttribute(handle, prefixedKey, value)
        }
    }

    private fun requireCompositeHandle(span: SpanHandle): CompositeSpanHandle {
        require(span is CompositeSpanHandle) { "SpanHandle was not created by this CompositeTraceRecorder" }
        return span
    }

    private companion object {
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_STEP_DURATION_MS_SUM = "step_duration_ms_sum"
        const val KEY_STATUS = "status"
        const val KEY_TOTAL_DURATION_MS = "total_duration_ms"
    }
}

/** Builds a dot-separated key from a step name and an attribute/event key. */
private fun buildStepKey(
    stepName: String,
    key: String,
): String = "$stepName.$key"

/**
 * Composite [SpanHandle] that bundles one handle per delegate recorder.
 *
 * @param recorders The ordered list of delegate recorders.
 * @param handles The corresponding handles, one per recorder (same order).
 * @param stepName The step name for this span, or `null` for root spans. Used to
 *   prefix attribute keys and event names.
 * @param parentOnComplete Callback invoked when this span ends, passing its duration
 *   in milliseconds to the parent's [stepDurationMsSum] accumulator.
 */
private class CompositeSpanHandle(
    val recorders: List<TraceRecorder>,
    val handles: List<SpanHandle>,
    val stepName: String? = null,
    val parentOnComplete: ((Long) -> Unit)? = null,
) : SpanHandle {
    /** Accumulated duration (in milliseconds) of all direct child steps. */
    val stepDurationMsSum = AtomicLong(0L)

    /** Prefixes [key] with [stepName] if this is a child span, otherwise returns [key] unchanged. */
    fun stepifiedKey(key: String): String = if (stepName != null) buildStepKey(stepName, key) else key

    /** Iterates over each (recorder, handle) pair and applies [action]. */
    inline fun forEachRecorder(action: (TraceRecorder, SpanHandle) -> Unit) {
        recorders.forEachIndexed { index, recorder -> action(recorder, handles[index]) }
    }
}

private class CompositeNetworkRequestTimingScope(
    private val scopes: List<NetworkRequestTimingScope>,
) : NetworkRequestTimingScope {
    override fun reportResponse(
        statusCode: Int,
        responseMessage: String,
    ) {
        scopes.forEach { it.reportResponse(statusCode, responseMessage) }
    }

    override fun reportError(throwable: Throwable) {
        scopes.forEach { it.reportError(throwable) }
    }

    override fun finish() {
        scopes.forEach { it.finish() }
    }
}
