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

import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.tracing.util.BlockingMap
import com.kroger.telemetry.tracing.util.FakeClock
import com.kroger.telemetry.tracing.util.FakeTelemeter
import com.kroger.telemetry.tracing.util.TIMEOUT_SECONDS
import com.kroger.telemetry.tracing.util.awaitOrFail
import com.kroger.telemetry.tracing.util.testThread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

internal class TraceLifecycleTest {
    private val telemeter = FakeTelemeter()
    private val clock = FakeClock()
    private val tracer =
        Tracer.build {
            telemeter = this@TraceLifecycleTest.telemeter
            clock = this@TraceLifecycleTest.clock
        }

    private fun TraceFacet.span(live: Span): SpanData = spans.single { it.spanId == live.spanId }

    @Test
    fun `GIVEN a trace WHEN the root ends THEN ids and parent links match the live spans in start order`() {
        val root = tracer.startTrace("root")
        val a = root.startChild("a")
        val b = root.startChild("b")
        val a1 = a.startChild("a1")
        a1.end()
        b.end()
        a.end()
        root.end()

        val trace = telemeter.traces.single()
        assertEquals(root.traceId, trace.traceId)
        assertEquals(listOf("root", "a", "b", "a1"), trace.spans.map { it.name })
        assertEquals(listOf(root, a, b, a1).map { it.spanId }, trace.spans.map { it.spanId })
        assertTrue(trace.spans.all { it.traceId == root.traceId })
        assertSame(trace.spans.first(), trace.root)
        assertNull(trace.span(root).parentSpanId)
        assertEquals(root.spanId, trace.span(a).parentSpanId)
        assertEquals(root.spanId, trace.span(b).parentSpanId)
        assertEquals(a.spanId, trace.span(a1).parentSpanId)
        assertEquals(0, trace.droppedSpanCount)
    }

    @Test
    fun `GIVEN a trace WHEN the root ends THEN exactly one VERBOSE completion event is recorded`() {
        val root = tracer.startTrace("search")
        root.startChild("step").end()
        root.end()
        root.end()

        val event = telemeter.events.single()
        assertEquals("Trace: search", event.description)
        assertEquals(2, event.facets.size)
        assertTrue(event.facets[0] is TraceFacet)
        assertEquals(Significance.VERBOSE, event.facets[1])
    }

    @Test
    fun `GIVEN a telemeter that operates on the trace from another thread WHEN the root ends THEN recording completes`() {
        val root = AtomicReference<Span>()
        val completedWhileRecording = AtomicBoolean(false)
        val telemeter =
            FakeTelemeter { event ->
                if (event.facets.any { it is TraceFacet }) {
                    // Stalls if the completion is recorded while the trace lock is held. The tracer swallows
                    // exceptions from record, so the outcome is captured in a flag rather than thrown.
                    val operated = CountDownLatch(1)
                    thread {
                        root.get().setAttribute("late", true)
                        root.get().startChild("late child").end()
                        operated.countDown()
                    }
                    completedWhileRecording.set(operated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                }
            }
        val tracer = Tracer.build { this.telemeter = telemeter }
        root.set(tracer.startTrace("root"))

        root.get().end()

        assertTrue(completedWhileRecording.get())
        assertEquals(1, telemeter.traces.size)
    }

    @Test
    fun `GIVEN nested spans WHEN a parent ends THEN its open child is truncated and its sibling stays open`() {
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")
        val child = parent.startChild("child")
        val sibling = root.startChild("sibling")

        parent.end()
        sibling.setAttribute("still", "open")
        clock.time += 5.milliseconds
        sibling.end()
        root.end()

        val trace = telemeter.traces.single()
        val childData = trace.span(child)
        assertTrue(childData.isTruncated)
        assertEquals(SpanStatus.Cancelled, childData.status)
        assertFalse(trace.span(parent).isTruncated)
        assertEquals(SpanStatus.Ok, trace.span(parent).status)
        val siblingData = trace.span(sibling)
        assertFalse(siblingData.isTruncated)
        assertEquals(SpanStatus.Ok, siblingData.status)
        assertEquals(mapOf("still" to "open"), siblingData.attributes)
        assertEquals(5.milliseconds, siblingData.duration)
    }

    @Test
    fun `GIVEN an ended parent WHEN a child starts THEN it is inert and nothing is reported`() {
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")
        val child = parent.startChild("child")
        parent.end()

        val late = parent.startChild("again")
        val lateRequest = parent.startHttpRequest("GET", "https://example.com/a")
        child.addEvent("late")
        child.setAttribute("late", true)
        root.end()

        assertEquals(Ids.INVALID_SPAN_ID, late.spanId)
        assertEquals(root.traceId, late.traceId)
        assertEquals(Ids.INVALID_SPAN_ID, lateRequest.spanId)
        assertTrue(telemeter.diagnostics.isEmpty())
        val trace = telemeter.traces.single()
        assertEquals(listOf("root", "parent", "child"), trace.spans.map { it.name })
        assertTrue(trace.span(child).events.isEmpty())
        assertTrue(trace.span(child).attributes.isEmpty())
        assertEquals(0, trace.droppedSpanCount)
    }

    @Test
    fun `GIVEN a closed trace WHEN the root is used again THEN every call is ignored`() {
        val root = tracer.startTrace("root")
        root.end()

        val late = root.startChild("late")
        late.end()
        root.setStatus(SpanStatus.Error("late"))
        root.end()

        assertEquals(Ids.INVALID_SPAN_ID, late.spanId)
        assertEquals(1, telemeter.traces.size)
        assertEquals(
            SpanStatus.Ok,
            telemeter.traces
                .single()
                .root.status,
        )
    }

    @Test
    fun `GIVEN a truncated span with an explicit status WHEN it is exported THEN the explicit status is kept`() {
        val root = tracer.startTrace("root")
        val failed = root.startChild("failed")
        val succeeded = root.startChild("succeeded")
        failed.setStatus(SpanStatus.Error("boom"))
        succeeded.setStatus(SpanStatus.Ok)

        root.end()

        val trace = telemeter.traces.single()
        assertTrue(trace.span(failed).isTruncated)
        assertEquals(SpanStatus.Error("boom"), trace.span(failed).status)
        assertTrue(trace.span(succeeded).isTruncated)
        assertEquals(SpanStatus.Ok, trace.span(succeeded).status)
    }

    @Test
    fun `GIVEN open descendants WHEN a span ends THEN they share its closure time`() {
        val root = tracer.startTrace("root")
        clock.time += 10.milliseconds
        val parent = root.startChild("parent")
        clock.time += 10.milliseconds
        val child = parent.startChild("child")
        clock.time += 10.milliseconds
        val grandchild = child.startChild("grandchild")
        clock.time += 10.milliseconds

        parent.end()
        clock.time += 10.milliseconds
        root.end()

        val trace = telemeter.traces.single()
        val ends = listOf(parent, child, grandchild).map { trace.span(it).run { startEpochNanos + duration.inWholeNanoseconds } }
        assertEquals(1, ends.toSet().size)
        assertEquals(30.milliseconds, trace.span(parent).duration)
        assertEquals(20.milliseconds, trace.span(child).duration)
        assertEquals(10.milliseconds, trace.span(grandchild).duration)
        assertEquals(50.milliseconds, trace.root.duration)
        assertEquals(clock.wallNanos, trace.root.startEpochNanos)
        assertEquals(clock.wallNanos + 10.milliseconds.inWholeNanoseconds, trace.span(parent).startEpochNanos)
    }

    @Test
    fun `GIVEN open spans WHEN the root ends THEN every span is exported, truncated ones included`() {
        val root = tracer.startTrace("root")
        val done = root.startChild("done")
        done.end()
        val open = root.startChild("open")
        val openChild = open.startChild("open child")
        val request = open.startHttpRequest("get", "https://example.com/a")

        root.end()

        val trace = telemeter.traces.single()
        assertEquals(listOf(root, done, open, openChild, request).map { it.spanId }, trace.spans.map { it.spanId })
        assertEquals(
            listOf(false, false, true, true, true),
            trace.spans.map { it.isTruncated },
        )
        assertEquals("HTTP GET", trace.span(request).name)
    }

    @Test
    fun `GIVEN a child start racing its parent's end WHEN the end wins THEN the child is inert`() {
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = AtomicReference<Span>()

        // The start reads its attributes before taking the lock, so it parks before the start is decided.
        val starter = testThread { child.set(parent.startChild("child", BlockingMap(entered, release))) }
        entered.awaitOrFail()
        parent.end()
        release.countDown()
        starter.join()
        root.end()

        assertEquals(Ids.INVALID_SPAN_ID, child.get().spanId)
        assertEquals(
            listOf("root", "parent"),
            telemeter.traces
                .single()
                .spans
                .map { it.name },
        )
    }

    @Test
    fun `GIVEN a child start racing its parent's end WHEN the start wins THEN the child is exported as truncated`() {
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = AtomicReference<Span>()

        // The start reads the clock under the lock, so it parks holding the lock.
        clock.gateNextOffsetRead(entered, release)
        val starter = testThread { child.set(parent.startChild("child")) }
        entered.awaitOrFail()
        val ender = testThread { parent.end() }
        ender.awaitBlocked()
        release.countDown()
        starter.join()
        ender.join()
        root.end()

        val trace = telemeter.traces.single()
        val childData = trace.span(child.get())
        assertTrue(childData.isTruncated)
        assertEquals(SpanStatus.Cancelled, childData.status)
        assertEquals(parent.spanId, childData.parentSpanId)
    }

    @Test
    fun `GIVEN a child ending WHEN its parent ends concurrently THEN the child is closed and exported once`() {
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")
        val child = parent.startChild("child")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        // The child's end holds the lock while the parent's end waits for it.
        clock.gateNextOffsetRead(entered, release)
        val childEnder = testThread { child.end() }
        entered.awaitOrFail()
        val parentEnder = testThread { parent.end() }
        parentEnder.awaitBlocked()
        release.countDown()
        childEnder.join()
        parentEnder.join()
        root.end()

        val trace = telemeter.traces.single()
        assertEquals(1, trace.spans.count { it.spanId == child.spanId })
        assertFalse(trace.span(child).isTruncated)
        assertEquals(
            3,
            trace.spans
                .map { it.spanId }
                .toSet()
                .size,
        )
    }

    @Test
    fun `GIVEN a parent ending WHEN its child ends concurrently THEN the child is closed and exported once`() {
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")
        val child = parent.startChild("child")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        // The parent's end holds the lock while the child's end waits for it.
        clock.gateNextOffsetRead(entered, release)
        val parentEnder = testThread { parent.end() }
        entered.awaitOrFail()
        val childEnder = testThread { child.end() }
        childEnder.awaitBlocked()
        release.countDown()
        parentEnder.join()
        childEnder.join()
        root.end()

        val trace = telemeter.traces.single()
        assertEquals(1, trace.spans.count { it.spanId == child.spanId })
        assertTrue(trace.span(child).isTruncated)
        assertEquals(3, trace.spans.size)
    }

    @Test
    fun `GIVEN two threads WHEN both end the root THEN it closes once and one completion event is recorded`() {
        val root = tracer.startTrace("root")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        clock.gateNextOffsetRead(entered, release)
        val first = testThread { root.end() }
        entered.awaitOrFail()
        val second = testThread { root.end() }
        second.awaitBlocked()
        release.countDown()
        first.join()
        second.join()

        assertEquals(1, telemeter.events.size)
        assertEquals(
            1,
            telemeter.traces
                .single()
                .spans.size,
        )
    }

    @Test
    fun `GIVEN many threads WHEN they start and end spans on one trace THEN the export is consistent`() {
        // Supplementary evidence, not a gate: the deterministic cases above are the real checks.
        repeat(20) {
            val telemeter = FakeTelemeter()
            val tracer = Tracer.build { this.telemeter = telemeter }
            val root = tracer.startTrace("root")
            val parents = List(4) { root.startChild("parent $it") }
            val start = CountDownLatch(1)
            val workers =
                parents.map { parent ->
                    testThread {
                        start.awaitOrFail()
                        repeat(10) { parent.startChild("child $it").end() }
                        parent.end()
                    }
                } +
                    testThread {
                        start.awaitOrFail()
                        root.end()
                    }
            start.countDown()
            workers.forEach { it.join() }

            val trace = telemeter.traces.single()
            val ids = trace.spans.map { it.spanId }
            assertEquals(ids.size, ids.toSet().size)
            assertTrue(trace.spans.drop(1).all { span -> span.parentSpanId in ids })
            // Every exported span is closed: an inert start never appears, and nothing open is left behind.
            assertTrue(ids.none { it == Ids.INVALID_SPAN_ID })
            assertTrue(trace.spans.all { it.duration >= kotlin.time.Duration.ZERO })
            assertEquals(1, telemeter.events.size)
        }
    }

    @Test
    fun `GIVEN exported types WHEN printed THEN each is one short line`() {
        val root = tracer.startTrace("search")
        root.startChild("step").apply {
            addEvent("cache_miss")
            recordException(IllegalStateException("contains user data"))
            end()
        }
        root.startHttpRequest("GET", "https://example.com/a").apply {
            setResponse(503)
            end()
        }
        clock.time += 412.milliseconds
        root.end()

        val trace = telemeter.traces.single()
        assertEquals("TraceFacet(name=search, spans=3, duration=412ms, status=Ok)", trace.toString())
        val printed = listOf(trace.toString()) + trace.spans.map { it.toString() } + trace.spans[1].events.map { it.toString() }
        assertTrue(printed.none { '\n' in it })
        assertFalse(printed.any { "user data" in it })
        assertEquals("SpanData(name=HTTP GET, duration=0ms, status=Error(HTTP 503))", trace.spans[2].toString())
        assertEquals("SpanEvent(name=exception, throwable=IllegalStateException)", trace.spans[1].events[1].toString())
    }
}
