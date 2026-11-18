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

import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.ENABLED_WHEN_THREW
import com.kroger.telemetry.tracing.util.FakeSpanListener
import com.kroger.telemetry.tracing.util.FakeTelemeter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

internal class TracerEnabledWhenTest {
    @Test
    fun `GIVEN no predicate WHEN a trace runs THEN it is recorded`() {
        val telemeter = FakeTelemeter()
        val tracer = Tracer.build { this.telemeter = telemeter }

        tracer.trace("root") { }

        assertTrue(tracer.isEnabled)
        assertEquals(1, telemeter.traces.size)
    }

    @Test
    fun `GIVEN a predicate that returns false WHEN a trace runs THEN nothing is recorded and the block still runs`() {
        val telemeter = FakeTelemeter()
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { false }
            }

        val result = tracer.trace("root") { root -> root.child("child") { 7 } * 6 }

        assertEquals(42, result)
        assertEquals(0, telemeter.events.size)
    }

    @Test
    fun `GIVEN a predicate that returns false WHEN a trace starts THEN it returns the shared inert span`() {
        val tracer = Tracer.build { enabledWhen = { false } }

        val root = tracer.startTrace("root")

        assertSame(Tracer.NoOp.startTrace("other"), root)
        assertEquals(Ids.INVALID_TRACE_ID, root.traceId)
        assertSame(root, root.startChild("child"))
    }

    @Test
    fun `GIVEN a predicate that returns false WHEN a trace starts THEN listeners are not called`() {
        val listener = FakeSpanListener("listener")
        val tracer =
            Tracer.build {
                enabledWhen = { false }
                listeners += listener
            }

        tracer.trace("root") { root -> root.child("child") { } }

        assertEquals(emptyList<FakeSpanListener.Call>(), listener.log)
    }

    @Test
    fun `GIVEN the predicate flips WHEN traces start THEN only traces started while enabled are recorded`() {
        val telemeter = FakeTelemeter()
        var enabled = false
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { enabled }
            }

        tracer.trace("before") { }
        enabled = true
        tracer.trace("during") { }
        enabled = false
        tracer.trace("after") { }

        assertEquals(listOf("during"), telemeter.traces.map { it.root.name })
    }

    @Test
    fun `GIVEN a trace started while enabled WHEN the predicate turns false THEN that trace still completes`() {
        // The decision is made once, at the root, so a trace is never half-recorded.
        val telemeter = FakeTelemeter()
        var enabled = true
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { enabled }
            }

        tracer.trace("root") { root ->
            enabled = false
            root.child("child") { }
        }

        val trace = telemeter.traces.single()
        assertEquals(listOf("root", "child"), trace.spans.map { it.name })
    }

    @Test
    fun `GIVEN a trace started while disabled WHEN the predicate turns true THEN that trace stays inert`() {
        val telemeter = FakeTelemeter()
        var enabled = false
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { enabled }
            }

        tracer.trace("root") { root ->
            enabled = true
            root.child("child") { }
        }

        assertEquals(emptyList<Any>(), telemeter.events)
    }

    @Test
    fun `GIVEN a predicate WHEN isEnabled is read THEN it re-evaluates on every read`() {
        var enabled = true
        val tracer = Tracer.build { enabledWhen = { enabled } }

        assertTrue(tracer.isEnabled)
        enabled = false
        assertFalse(tracer.isEnabled)
        enabled = true
        assertTrue(tracer.isEnabled)
    }

    @Test
    fun `GIVEN a predicate WHEN traces start THEN it is evaluated once per trace and not per span`() {
        val calls = AtomicInteger()
        val tracer =
            Tracer.build {
                enabledWhen = {
                    calls.incrementAndGet()
                    true
                }
            }

        tracer.trace("root") { root -> root.child("child") { root.startHttpRequest("GET", "https://h/a").end() } }

        assertEquals(1, calls.get())
    }

    @Test
    fun `GIVEN a predicate that throws WHEN a trace starts THEN the trace is inert and nothing throws`() {
        val telemeter = FakeTelemeter()
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { error("flag store unavailable") }
            }

        val result = tracer.trace("root") { 7 }

        assertEquals(7, result)
        assertEquals(emptyList<TraceFacet>(), telemeter.traces)
    }

    @Test
    fun `GIVEN a predicate that throws WHEN a trace starts THEN a diagnostic names the predicate and exception`() {
        val telemeter = FakeTelemeter()
        val predicate: () -> Boolean = { throw IllegalStateException("flag store unavailable") }
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = predicate
            }

        tracer.trace("root") { }

        val diagnostic = telemeter.diagnostics.single()
        assertEquals(ENABLED_WHEN_THREW, diagnostic.kind)
        assertEquals(predicate.javaClass.name, diagnostic.source)
        assertTrue("IllegalStateException" in diagnostic.message, diagnostic.message)
        val event = telemeter.events.single()
        assertEquals(Significance.WARNING, event.facets.filterIsInstance<Significance>().single())
        assertTrue(event.facets.any { it is Failure })
    }

    @Test
    fun `GIVEN a predicate that throws WHEN the diagnostic is recorded THEN the exception message appears nowhere`() {
        val telemeter = FakeTelemeter()
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { error("token=SECRET") }
            }

        tracer.trace("root") { }

        val diagnostic = telemeter.diagnostics.single()
        assertTrue("IllegalStateException" in diagnostic.throwable.toString(), diagnostic.throwable.toString())
        val everything =
            telemeter.events.joinToString("\n") { it.description + it.facets.joinToString() } +
                diagnostic.message + diagnostic.source + diagnostic.throwable?.message
        assertFalse("SECRET" in everything)
    }

    @Test
    fun `GIVEN a predicate that throws every time WHEN many traces start THEN it is reported once`() {
        val telemeter = FakeTelemeter()
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { error("flag store unavailable") }
            }

        repeat(100) { tracer.trace("root") { } }

        assertEquals(1, telemeter.diagnostics.size)
    }

    @Test
    fun `GIVEN a predicate that throws different exceptions WHEN traces start THEN each class is reported once`() {
        val telemeter = FakeTelemeter()
        val failures = ArrayDeque(listOf(IllegalStateException(), IllegalArgumentException(), IllegalStateException()))
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { throw failures.removeFirst() }
            }

        repeat(3) { tracer.trace("root") { } }

        assertEquals(2, telemeter.diagnostics.size)
    }

    @Test
    fun `GIVEN a predicate that throws WHEN isEnabled is read THEN it is false and the diagnostic is recorded`() {
        val telemeter = FakeTelemeter()
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { error("flag store unavailable") }
            }

        assertFalse(tracer.isEnabled)

        assertEquals(ENABLED_WHEN_THREW, telemeter.diagnostics.single().kind)
    }

    @Test
    fun `GIVEN a predicate that throws and no telemeter WHEN a trace starts THEN it is inert and nothing throws`() {
        val tracer = Tracer.build { enabledWhen = { error("flag store unavailable") } }

        assertFalse(tracer.isEnabled)
        assertEquals(7, tracer.trace("root") { 7 })
    }

    @Test
    fun `GIVEN a predicate that throws once WHEN it recovers THEN later traces are recorded`() {
        val telemeter = FakeTelemeter()
        var failing = true
        val tracer =
            Tracer.build {
                this.telemeter = telemeter
                enabledWhen = { if (failing) error("not ready") else true }
            }

        tracer.trace("early") { }
        failing = false
        tracer.trace("late") { }

        assertEquals(listOf("late"), telemeter.traces.map { it.root.name })
        assertEquals(1, telemeter.diagnostics.size)
    }

    @Test
    fun `GIVEN NoOp WHEN isEnabled is read THEN it is false`() {
        assertFalse(Tracer.NoOp.isEnabled)
    }
}
