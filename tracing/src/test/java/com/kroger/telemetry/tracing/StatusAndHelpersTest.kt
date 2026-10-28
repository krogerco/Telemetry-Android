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

import com.kroger.telemetry.tracing.util.FakeTelemeter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

internal class StatusAndHelpersTest {
    private val telemeter = FakeTelemeter()
    private val tracer = Tracer.build { telemeter = this@StatusAndHelpersTest.telemeter }

    private val trace: TraceFacet get() = telemeter.traces.single()

    private fun named(name: String): SpanData = trace.spans.single { it.name == name }

    @Test
    fun `GIVEN a helper block WHEN it returns normally THEN its value is returned and the status is unchanged`() {
        val result =
            tracer.trace("root") { root ->
                root.child("ok") { 1 } +
                    root.child("explicit") { span ->
                        span.setStatus(SpanStatus.Error("returned"))
                        41
                    }
            }

        assertEquals(42, result)
        assertEquals(SpanStatus.Ok, trace.root.status)
        assertEquals(SpanStatus.Ok, named("ok").status)
        assertEquals(SpanStatus.Error("returned"), named("explicit").status)
        assertTrue(trace.spans.none { it.isTruncated || it.events.isNotEmpty() })
    }

    @Test
    fun `GIVEN an explicit status WHEN the block returns THEN the explicit status is kept`() {
        tracer.trace("root") { root ->
            root.child("cancelled") { it.setStatus(SpanStatus.Cancelled) }
            root.child("last wins") { span ->
                span.setStatus(SpanStatus.Error("first"))
                span.setStatus(SpanStatus.Ok)
            }
        }

        assertEquals(SpanStatus.Cancelled, named("cancelled").status)
        assertEquals(SpanStatus.Ok, named("last wins").status)
    }

    @Test
    fun `GIVEN withTimeout WHEN it expires inside a child THEN the child is Error timeout with the exception recorded`() =
        runTest {
            assertThrows<TimeoutCancellationException> {
                tracer.trace("root") { root ->
                    withTimeout(10) {
                        root.child("slow") { awaitCancellation() }
                    }
                }
            }

            val slow = named("slow")
            assertEquals(SpanStatus.Error("timeout"), slow.status)
            // Not necessarily the instance the caller catches: coroutines may copy it while unwinding.
            assertTrue(slow.events.single().throwable is TimeoutCancellationException)
            assertEquals("exception", slow.events.single().name)
            // The root sees the same timeout escape its block.
            assertEquals(SpanStatus.Error("timeout"), trace.root.status)
        }

    @Test
    fun `GIVEN parallel children WHEN one fails inside async THEN its sibling ends Cancelled`() =
        runTest {
            val started = CompletableDeferred<Unit>()

            assertThrows<IOException> {
                tracer.trace("root") { root ->
                    coroutineScope {
                        val waiting =
                            async {
                                root.child("waiting") {
                                    started.complete(Unit)
                                    awaitCancellation()
                                }
                            }
                        val failing =
                            async {
                                started.await()
                                root.child("failing") { throw IOException("secret") }
                            }
                        waiting.await()
                        failing.await()
                    }
                }
            }

            val waiting = named("waiting")
            assertEquals(SpanStatus.Cancelled, waiting.status)
            assertFalse(waiting.isTruncated)
            // Cancellation isn't an error: nothing is recorded for it.
            assertTrue(waiting.events.isEmpty())
            assertEquals(SpanStatus.Error("IOException"), named("failing").status)
            assertEquals(SpanStatus.Error("IOException"), trace.root.status)
        }

    @Test
    fun `GIVEN a plain CancellationException WHEN thrown from a child THEN the child is Cancelled`() {
        assertThrows<CancellationException> {
            tracer.trace("root") { root -> root.child("cancelled") { throw CancellationException("left screen") } }
        }

        assertEquals(SpanStatus.Cancelled, named("cancelled").status)
        assertEquals(SpanStatus.Cancelled, trace.root.status)
    }

    @Test
    fun `GIVEN an explicit Ok WHEN the block then throws THEN the status is Error with the class name`() {
        assertThrows<IllegalStateException> {
            tracer.trace("root") { root ->
                root.child("step") { span ->
                    span.setStatus(SpanStatus.Ok)
                    throw IllegalStateException("boom")
                }
            }
        }

        val step = named("step")
        assertEquals(SpanStatus.Error("IllegalStateException"), step.status)
        assertTrue(step.events.single().throwable is IllegalStateException)
    }

    @Test
    fun `GIVEN an explicit Error or Cancelled WHEN the block then throws THEN the explicit status is kept`() {
        tracer.trace("root") { root ->
            runCatching {
                root.child("error") { span ->
                    span.setStatus(SpanStatus.Error("business"))
                    throw IllegalStateException("boom")
                }
            }
            runCatching {
                root.child("cancelled") { span ->
                    span.setStatus(SpanStatus.Cancelled)
                    throw IllegalStateException("boom")
                }
            }
        }

        assertEquals(SpanStatus.Error("business"), named("error").status)
        assertEquals(SpanStatus.Cancelled, named("cancelled").status)
        // The exception is still recorded, even though it didn't change the status.
        assertTrue(named("error").events.single().throwable is IllegalStateException)
    }

    @Test
    fun `GIVEN a non-local return from inside child WHEN it exits THEN the span ends with its status unchanged`() {
        fun findFirst(root: Span): String {
            listOf("a", "b").forEach { item ->
                root.child("lookup") { span ->
                    span.setAttribute("item", item)
                    return item
                }
            }
            return "none"
        }

        val result = tracer.trace("root") { root -> findFirst(root) }

        assertEquals("a", result)
        val lookup = named("lookup")
        assertEquals(SpanStatus.Ok, lookup.status)
        assertFalse(lookup.isTruncated)
        assertEquals(mapOf("item" to "a"), lookup.attributes)
        assertEquals(2, trace.spans.size)
    }

    @Test
    fun `GIVEN a labeled return from inside child WHEN it exits THEN the span ends`() {
        tracer.trace("root") { root ->
            run outer@{
                root.child("inner") { return@outer }
            }
            root.child("after") { }
        }

        assertEquals(listOf("root", "inner", "after"), trace.spans.map { it.name })
        assertEquals(SpanStatus.Ok, named("inner").status)
        assertFalse(named("inner").isTruncated)
    }

    @Test
    fun `GIVEN exceptions carrying messages WHEN classified THEN descriptions never contain the message`() {
        val secret = "user@example.com"
        val exceptions =
            listOf(
                IOException(secret),
                IllegalArgumentException(secret),
                TimeoutLike(secret),
                object : RuntimeException(secret) {},
            )

        tracer.trace("root") { root ->
            exceptions.forEachIndexed { i, e -> runCatching { root.child("step $i") { throw e } } }
        }

        val descriptions = trace.spans.drop(1).map { (it.status as SpanStatus.Error).description.orEmpty() }
        assertEquals("IOException", descriptions[0])
        assertEquals("IllegalArgumentException", descriptions[1])
        assertEquals("TimeoutLike", descriptions[2])
        // An anonymous class has no simple name; the binary name is used rather than an empty description.
        assertTrue(descriptions[3].startsWith(StatusAndHelpersTest::class.java.name), descriptions[3])
        assertTrue(descriptions.none { secret in it })
    }

    @Test
    fun `GIVEN a helper on an inert span WHEN the block throws THEN the exception propagates unchanged`() {
        val thrown = IllegalStateException("boom")

        val caught =
            assertThrows<IllegalStateException> {
                Tracer.NoOp.trace("root") { root -> root.child("child") { throw thrown } }
            }

        assertSame(thrown, caught)
        assertTrue(telemeter.events.isEmpty())
    }

    private class TimeoutLike(
        message: String,
    ) : Exception(message)
}
