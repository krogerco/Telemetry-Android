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

import com.kroger.telemetry.tracing.NoOpTraceRecorder
import com.kroger.telemetry.tracing.SpanImpl
import com.kroger.telemetry.tracing.SpanStatus
import com.kroger.telemetry.tracing.NoOpNetworkTraceContext
import com.kroger.telemetry.tracing.createNetworkTraceContext
import com.kroger.telemetry.tracing.step
import com.kroger.telemetry.util.FakeTraceRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

internal class TraceTest {

    // --- RecorderSpan ---

    @Test
    fun `GIVEN recorder span WHEN setAttribute called THEN delegates to recorder with correct handle`() {
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "test", null)

        span.setAttribute("key", "value")

        assertEquals(1, recorder.setAttributeCalls.size)
        assertSame(handle, recorder.setAttributeCalls[0].handle)
        assertEquals("key", recorder.setAttributeCalls[0].key)
        assertEquals("value", recorder.setAttributeCalls[0].value)
    }

    @Test
    fun `GIVEN recorder span WHEN addEvent called THEN delegates to recorder with correct handle`() {
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "test", null)

        span.addEvent("event-name")

        assertEquals(1, recorder.addEventCalls.size)
        assertSame(handle, recorder.addEventCalls[0].handle)
        assertEquals("event-name", recorder.addEventCalls[0].name)
    }

    @Test
    fun `GIVEN recorder span WHEN end called with status and error THEN delegates to recorder`() {
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "test", null)
        val error = RuntimeException("test error")

        span.end(SpanStatus.Error, error)

        assertEquals(1, recorder.endSpanCalls.size)
        assertSame(handle, recorder.endSpanCalls[0].handle)
        assertEquals(SpanStatus.Error, recorder.endSpanCalls[0].status)
        assertSame(error, recorder.endSpanCalls[0].error)
    }

    @Test
    fun `GIVEN recorder span WHEN end called twice THEN recorder only receives one end call`() {
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "test", null)

        span.end()
        span.end()

        assertEquals(1, recorder.endSpanCalls.size)
    }

    @Test
    fun `GIVEN recorder span WHEN end called twice THEN duration is preserved from first call`() {
        val timeSource = TestTimeSource()
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, timeSource, "test-trace-id", "test", null)

        timeSource += 100.milliseconds
        span.end()
        timeSource += 200.milliseconds
        span.end()

        assertEquals(100.milliseconds, span.duration)
    }

    @Test
    fun `GIVEN recorder span WHEN end called with defaults THEN uses Success and null error`() {
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "test", null)

        span.end()

        assertEquals(SpanStatus.Success, recorder.endSpanCalls[0].status)
        assertNull(recorder.endSpanCalls[0].error)
    }

    @Test
    fun `GIVEN recorder span WHEN startChildSpan called THEN creates child using recorder startChildSpan`() {
        val recorder = FakeTraceRecorder()
        val parentHandle = recorder.startSpan("parent")
        val span = SpanImpl(parentHandle, recorder, TestTimeSource(), "test-trace-id", "parent", null)
        val attributes = mapOf("key" to "value")

        span.startChildSpan("child", attributes)

        val childCall = recorder.startSpanCalls[1]
        assertSame(parentHandle, childCall.parent)
        assertEquals("child", childCall.name)
        assertEquals(attributes, childCall.attributes)
    }

    @Test
    fun `GIVEN recorder span WHEN startChildSpan called THEN child operates on its own handle`() {
        val recorder = FakeTraceRecorder()
        val parentHandle = recorder.startSpan("parent")
        val span = SpanImpl(parentHandle, recorder, TestTimeSource(), "test-trace-id", "parent", null)

        val child = span.startChildSpan("child")
        child.setAttribute("childKey", "childValue")

        val childHandle = recorder.startSpanCalls[1].handle
        assertSame(childHandle, recorder.setAttributeCalls[0].handle)
    }

    @Test
    fun `GIVEN recorder span WHEN createNetworkTraceContext called THEN sets prefixed attributes and returns context`() {
        val recorder = FakeTraceRecorder()
        recorder.networkContext = "trace-id-123"
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "test", null)

        val context = span.createNetworkTraceContext(
            "network",
            mapOf("url" to "https://example.com", "method" to "GET"),
        )

        assertEquals(mapOf("fake" to "trace-id-123"), context.requestTags)
        val attrKeys = recorder.setAttributeCalls.map { it.key }
        assertEquals(listOf("network.url", "network.method"), attrKeys)
        assertEquals("https://example.com", recorder.setAttributeCalls[0].value)
        assertEquals("GET", recorder.setAttributeCalls[1].value)
    }

    @Test
    fun `GIVEN recorder with null context WHEN createNetworkTraceContext called THEN returns NoOpNetworkTraceContext`() {
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "test", null)

        val context = span.createNetworkTraceContext("network")

        assertSame(NoOpNetworkTraceContext, context)
    }

    // --- NoOpTraceRecorder ---

    @Test
    fun `GIVEN NoOpTraceRecorder WHEN all operations called THEN no exceptions thrown and context is null`() {
        val handle = NoOpTraceRecorder.startSpan("test", mapOf("k" to "v"))
        val childHandle = NoOpTraceRecorder.startSpan("child", parent = handle)
        NoOpTraceRecorder.setAttribute(handle, "key", "value")
        NoOpTraceRecorder.addEvent(handle, "event")
        NoOpTraceRecorder.endSpan(handle, SpanStatus.Success, kotlin.time.Duration.ZERO)

        assertNull(NoOpTraceRecorder.getNetworkTraceContext(childHandle))
    }

    // --- traceStep ---

    @Test
    fun `GIVEN span WHEN traceStep block succeeds THEN returns result and ends child with Success`() =
        runTest {
            val recorder = FakeTraceRecorder()
            val handle = recorder.startSpan("parent")
            val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "parent", null)

            val result = span.step("step") { 42 }

            assertEquals(42, result)
            assertEquals(1, recorder.endSpanCalls.size)
            assertEquals(SpanStatus.Success, recorder.endSpanCalls[0].status)
            assertNull(recorder.endSpanCalls[0].error)
        }

    @Test
    fun `GIVEN span WHEN traceStep block throws THEN ends child with Error and rethrows`() =
        runTest {
            val recorder = FakeTraceRecorder()
            val handle = recorder.startSpan("parent")
            val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "parent", null)
            val exception = RuntimeException("test error")

            val thrown =
                assertFailsWith<RuntimeException> {
                    span.step("step") { throw exception }
                }

            assertSame(exception, thrown)
            assertEquals(1, recorder.endSpanCalls.size)
            assertEquals(SpanStatus.Error, recorder.endSpanCalls[0].status)
            assertSame(exception, recorder.endSpanCalls[0].error)
        }

    @Test
    fun `GIVEN span WHEN traceStep is cancelled THEN ends child with Cancelled and rethrows`() =
        runTest {
            val recorder = FakeTraceRecorder()
            val handle = recorder.startSpan("parent")
            val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "parent", null)

            assertFailsWith<CancellationException> {
                span.step("step") { throw CancellationException("cancelled") }
            }

            assertEquals(1, recorder.endSpanCalls.size)
            assertEquals(SpanStatus.Cancelled, recorder.endSpanCalls[0].status)
        }

    @Test
    fun `GIVEN span WHEN traceStep called THEN block receives child span`() =
        runTest {
            val recorder = FakeTraceRecorder()
            val handle = recorder.startSpan("parent")
            val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "parent", null)

            span.step("step") { childSpan ->
                childSpan.setAttribute("from-block", true)
            }

            assertEquals(1, recorder.setAttributeCalls.size)
            val childHandle = recorder.startSpanCalls[1].handle
            assertSame(childHandle, recorder.setAttributeCalls[0].handle)
        }

    @Test
    fun `GIVEN span WHEN traceStep called with attributes THEN child span has attributes`() =
        runTest {
            val recorder = FakeTraceRecorder()
            val handle = recorder.startSpan("parent")
            val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "parent", null)
            val attrs = mapOf<String, Any?>("retryCount" to 3)

            span.step("step", attrs) { }

            assertEquals("step", recorder.startSpanCalls[1].name)
            assertEquals(attrs, recorder.startSpanCalls[1].attributes)
        }

    // --- spanId ---

    @Test
    fun `GIVEN recorder span WHEN created THEN spanId is non-empty`() {
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "test", null)

        assertTrue(span.spanId.isNotEmpty())
    }

    // --- Span integration ---

    @Test
    fun `GIVEN recorder span WHEN end called THEN duration is set`() {
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("test")
        val span = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "test", null)

        assertNull(span.duration)
        span.end()
        assertNotNull(span.duration)
    }

    @Test
    fun `GIVEN recorder span WHEN startChildSpan called THEN child has correct parent and name`() {
        val recorder = FakeTraceRecorder()
        val handle = recorder.startSpan("parent")
        val parentSpan = SpanImpl(handle, recorder, TestTimeSource(), "test-trace-id", "parent", null)

        val child = parentSpan.startChildSpan("child")

        assertSame(parentSpan, child.parent)
        assertEquals("child", child.name)
    }
}
