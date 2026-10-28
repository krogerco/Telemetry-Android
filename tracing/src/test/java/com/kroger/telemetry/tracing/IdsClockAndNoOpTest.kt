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

import com.kroger.telemetry.tracing.util.FakeClock
import com.kroger.telemetry.tracing.util.FakeTelemeter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class IdsClockAndNoOpTest {
    private val traceIdPattern = Regex("^[0-9a-f]{32}$")
    private val spanIdPattern = Regex("^[0-9a-f]{16}$")

    @Test
    fun `GIVEN many traces WHEN IDs are generated THEN they are W3C-format, never all zeros and unique`() {
        val telemeter = FakeTelemeter()
        val tracer = Tracer.build { this.telemeter = telemeter }

        repeat(500) {
            tracer.trace("root") { root -> root.child("child") { } }
        }

        val traceIds = telemeter.traces.map { it.traceId }
        val spanIds = telemeter.traces.flatMap { trace -> trace.spans.map { it.spanId } }
        assertTrue(traceIds.all { traceIdPattern.matches(it) && it != Ids.INVALID_TRACE_ID })
        assertTrue(spanIds.all { spanIdPattern.matches(it) && it != Ids.INVALID_SPAN_ID })
        assertEquals(traceIds.size, traceIds.toSet().size)
        assertEquals(spanIds.size, spanIds.toSet().size)
    }

    @Test
    fun `GIVEN the random source yields zero WHEN a span ID is generated THEN it draws again`() {
        val random = ScriptedRandom(0, 0, 42)

        assertEquals("000000000000002a", Ids.newSpanId(random))
        assertEquals(3, random.calls)
    }

    @Test
    fun `GIVEN the random source yields an all-zero pair WHEN a trace ID is generated THEN it draws again`() {
        val random = ScriptedRandom(0, 0, 0, 7)

        assertEquals("0000000000000000" + "0000000000000007", Ids.newTraceId(random))
        assertEquals(4, random.calls)
    }

    @Test
    fun `GIVEN one half of a trace ID is zero WHEN generated THEN it is kept`() {
        // Only the all-zero ID is invalid; a zero half is a legitimate value.
        assertEquals("0000000000000005" + "0000000000000000", Ids.newTraceId(ScriptedRandom(5, 0)))
        assertEquals("0000000000000000" + "0000000000000005", Ids.newTraceId(ScriptedRandom(0, 5)))
    }

    @Test
    fun `GIVEN small and negative values WHEN a span ID is generated THEN it is zero-padded lowercase hex`() {
        // Random IDs almost never need padding, so script the values that do.
        assertEquals("0000000000000001", Ids.newSpanId(ScriptedRandom(1)))
        assertEquals("00000000000000ff", Ids.newSpanId(ScriptedRandom(255)))
        assertEquals("ffffffffffffffff", Ids.newSpanId(ScriptedRandom(-1)))
        assertEquals("8000000000000000", Ids.newSpanId(ScriptedRandom(Long.MIN_VALUE)))
        assertEquals("7fffffffffffffff", Ids.newSpanId(ScriptedRandom(Long.MAX_VALUE)))
        assertEquals("0123456789abcdef", Ids.newSpanId(ScriptedRandom(0x0123456789abcdefL)))
    }

    @Test
    fun `GIVEN scripted halves WHEN a trace ID is generated THEN high comes first, each half padded to 16 chars`() {
        assertEquals("ffffffffffffffff" + "0000000000000001", Ids.newTraceId(ScriptedRandom(-1, 1)))
        assertEquals("0123456789abcdef" + "fedcba9876543210", Ids.newTraceId(ScriptedRandom(0x0123456789abcdefL, -0x0123456789abcdf0L)))
    }

    @Test
    fun `GIVEN a live span WHEN read THEN its IDs match its exported snapshot`() {
        val telemeter = FakeTelemeter()
        val tracer = Tracer.build { this.telemeter = telemeter }

        val root = tracer.startTrace("root")
        val child = root.startChild("child")
        child.end()
        root.end()

        assertEquals(root.traceId, child.traceId)
        val trace = telemeter.traces.single()
        assertEquals(listOf(root.spanId, child.spanId), trace.spans.map { it.spanId })
        assertNotEquals(root.spanId, child.spanId)
    }

    // Clock

    @Test
    fun `GIVEN the wall clock jumps backwards mid-trace WHEN events are recorded THEN none precede their span's start`() {
        val telemeter = FakeTelemeter()
        val clock = FakeClock(wallNanos = 1_000.seconds.inWholeNanoseconds)
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                this.clock = clock
            }

        val root = tracer.startTrace("root")
        clock.time += 10.milliseconds
        clock.wallNanos -= 3_600.seconds.inWholeNanoseconds // an hour back, e.g. an NTP correction
        val child = root.startChild("child")
        clock.time += 5.milliseconds
        child.addEvent("during")
        clock.time += 5.milliseconds
        child.end()
        root.end()

        val trace = telemeter.traces.single()
        val childData = trace.spans.single { it.name == "child" }
        val event = childData.events.single()
        assertTrue(event.epochNanos >= childData.startEpochNanos)
        assertTrue(childData.startEpochNanos >= trace.root.startEpochNanos)
        assertEquals(1_000.seconds.inWholeNanoseconds + 10.milliseconds.inWholeNanoseconds, childData.startEpochNanos)
        assertEquals(childData.startEpochNanos + 5.milliseconds.inWholeNanoseconds, event.epochNanos)
        assertEquals(10.milliseconds, childData.duration)
        assertEquals(20.milliseconds, trace.root.duration)
    }

    @Test
    fun `GIVEN two traces WHEN the wall clock changes between them THEN each trace anchors to its own reading`() {
        val telemeter = FakeTelemeter()
        val clock = FakeClock(wallNanos = 5_000L)
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                this.clock = clock
            }

        tracer.trace("first") { }
        clock.wallNanos = 9_000L
        tracer.trace("second") { }

        assertEquals(listOf(5_000L, 9_000L), telemeter.traces.map { it.root.startEpochNanos })
    }

    @Test
    fun `GIVEN NoOp WHEN used THEN spans have all-zero IDs and every call is safe`() {
        val root = Tracer.NoOp.startTrace("root", mapOf("a" to 1))
        val child = root.startChild("child")
        val request = child.startHttpRequest("GET", "https://h/a")

        listOf(root, child, request).forEach { span ->
            assertEquals(Ids.INVALID_TRACE_ID, span.traceId)
            assertEquals(Ids.INVALID_SPAN_ID, span.spanId)
            span.setAttribute("k", "v")
            span.addEvent("e")
            span.recordException(IllegalStateException())
            span.setStatus(SpanStatus.Error("x"))
            span.end()
            span.end()
        }
        request.setResponse(500)
        assertEquals(emptyMap<String, String>(), request.propagationHeaders())
    }

    @Test
    fun `GIVEN NoOp WHEN the helpers run THEN blocks run and return normally`() {
        val result = Tracer.NoOp.trace("root") { root -> root.child("child") { 7 } * 6 }

        assertEquals(42, result)
    }

    @Test
    fun `GIVEN NoOp WHEN started repeatedly THEN it allocates no recording state`() {
        // Every NoOp span is the same shared instance: no IDs, clocks or locks per trace.
        val first = Tracer.NoOp.startTrace("a")
        assertSame(first, Tracer.NoOp.startTrace("b"))
        assertSame(first, first.startChild("c"))
        assertSame(first, first.startHttpRequest("GET", "https://h/a"))
    }

    @Test
    fun `GIVEN a tracer with no telemeter WHEN traces end THEN nothing is exported and nothing throws`() {
        val tracer = Tracer.build()

        tracer.trace("root") { root -> root.child("child") { it.setAttribute("bad", Any()) } }
    }

    @Test
    fun `GIVEN inert spans from a real trace WHEN the trace ends THEN they are never exported`() {
        val telemeter = FakeTelemeter()
        val tracer = Tracer.build { this.telemeter = telemeter }

        tracer.trace("root") { root ->
            val parent = root.startChild("parent")
            parent.end()
            val inert = parent.startChild("inert")
            inert.child("inert child") { }
            assertEquals(root.traceId, inert.traceId)
            assertEquals(Ids.INVALID_SPAN_ID, inert.spanId)
        }

        assertEquals(
            listOf("root", "parent"),
            telemeter.traces
                .single()
                .spans
                .map { it.name },
        )
    }

    /** Returns [values] from [nextLong] in order, so the zero-retry branches can be hit deterministically. */
    private class ScriptedRandom(
        vararg values: Long,
    ) : Random() {
        private val remaining = ArrayDeque(values.toList())
        var calls = 0
            private set

        override fun nextLong(): Long {
            calls++
            return remaining.removeFirst()
        }
    }
}
