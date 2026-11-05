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

import com.kroger.telemetry.Event
import com.kroger.telemetry.Relay
import com.kroger.telemetry.Telemeter
import com.kroger.telemetry.child
import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.facet.FacetResolver
import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.facet.UnresolvedFacet
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.ATTRIBUTE_DROPPED
import com.kroger.telemetry.tracing.util.FakeTelemeter
import com.kroger.telemetry.tracing.util.TIMEOUT_SECONDS
import com.kroger.telemetry.tracing.util.awaitOrFail
import com.kroger.telemetry.tracing.util.testThread
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Listener diagnostics (LISTENER_THREW, with Failure) arrive with the realtime tier in item 10; deduplication of
 * every other kind is covered in [DiagnosticDedupTest].
 */
internal class DiagnosticsTest {
    @Test
    fun `GIVEN a dropped attribute WHEN reported THEN the event is TracingDiagnostic plus WARNING without Failure`() {
        val telemeter = FakeTelemeter()
        val tracer = Tracer.build { this.telemeter = telemeter }

        tracer.trace("root") { it.setAttribute("bad", Any()) }

        val event = telemeter.events.first()
        assertEquals("Tracing diagnostic: $ATTRIBUTE_DROPPED", event.description)
        assertEquals(2, event.facets.size)
        val diagnostic = event.facets[0] as TracingDiagnostic
        assertEquals(ATTRIBUTE_DROPPED, diagnostic.kind)
        assertNull(diagnostic.source)
        assertNull(diagnostic.throwable)
        assertEquals(Significance.WARNING, event.facets[1])
        assertTrue(event.facets.none { it is Failure })
        assertEquals("TracingDiagnostic(kind=$ATTRIBUTE_DROPPED, message=${diagnostic.message})", diagnostic.toString())
    }

    @Test
    fun `GIVEN a telemeter whose record throws WHEN tracing THEN nothing reaches the caller and tracing continues`() {
        val calls = AtomicInteger()
        val throwing =
            object : Telemeter {
                override fun record(
                    event: Event,
                    withFacets: List<Facet>?,
                ) {
                    calls.incrementAndGet()
                    throw IllegalStateException("telemeter broke")
                }
            }
        val tracer = Tracer.build { telemeter = throwing }

        repeat(2) {
            tracer.trace("root") { root ->
                root.setAttribute("bad", Any()) // a diagnostic (only the first trace records it)
                root.child("step") { it.addEvent(name = "event") }
            }
        }

        // Two completions plus one diagnostic, each attempted despite earlier throws.
        assertEquals(3, calls.get())
    }

    @Test
    fun `GIVEN a telemeter whose record throws an Error WHEN tracing THEN the Error propagates`() {
        val throwing =
            object : Telemeter {
                override fun record(
                    event: Event,
                    withFacets: List<Facet>?,
                ): Unit = throw StackOverflowError()
            }
        val tracer = Tracer.build { telemeter = throwing }

        assertThrows<StackOverflowError> { tracer.trace("root") { } }
    }

    @Test
    fun `GIVEN a real telemeter whose FacetResolver throws WHEN tracing THEN traces still reach relays`() =
        runTest {
            class Unresolved : UnresolvedFacet
            val resolverCalls = AtomicInteger()
            val throwingResolver =
                object : FacetResolver {
                    override fun getType(): Class<*> = Unresolved::class.java

                    override fun resolve(unresolvedFacet: UnresolvedFacet): List<Facet> {
                        resolverCalls.incrementAndGet()
                        throw IllegalStateException("resolver broke")
                    }
                }
            val received = mutableListOf<TraceFacet>()
            val relay = Relay.buildTypedRelay<TraceFacet> { trace -> received += trace }
            val parent =
                Telemeter.build(
                    relays = listOf(relay),
                    facetResolvers = mapOf(Unresolved::class.java to throwingResolver),
                    flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = backgroundScope),
                )
            // A child's facets become part of the event it forwards, so the parent resolves them: the tracing event
            // reaches the throwing resolver this way. (A telemeter's own facets are appended after resolution.)
            val child = parent.child(facets = listOf(Unresolved()))
            val tracer = Tracer.build { this.telemeter = child }

            tracer.trace("root") { }
            testScheduler.runCurrent()

            assertEquals(1, resolverCalls.get())
            assertEquals(listOf("root"), received.map { it.root.name })
        }

    @Test
    fun `GIVEN a telemeter whose record blocks WHEN another thread operates on the trace THEN it is not blocked`() {
        val recording = CountDownLatch(1)
        val release = CountDownLatch(1)
        // An exception thrown in record is swallowed by tracing, so a timed-out wait is recorded here instead.
        val releaseTimedOut = AtomicBoolean(false)
        val blocking =
            FakeTelemeter { event ->
                if (event.facets.any { it is TracingDiagnostic }) {
                    recording.countDown()
                    // Longer than the wait for the other thread below, which starts later, so this can't give up first.
                    if (!release.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)) releaseTimedOut.set(true)
                }
            }
        val tracer = Tracer.build { telemeter = blocking }
        val root = tracer.startTrace("root")
        val child = root.startChild("child")

        // This write's diagnostic parks the thread inside record, after the lock is released.
        val writer = testThread { child.setAttribute("bad", Any()) }
        recording.awaitOrFail()
        val operated = CountDownLatch(1)
        val other =
            testThread {
                child.setAttribute("ok", true)
                root.startChild("sibling").end()
                child.end()
                operated.countDown()
            }
        val completed = operated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        release.countDown()
        writer.join()
        other.join()
        root.end()

        assertTrue(completed, "another thread was blocked while record was running")
        // Backstop: record's wait is longer than the other thread's, so it should never time out. If it did, the lock
        // may have been released by the timeout rather than by this test.
        assertFalse(releaseTimedOut.get(), "record was still blocked when its wait timed out, so the lock may have been held")
        assertEquals(
            mapOf("ok" to true),
            blocking.traces
                .single()
                .spans
                .single { it.name == "child" }
                .attributes,
        )
    }

    @Test
    fun `GIVEN no telemeter WHEN problems occur THEN they are dropped silently`() {
        val tracer = Tracer.build { urlSanitizer = UrlSanitizer { throw IllegalStateException() } }

        tracer.trace("root") { root ->
            root.setAttribute("bad", Any())
            root.startHttpRequest("GET", "https://h/a").end()
        }
    }
}
