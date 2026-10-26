/**
 * MIT License
 *
 * Copyright (c) 2021 The Kroger Co. All rights reserved.
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

package com.kroger.telemetry

import com.kroger.telemetry.tracing.CompositeTraceRecorder
import com.kroger.telemetry.tracing.SpanStatus
import com.kroger.telemetry.util.FakeTraceRecorder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

internal class CompositeTraceRecorderTest {
    @Test
    fun `GIVEN composite with multiple recorders WHEN startTrace called THEN delegates to all recorders`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1")
        val recorder2 = FakeTraceRecorder(recorderId = "r2")
        val composite = CompositeTraceRecorder(setOf(recorder1, recorder2))

        composite.startSpan("my-trace", mapOf("key" to "value"))

        assertEquals(1, recorder1.startSpanCalls.size)
        assertEquals("my-trace", recorder1.startSpanCalls[0].name)
        assertEquals(mapOf("key" to "value"), recorder1.startSpanCalls[0].attributes)

        assertEquals(1, recorder2.startSpanCalls.size)
        assertEquals("my-trace", recorder2.startSpanCalls[0].name)
        assertEquals(mapOf("key" to "value"), recorder2.startSpanCalls[0].attributes)
    }

    @Test
    fun `GIVEN composite WHEN startChildSpan called THEN prefixes attributes with step name`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        composite.startSpan("step1", mapOf("attr" to "val"), parent = root)

        val childCall = recorder.startSpanCalls[1]
        assertEquals("step1", childCall.name)
        assertEquals(mapOf("step1.attr" to "val"), childCall.attributes)
    }

    @Test
    fun `GIVEN composite with root trace WHEN setAttribute called THEN uses original key`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        composite.setAttribute(root, "my_key", "my_value")

        assertEquals("my_key", recorder.setAttributeCalls[0].key)
        assertEquals("my_value", recorder.setAttributeCalls[0].value)
    }

    @Test
    fun `GIVEN composite with child span WHEN setAttribute called THEN prefixes key with step name`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        val child = composite.startSpan("step1", parent = root)
        composite.setAttribute(child, "my_key", "my_value")

        assertEquals("step1.my_key", recorder.setAttributeCalls[0].key)
        assertEquals("my_value", recorder.setAttributeCalls[0].value)
    }

    @Test
    fun `GIVEN composite with root trace WHEN addEvent called THEN uses original name`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        composite.addEvent(root, "checkpoint")

        assertEquals("checkpoint", recorder.addEventCalls[0].name)
    }

    @Test
    fun `GIVEN composite with child span WHEN addEvent called THEN prefixes name with step name`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        val child = composite.startSpan("step1", parent = root)
        composite.addEvent(child, "checkpoint")

        assertEquals("step1.checkpoint", recorder.addEventCalls[0].name)
    }

    @Test
    fun `GIVEN composite WHEN root trace ended THEN reports status total_duration_ms and step_duration_ms_sum`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        composite.endSpan(root, SpanStatus.Success, 100.milliseconds)

        val attrs = recorder.setAttributeCalls.associate { it.key to it.value }
        assertEquals("success", attrs["status"])
        assertEquals(100L, attrs["total_duration_ms"])
        assertEquals(0L, attrs["step_duration_ms_sum"])

        assertEquals(1, recorder.endSpanCalls.size)
        assertEquals(SpanStatus.Success, recorder.endSpanCalls[0].status)
    }

    @Test
    fun `GIVEN composite WHEN child trace ended THEN reports prefixed status and duration_ms`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        val child = composite.startSpan("step1", parent = root)
        val error = RuntimeException("fail")
        composite.endSpan(child, SpanStatus.Error, 50.milliseconds, error)

        val attrs = recorder.setAttributeCalls.associate { it.key to it.value }
        assertEquals("error", attrs["step1.status"])
        assertEquals(50L, attrs["step1.duration_ms"])

        assertEquals(1, recorder.endSpanCalls.size)
        assertEquals(SpanStatus.Error, recorder.endSpanCalls[0].status)
        assertEquals(error, recorder.endSpanCalls[0].error)
    }

    @Test
    fun `GIVEN composite WHEN child without sub-steps ended THEN does not report step_duration_ms_sum`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        val child = composite.startSpan("step1", parent = root)
        composite.endSpan(child, SpanStatus.Success, 50.milliseconds)

        val attrKeys = recorder.setAttributeCalls.map { it.key }
        assertFalse("step1.step_duration_ms_sum" in attrKeys)
    }

    @Test
    fun `GIVEN composite WHEN child with sub-steps ended THEN reports step_duration_ms_sum`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        val child = composite.startSpan("step1", parent = root)
        val grandchild = composite.startSpan("substep", parent = child)
        composite.endSpan(grandchild, SpanStatus.Success, 25.milliseconds)
        composite.endSpan(child, SpanStatus.Success, 30.milliseconds)

        val stepSumCalls =
            recorder.setAttributeCalls.filter { it.key == "step1.step_duration_ms_sum" }
        assertEquals(1, stepSumCalls.size)
        assertEquals(25L, stepSumCalls[0].value)
    }

    @Test
    fun `GIVEN composite WHEN multiple children ended THEN root step_duration_ms_sum accumulates child durations`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")

        val child1 = composite.startSpan("step1", parent = root)
        composite.endSpan(child1, SpanStatus.Success, 30.milliseconds)

        val child2 = composite.startSpan("step2", parent = root)
        composite.endSpan(child2, SpanStatus.Success, 20.milliseconds)

        composite.endSpan(root, SpanStatus.Success, 60.milliseconds)

        val stepSumCalls =
            recorder.setAttributeCalls.filter { it.key == "step_duration_ms_sum" }
        assertEquals(1, stepSumCalls.size)
        assertEquals(50L, stepSumCalls[0].value)
    }

    @Test
    fun `GIVEN composite WHEN endTrace with Error status THEN reports error status string`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        composite.endSpan(root, SpanStatus.Error, 10.milliseconds, RuntimeException("fail"))

        val statusCall = recorder.setAttributeCalls.first { it.key == "status" }
        assertEquals("error", statusCall.value)
    }

    @Test
    fun `GIVEN composite WHEN endTrace with Cancelled status THEN reports cancelled status string`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        composite.endSpan(root, SpanStatus.Cancelled, 10.milliseconds)

        val statusCall = recorder.setAttributeCalls.first { it.key == "status" }
        assertEquals("cancelled", statusCall.value)
    }

    @Test
    fun `GIVEN composite with recorder returning network context WHEN getNetworkTraceContext called THEN returns context`() {
        val recorder = FakeTraceRecorder().apply { networkContext = "trace-123" }
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")
        val context = composite.getNetworkTraceContext(root)

        assertEquals("trace-123", context)
    }

    @Test
    fun `GIVEN composite with all null contexts WHEN getNetworkTraceContext called THEN returns null`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1")
        val recorder2 = FakeTraceRecorder(recorderId = "r2")
        val composite = CompositeTraceRecorder(setOf(recorder1, recorder2))

        val root = composite.startSpan("root")
        val context = composite.getNetworkTraceContext(root)

        assertNull(context)
    }

    @Test
    fun `GIVEN composite with multiple recorders WHEN endTrace called THEN delegates to all recorders`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1")
        val recorder2 = FakeTraceRecorder(recorderId = "r2")
        val composite = CompositeTraceRecorder(setOf(recorder1, recorder2))

        val root = composite.startSpan("root")
        composite.endSpan(root, SpanStatus.Success, 50.milliseconds)

        assertEquals(1, recorder1.endSpanCalls.size)
        assertEquals(SpanStatus.Success, recorder1.endSpanCalls[0].status)

        assertEquals(1, recorder2.endSpanCalls.size)
        assertEquals(SpanStatus.Success, recorder2.endSpanCalls[0].status)
    }

    @Test
    fun `GIVEN composite WHEN root total_duration_ms reported THEN reflects passed duration`() {
        val recorder = FakeTraceRecorder()
        val composite = CompositeTraceRecorder(setOf(recorder))

        val root = composite.startSpan("root")

        val child = composite.startSpan("step1", parent = root)
        composite.endSpan(child, SpanStatus.Success, 30.milliseconds)

        composite.endSpan(root, SpanStatus.Success, 100.milliseconds)

        val totalDuration =
            recorder.setAttributeCalls.first { it.key == "total_duration_ms" }
        assertEquals(100L, totalDuration.value)

        val stepSum =
            recorder.setAttributeCalls.filter { it.key == "step_duration_ms_sum" }
        assertEquals(1, stepSum.size)
        assertEquals(30L, stepSum[0].value)
    }

    @Test
    fun `GIVEN composite with multiple recorders WHEN getNetworkTraceContexts called THEN collects from all delegates`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1").apply { networkContext = "tag-1" }
        val recorder2 = FakeTraceRecorder(recorderId = "r2").apply { networkContext = "tag-2" }
        val composite = CompositeTraceRecorder(setOf(recorder1, recorder2))

        val root = composite.startSpan("root")
        val contexts = composite.getNetworkTraceContexts(root)

        assertEquals(mapOf("r1" to "tag-1", "r2" to "tag-2"), contexts)
    }

    @Test
    fun `GIVEN composite with some null contexts WHEN getNetworkTraceContexts called THEN only includes non-null`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1").apply { networkContext = "tag-1" }
        val recorder2 = FakeTraceRecorder(recorderId = "r2") // networkContext defaults to null
        val composite = CompositeTraceRecorder(setOf(recorder1, recorder2))

        val root = composite.startSpan("root")
        val contexts = composite.getNetworkTraceContexts(root)

        assertEquals(mapOf("r1" to "tag-1"), contexts)
    }

    @Test
    fun `GIVEN composite WHEN getAllRequestTagHeaders called THEN returns headers keyed by recorderId`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1").apply { fakeRequestTagHeaderName = "x-r1" }
        val recorder2 = FakeTraceRecorder(recorderId = "r2").apply { fakeRequestTagHeaderName = "x-r2" }
        val recorder3 = FakeTraceRecorder(recorderId = "r3") // empty header, should be excluded
        val composite = CompositeTraceRecorder(setOf(recorder1, recorder2, recorder3))

        val headers = composite.getAllRequestTagHeaders()

        assertEquals(mapOf("r1" to "x-r1", "r2" to "x-r2"), headers)
    }

    @Test
    fun `GIVEN composite WHEN startAllNetworkRequestTimings called THEN routes to correct recorders`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1")
        val recorder2 = FakeTraceRecorder(recorderId = "r2")
        val composite = CompositeTraceRecorder(setOf(recorder1, recorder2))

        composite.startAllNetworkRequestTimings(
            requestTags = mapOf("r1" to "tag-1", "r2" to "tag-2"),
            url = "https://example.com",
        )

        assertEquals(1, recorder1.startNetworkTimingCalls.size)
        assertEquals("tag-1", recorder1.startNetworkTimingCalls[0].requestTag)
        assertEquals("https://example.com", recorder1.startNetworkTimingCalls[0].url)

        assertEquals(1, recorder2.startNetworkTimingCalls.size)
        assertEquals("tag-2", recorder2.startNetworkTimingCalls[0].requestTag)
        assertEquals("https://example.com", recorder2.startNetworkTimingCalls[0].url)
    }

    @Test
    fun `GIVEN composite WHEN startAllNetworkRequestTimings called with unknown recorderId THEN skips it`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1")
        val composite = CompositeTraceRecorder(setOf(recorder1))

        val scope =
            composite.startAllNetworkRequestTimings(
                requestTags = mapOf("r1" to "tag-1", "unknown" to "tag-x"),
                url = "https://example.com",
            )

        assertEquals(1, recorder1.startNetworkTimingCalls.size)
    }

    @Test
    fun `GIVEN composite with no matching recorders WHEN startAllNetworkRequestTimings called THEN returns no-op scope`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1")
        val composite = CompositeTraceRecorder(setOf(recorder1))

        val scope =
            composite.startAllNetworkRequestTimings(
                requestTags = mapOf("unknown" to "tag-x"),
                url = "https://example.com",
            )

        // Should not throw when used
        scope.reportResponse(200, "OK")
        scope.finish()
        assertTrue(recorder1.startNetworkTimingCalls.isEmpty())
    }

    @Test
    fun `GIVEN recorders with unique ids WHEN CompositeTraceRecorder created THEN no exception thrown`() {
        val recorder1 = FakeTraceRecorder(recorderId = "r1")
        val recorder2 = FakeTraceRecorder(recorderId = "r2")

        CompositeTraceRecorder(setOf(recorder1, recorder2))
    }

    @Test
    fun `GIVEN recorders with duplicate ids WHEN CompositeTraceRecorder created THEN throws IllegalArgumentException`() {
        val recorder1 = FakeTraceRecorder(recorderId = "duplicate")
        val recorder2 = FakeTraceRecorder(recorderId = "duplicate")

        val exception =
            assertFailsWith<IllegalArgumentException> {
                CompositeTraceRecorder(setOf(recorder1, recorder2))
            }

        assertTrue(exception.message!!.contains("duplicate"))
    }

    @Test
    fun `GIVEN single recorder WHEN CompositeTraceRecorder created THEN no exception thrown`() {
        val recorder = FakeTraceRecorder()

        CompositeTraceRecorder(setOf(recorder))
    }

    @Test
    fun `GIVEN empty recorders WHEN CompositeTraceRecorder created THEN no exception thrown`() {
        CompositeTraceRecorder(emptySet())
    }

    @Test
    fun `GIVEN nested composite WHEN getAllRequestTagHeaders called THEN returns leaf recorder headers`() {
        val leaf1 = FakeTraceRecorder(recorderId = "r1").apply { fakeRequestTagHeaderName = "x-r1" }
        val leaf2 = FakeTraceRecorder(recorderId = "r2").apply { fakeRequestTagHeaderName = "x-r2" }
        val inner = CompositeTraceRecorder(setOf(leaf1))
        val outer = CompositeTraceRecorder(setOf(inner, leaf2))

        val headers = outer.getAllRequestTagHeaders()

        assertEquals(mapOf("r1" to "x-r1", "r2" to "x-r2"), headers)
    }

    @Test
    fun `GIVEN nested composite WHEN startAllNetworkRequestTimings called THEN routes to leaf recorders`() {
        val leaf1 = FakeTraceRecorder(recorderId = "r1")
        val leaf2 = FakeTraceRecorder(recorderId = "r2")
        val inner = CompositeTraceRecorder(setOf(leaf1))
        val outer = CompositeTraceRecorder(setOf(inner, leaf2))

        outer.startAllNetworkRequestTimings(
            requestTags = mapOf("r1" to "tag-1", "r2" to "tag-2"),
            url = "https://example.com",
        )

        assertEquals(1, leaf1.startNetworkTimingCalls.size)
        assertEquals("tag-1", leaf1.startNetworkTimingCalls[0].requestTag)
        assertEquals("https://example.com", leaf1.startNetworkTimingCalls[0].url)

        assertEquals(1, leaf2.startNetworkTimingCalls.size)
        assertEquals("tag-2", leaf2.startNetworkTimingCalls[0].requestTag)
        assertEquals("https://example.com", leaf2.startNetworkTimingCalls[0].url)
    }
}
