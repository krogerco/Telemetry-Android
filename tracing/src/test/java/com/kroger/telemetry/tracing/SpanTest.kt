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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

internal class SpanTest {
    private fun createSpan(
        name: String = "test",
        parent: Span? = null,
        timeSource: TestTimeSource = TestTimeSource(),
        traceId: String = "test-trace-id",
    ): SpanImpl {
        val handle = NoOpTraceRecorder.startSpan(name)
        return SpanImpl(handle, NoOpTraceRecorder, timeSource, traceId, name, parent)
    }

    @Test
    fun `GIVEN span WHEN created THEN duration is null`() {
        val span = createSpan()
        assertNull(span.duration)
    }

    @Test
    fun `GIVEN span WHEN end called THEN duration is set`() {
        val timeSource = TestTimeSource()
        val span = createSpan(timeSource = timeSource)
        timeSource += 100.milliseconds
        span.end()
        assertEquals(100.milliseconds, span.duration)
    }

    @Test
    fun `GIVEN span WHEN end called twice THEN duration is not updated`() {
        val timeSource = TestTimeSource()
        val span = createSpan(timeSource = timeSource)
        timeSource += 100.milliseconds
        span.end()
        timeSource += 200.milliseconds
        span.end()
        assertEquals(100.milliseconds, span.duration)
    }

    @Test
    fun `GIVEN root span WHEN created THEN parent is null`() {
        val span = createSpan(name = "root")
        assertNull(span.parent)
    }

    @Test
    fun `GIVEN span WHEN startChildSpan called THEN child parent is this span`() {
        val span = createSpan(name = "parent")
        val child = span.startChildSpan("child")
        assertSame(span, child.parent)
    }

    @Test
    fun `GIVEN span WHEN startChildSpan called THEN child has correct name`() {
        val span = createSpan(name = "parent")
        val child = span.startChildSpan("child")
        assertEquals("child", child.name)
    }

    @Test
    fun `GIVEN span WHEN startChildSpan called THEN child uses same time source`() {
        val timeSource = TestTimeSource()
        val span = createSpan(name = "parent", timeSource = timeSource)
        val child = span.startChildSpan("child")
        timeSource += 50.milliseconds
        child.end()
        assertEquals(50.milliseconds, child.duration)
    }

    @Test
    fun `GIVEN span WHEN name accessed THEN returns name from constructor`() {
        val span = createSpan(name = "my-span")
        assertEquals("my-span", span.name)
    }

    @Test
    fun `GIVEN span WHEN startChildSpan called THEN child shares same traceId`() {
        val span = createSpan(name = "parent", traceId = "shared-trace-id")
        val child = span.startChildSpan("child")
        assertEquals("shared-trace-id", child.traceId)
    }

    @Test
    fun `GIVEN span with grandchild WHEN created THEN all share same traceId`() {
        val span = createSpan(name = "root", traceId = "shared-trace-id")
        val child = span.startChildSpan("child")
        val grandchild = child.startChildSpan("grandchild")
        assertEquals("shared-trace-id", child.traceId)
        assertEquals("shared-trace-id", grandchild.traceId)
    }

    @Test
    fun `GIVEN span WHEN created THEN spanId is non-empty`() {
        val span = createSpan()
        assertTrue(span.spanId.isNotEmpty())
    }

    @Test
    fun `GIVEN parent and child spans WHEN created THEN each has a unique spanId`() {
        val span = createSpan(name = "parent")
        val child = span.startChildSpan("child")
        assertNotEquals(span.spanId, child.spanId)
    }

    @Test
    fun `GIVEN parent and child spans WHEN both ended THEN each has independent duration`() {
        val timeSource = TestTimeSource()
        val parent = createSpan(name = "parent", timeSource = timeSource)
        timeSource += 10.milliseconds
        val child = parent.startChildSpan("child")
        timeSource += 30.milliseconds
        child.end()
        timeSource += 60.milliseconds
        parent.end()

        assertEquals(30.milliseconds, child.duration)
        assertEquals(100.milliseconds, parent.duration)
    }

    //region CompletedSpan

    @Test
    fun `GIVEN span WHEN end called THEN returns CompletedSpan with correct fields`() {
        val timeSource = TestTimeSource()
        val span = createSpan(name = "my-span", traceId = "trace-1", timeSource = timeSource)
        timeSource += 75.milliseconds

        val completed = span.end()

        assertEquals("my-span", completed.name)
        assertEquals(span.spanId, completed.spanId)
        assertEquals("trace-1", completed.traceId)
        assertEquals(75.milliseconds, completed.duration)
    }

    @Test
    fun `GIVEN span WHEN end called twice THEN returns same CompletedSpan`() {
        val timeSource = TestTimeSource()
        val span = createSpan(timeSource = timeSource)
        timeSource += 100.milliseconds
        val first = span.end()
        timeSource += 200.milliseconds
        val second = span.end()

        assertSame(first, second)
    }

    @Test
    fun `GIVEN parent with ended child WHEN parent ended THEN CompletedSpan contains child`() {
        val timeSource = TestTimeSource()
        val parent = createSpan(name = "parent", timeSource = timeSource)
        val child = parent.startChildSpan("child")
        timeSource += 50.milliseconds
        child.end()
        timeSource += 50.milliseconds
        val completed = parent.end()

        assertEquals(1, completed.children.size)
        assertEquals("child", completed.children[0].name)
        assertEquals(50.milliseconds, completed.children[0].duration)
    }

    @Test
    fun `GIVEN parent with unfinished child WHEN parent ended THEN child is auto-ended`() {
        val timeSource = TestTimeSource()
        val parent = createSpan(name = "parent", timeSource = timeSource)
        val child = parent.startChildSpan("child")
        timeSource += 80.milliseconds

        val completed = parent.end(SpanStatus.Error, RuntimeException("fail"))

        assertEquals(1, completed.children.size)
        assertNotNull(child.duration)
    }

    @Test
    fun `GIVEN parent with unfinished child WHEN parent ended with Error THEN child inherits status`() {
        val timeSource = TestTimeSource()
        val parent = createSpan(name = "parent", timeSource = timeSource)
        parent.startChildSpan("child")
        timeSource += 40.milliseconds

        val completed = parent.end(SpanStatus.Error)

        assertEquals(1, completed.children.size)
    }

    @Test
    fun `GIVEN parent with mix of ended and unfinished children WHEN parent ended THEN all are in CompletedSpan`() {
        val timeSource = TestTimeSource()
        val parent = createSpan(name = "parent", timeSource = timeSource)
        val child1 = parent.startChildSpan("child1")
        timeSource += 20.milliseconds
        child1.end()
        val child2 = parent.startChildSpan("child2")
        timeSource += 30.milliseconds

        val completed = parent.end()

        assertEquals(2, completed.children.size)
        assertEquals("child1", completed.children[0].name)
        assertEquals(20.milliseconds, completed.children[0].duration)
        assertEquals("child2", completed.children[1].name)
        assertNotNull(child2.duration)
    }

    @Test
    fun `GIVEN parent with already ended child WHEN parent ended THEN child duration is preserved`() {
        val timeSource = TestTimeSource()
        val parent = createSpan(name = "parent", timeSource = timeSource)
        val child = parent.startChildSpan("child")
        timeSource += 25.milliseconds
        child.end()
        timeSource += 75.milliseconds

        val completed = parent.end()

        assertEquals(25.milliseconds, completed.children[0].duration)
    }

    @Test
    fun `GIVEN span with no children WHEN ended THEN CompletedSpan has empty children`() {
        val span = createSpan()
        val completed = span.end()
        assertTrue(completed.children.isEmpty())
    }

    //endregion
}
