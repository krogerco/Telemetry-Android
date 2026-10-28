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
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.LIMIT_EXCEEDED
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.LISTENER_THREW
import com.kroger.telemetry.tracing.util.FakeSpanListener
import com.kroger.telemetry.tracing.util.FakeSpanListener.Call
import com.kroger.telemetry.tracing.util.FakeTelemeter
import com.kroger.telemetry.tracing.util.FakeVendor
import com.kroger.telemetry.tracing.util.awaitOrFail
import com.kroger.telemetry.tracing.util.testThread
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertNotNull

internal class SpanListenerTest {
    private val telemeter = FakeTelemeter()
    private val log = CopyOnWriteArrayList<Call>()
    private val first = FakeSpanListener("first", log)
    private val second = FakeSpanListener("second", log)

    private fun tracer(vararg listeners: SpanListener<*>): Tracer =
        Tracer.build {
            telemeter = this@SpanListenerTest.telemeter
            this.listeners += listeners
        }

    private val trace: TraceFacet get() = telemeter.traces.single()

    private fun List<Call>.callbacks(): List<String> = map { it.toString() }

    // Order, thread and timing

    @Test
    fun `GIVEN two listeners WHEN spans start and end THEN they are called in list order, synchronously, on the calling thread`() {
        val tracer = tracer(first, second)
        val caller = Thread.currentThread()

        val root = tracer.startTrace("root")
        assertEquals(listOf("first.onStart(root)", "second.onStart(root)"), log.callbacks())
        val child = root.startChild("child")
        assertEquals(4, log.size)
        child.end()
        assertEquals(6, log.size)
        root.end()

        assertEquals(
            listOf(
                "first.onStart(root)",
                "second.onStart(root)",
                "first.onStart(child, parent=root)",
                "second.onStart(child, parent=root)",
                "first.onEnd(child)",
                "second.onEnd(child)",
                "first.onEnd(root)",
                "second.onEnd(root)",
            ),
            log.callbacks(),
        )
        assertTrue(log.all { it.thread === caller })
    }

    @Test
    fun `GIVEN a SpanStart WHEN received THEN it describes the span as it will be exported`() {
        val starts = CopyOnWriteArrayList<SpanStart>()
        first.onStartHook = { span, _ ->
            starts += span
            span.name
        }
        val tracer = tracer(first)

        tracer.trace("root") { root ->
            root.child("child") { }
            root.startHttpRequest("get", "https://h/a").end()
        }

        assertEquals(listOf(true, false, false), starts.map { it.isRoot })
        assertEquals(listOf(false, false, true), starts.map { it.isHttpRequest })
        assertEquals(trace.spans.map { it.name }, starts.map { it.name })
        assertEquals(trace.spans.map { it.spanId }, starts.map { it.spanId })
        assertTrue(starts.all { it.traceId == trace.traceId })
    }

    @Test
    fun `GIVEN a listener whose exception message contains a URL WHEN reported THEN the export keeps its class and stack but not the message`() {
        val secret = "https://example.com/search?q=private-query"
        val original =
            IllegalArgumentException("cannot parse $secret", IllegalStateException("cause: $secret")).apply {
                addSuppressed(RuntimeException("suppressed: $secret"))
            }
        first.onStartHook = { span, _ ->
            if (span.isHttpRequest) throw original
            span.name
        }
        val tracer = tracer(first)

        tracer.trace("root") { root -> root.startHttpRequest("GET", secret).end() }

        val event = telemeter.events.single { it.facets.any { facet -> facet is TracingDiagnostic } }
        val diagnostic = event.facets.filterIsInstance<TracingDiagnostic>().single()
        val failure = event.facets.filterIsInstance<com.kroger.telemetry.facet.Failure>().single()
        val recorded = listOf(assertNotNull(diagnostic.throwable), assertNotNull(failure.throwable))
        for (thrown in recorded) {
            // Every field a crash reporter reads: the message (and its localized form), causes, suppressed, rendering.
            val rendered = thrown.stackTraceToString()
            assertTrue("private-query" !in rendered, rendered)
            assertTrue("private-query" !in thrown.localizedMessage.orEmpty())
            assertEquals(null, thrown.cause)
            assertTrue(thrown.suppressed.isEmpty())
            // What's needed to find the bug: the original class, as the message reporters show, and its exact stack.
            assertTrue(IllegalArgumentException::class.java.name in thrown.message.orEmpty(), thrown.message)
            assertEquals(original.stackTrace.toList(), thrown.stackTrace.toList())
        }
        assertTrue("private-query" !in (failure.message.orEmpty() + diagnostic.message))
    }

    @Test
    fun `GIVEN a listener exception whose stack trace holds a null element WHEN reported THEN the diagnostic is still recorded`() {
        val malformed =
            object : IllegalStateException("x") {
                @Suppress("UNCHECKED_CAST")
                override fun getStackTrace(): Array<StackTraceElement> = arrayOfNulls<StackTraceElement>(1) as Array<StackTraceElement>
            }
        first.onStartHook = { _, _ -> throw malformed }
        val tracer = tracer(first)

        repeat(2) { tracer.trace("root") { } }

        assertEquals(1, telemeter.diagnostics.count { it.kind == LISTENER_THREW })
    }

    @Test
    fun `GIVEN a listener exception whose stack trace can't be read WHEN reported THEN the diagnostic is still recorded`() {
        val unreadable =
            object : IllegalStateException("x") {
                override fun getStackTrace(): Array<StackTraceElement> = throw UnsupportedOperationException("no stack")
            }
        first.onStartHook = { _, _ -> throw unreadable }
        val tracer = tracer(first)

        repeat(2) { tracer.trace("root") { } }

        // Recorded once (dedup), with no stack; never silently lost.
        val diagnostic = telemeter.diagnostics.single { it.kind == LISTENER_THREW }
        assertTrue(assertNotNull(diagnostic.throwable).stackTrace.isEmpty())
    }

    @Test
    fun `GIVEN a listener WHEN onEnd runs THEN it receives the exported SpanData instance before the trace is recorded`() {
        val recordedBeforeRootEnd = AtomicReference<Boolean>()
        first.onEndHook = { state, _ -> if (state == "root") recordedBeforeRootEnd.set(telemeter.traces.isNotEmpty()) }
        val tracer = tracer(first)

        tracer.trace("root") { root -> root.child("child") { } }

        assertEquals(trace.spans.reversed().map { System.identityHashCode(it) }, first.ends.map { System.identityHashCode(it) })
        trace.spans
            .reversed()
            .zip(first.ends)
            .forEach { (exported, received) -> assertSame(exported, received) }
        assertEquals(false, recordedBeforeRootEnd.get())
    }

    @Test
    fun `GIVEN listeners with different state WHEN children start THEN each receives its own parent state`() {
        first.onStartHook = { span, _ -> "first:${span.name}" }
        second.onStartHook = { span, _ -> "second:${span.name}" }
        val tracer = tracer(first, second)

        tracer.trace("root") { root -> root.child("child") { child -> child.child("grandchild") { } } }

        val parents = log.filter { it.callback == "onStart" }.map { "${it.listener}<-${it.parent}" }
        assertEquals(
            listOf(
                "first<-null",
                "second<-null",
                "first<-first:root",
                "second<-second:root",
                "first<-first:child",
                "second<-second:child",
            ),
            parents,
        )
        assertEquals(
            listOf("first:grandchild", "first:child", "first:root"),
            log
                .filter {
                    it.listener == "first" && it.callback == "onEnd"
                }.map { it.span },
        )
    }

    // Exactly-once onEnd

    @Test
    fun `GIVEN open descendants WHEN an ancestor ends THEN each gets exactly one onEnd, deepest and latest first`() {
        val tracer = tracer(first)

        val root = tracer.startTrace("root") as SpanImpl
        val a = root.startChild("a") as SpanImpl
        val a1 = a.startChild("a1") as SpanImpl
        val a1x = a1.startChild("a1x") as SpanImpl
        val a2 = a.startChild("a2") as SpanImpl
        val b = root.startChild("b") as SpanImpl
        a.end()
        for (span in listOf(a, a1, a1x, a2)) assertNull(span.listenerStates)
        assertNotNull(root.listenerStates)
        assertNotNull(b.listenerStates)
        a.end()
        b.end()
        root.end()
        root.end()

        assertNull(b.listenerStates)
        assertNull(root.listenerStates)
        val ends = log.filter { it.callback == "onEnd" }.map { it.span }
        assertEquals(listOf("a2", "a1x", "a1", "a", "b", "root"), ends)
        assertTrue(first.ends.filter { it.name != "a" && it.name.startsWith("a") }.all { it.isTruncated })
    }

    @Test
    fun `GIVEN a null or throwing onStart WHEN the span runs THEN that listener gets no further calls and children get parent null`() {
        first.onStartHook = { span, _ -> if (span.name == "ignored") null else span.name }
        second.onStartHook = { span, parent ->
            if (span.name == "ignored") throw IllegalStateException("boom")
            span.name.also { if (span.name == "grandchild") assertEquals(null, parent) }
        }
        val tracer = tracer(first, second)

        tracer.trace("root") { root ->
            root.child("ignored") { ignored ->
                ignored.child("grandchild") { }
            }
        }

        val calls = log.callbacks()
        assertTrue("first.onStart(grandchild)" in calls, calls.toString())
        assertTrue("second.onStart(grandchild)" in calls, calls.toString())
        assertTrue(calls.none { it.contains("onEnd(ignored)") }, calls.toString())
        // Both listeners still saw every other span end.
        assertEquals(listOf("grandchild", "root"), first.ends.map { it.name })
        assertEquals(listOf("grandchild", "root"), second.ends.map { it.name })
    }

    // Isolation

    @Test
    fun `GIVEN a throwing listener WHEN any callback throws THEN the other listener and the caller are unaffected`() {
        val throwing =
            object : SpanListener<String> {
                override fun onStart(
                    span: SpanStart,
                    parent: String?,
                ): String = span.name

                override fun onEnd(
                    state: String,
                    span: SpanData,
                ): Unit = throw IllegalStateException("onEnd broke")

                override fun propagationHeaders(state: String): Map<String, String> = throw IllegalStateException("headers broke")
            }
        second.headersHook = { mapOf("x-second" to it) }
        val tracer = tracer(throwing, second)

        val result =
            tracer.trace("root") { root ->
                root.child("step") { step ->
                    val request = step.startHttpRequest("GET", "https://h/a")
                    assertEquals(mapOf("x-second" to "HTTP GET"), request.propagationHeaders())
                    request.end()
                    42
                }
            }

        assertEquals(42, result)
        assertEquals(listOf("HTTP GET", "step", "root"), second.ends.map { it.name })
        assertEquals(3, trace.spans.size)
    }

    @Test
    fun `GIVEN a listener throwing CancellationException WHEN called THEN it is contained like any other throw`() =
        runTest {
            val cancelling =
                object : SpanListener<String> {
                    override fun onStart(
                        span: SpanStart,
                        parent: String?,
                    ): String = throw CancellationException("listener cancelled")
                }
            val tracer = tracer(cancelling, first)

            // If the cancellation escaped, the helper would mark the span Cancelled and the coroutine would stop.
            val result = tracer.trace("root") { root -> root.child("child") { 7 } }
            yield()

            assertEquals(7, result)
            assertEquals(SpanStatus.Ok, trace.root.status)
            assertEquals(listOf("child", "root"), first.ends.map { it.name })
            assertTrue(
                telemeter.diagnostics.any {
                    it.kind == LISTENER_THREW &&
                        (it.throwable as? RedactedThrowable)?.originalClassName == CancellationException::class.java.name
                },
            )
        }

    @Test
    fun `GIVEN a listener that throws on every span WHEN tracing THEN one diagnostic with Failure is recorded per exception class`() {
        var throwState = true
        val throwing =
            object : SpanListener<String> {
                override fun onStart(
                    span: SpanStart,
                    parent: String?,
                ): String {
                    if (span.name == "other") throw UnsupportedOperationException("different")
                    if (throwState) throw IllegalStateException("same every time")
                    return span.name
                }
            }
        val tracer = tracer(throwing)

        repeat(3) { tracer.trace("root") { root -> repeat(5) { root.child("child") { } } } }
        tracer.trace("other") { }
        throwState = false

        val events = telemeter.events.filter { it.facets.any { facet -> facet is TracingDiagnostic } }
        assertEquals(2, events.size)
        events.forEach { event ->
            val diagnostic = event.facets.filterIsInstance<TracingDiagnostic>().single()
            assertEquals(LISTENER_THREW, diagnostic.kind)
            assertEquals(throwing.javaClass.name, diagnostic.source)
            assertTrue(Significance.WARNING in event.facets)
            assertSame(
                diagnostic.throwable,
                event.facets
                    .filterIsInstance<Failure>()
                    .single()
                    .throwable,
            )
            // The message names classes only, never the exception's message.
            assertTrue("same every time" !in diagnostic.message && "different" !in diagnostic.message)
        }
        // The recorded throwable is a redacted copy that keeps the original's class name.
        assertEquals(
            listOf(IllegalStateException::class.java.name, UnsupportedOperationException::class.java.name),
            events.map {
                (
                    it.facets
                        .filterIsInstance<TracingDiagnostic>()
                        .single()
                        .throwable as RedactedThrowable
                ).originalClassName
            },
        )
    }

    // Structured order with a vendor-style listener

    @Test
    fun `GIVEN sequential code WHEN traced with a vendor listener THEN children leave before parents, once each`() {
        val vendor = FakeVendor()
        val tracer = tracer(vendor.listener, first)

        tracer.trace("search") { root ->
            root.setAttribute("mode", "grid")
            root.child("items") { it.addEvent("cache_miss") }
            root.child("facets") { facets -> facets.child("load") { } }
        }

        assertEquals(
            listOf(
                "enter search",
                "enter items",
                "report items.event:cache_miss",
                "report items.status",
                "leave items",
                "enter facets",
                "enter load",
                "report load.status",
                "leave load",
                "report facets.status",
                "leave facets",
                "report search.mode",
                "report search.status",
                "leave search",
            ),
            vendor.calls,
        )
        assertTrue(vendor.violations.isEmpty(), vendor.violations.toString())
    }

    @Test
    fun `GIVEN coroutineScope-structured parallel children WHEN traced THEN every enter has one leave, children first`() =
        runTest {
            val vendor = FakeVendor()
            val tracer = tracer(vendor.listener)

            tracer.trace("search") { root ->
                coroutineScope {
                    val items = async { root.child("items") { yield() } }
                    val facets = async { root.child("facets") { yield() } }
                    items.await()
                    facets.await()
                }
            }

            val enters = vendor.calls.filter { it.startsWith("enter") }.map { it.removePrefix("enter ") }
            val leaves = vendor.calls.filter { it.startsWith("leave") }.map { it.removePrefix("leave ") }
            assertEquals(enters.sorted(), leaves.sorted())
            assertEquals("search", leaves.last())
            assertTrue(vendor.violations.isEmpty(), vendor.violations.toString())
        }

    @Test
    fun `GIVEN a vendor listener WHEN a parent ends with an open child THEN the child's action is skipped, not called after close`() {
        val vendor = FakeVendor()
        val tracer = tracer(vendor.listener)

        val root = tracer.startTrace("root")
        val step = root.startChild("step")
        step.startChild("orphan")
        step.end()
        root.end()

        // The orphan's onEnd runs first (deepest first) while its parent action is still open.
        assertEquals(listOf("leave orphan", "leave step", "leave root"), vendor.calls.filter { it.startsWith("leave") })
        assertTrue(vendor.violations.isEmpty(), vendor.violations.toString())
    }

    // Ended during onStart

    @Test
    fun `GIVEN a child's onStart blocking WHEN another thread ends the parent THEN end returns and the child's onEnd follows on the starting thread`() {
        val inOnStart = CountDownLatch(1)
        val release = CountDownLatch(1)
        val child = AtomicReference<SpanImpl>()
        first.onStartHook = { span, _ ->
            if (span.name == "child") {
                inOnStart.countDown()
                release.awaitOrFail()
            }
            span.name
        }
        val tracer = tracer(first, second)
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")

        val starter = testThread { child.set(parent.startChild("child") as SpanImpl) }
        inOnStart.awaitOrFail()
        val ender = testThread { parent.end() }
        ender.join() // returns while the child's onStart is still blocked
        val parentEnded = log.callbacks().contains("first.onEnd(parent)")
        release.countDown()
        starter.join()
        root.end()

        assertTrue(parentEnded, "the parent's onEnd should not wait for the child's onStart")
        val childCalls = log.filter { it.span == "child" }
        assertEquals(
            listOf(
                "first.onStart(child, parent=parent)",
                "second.onStart(child, parent=parent)",
                "first.onEnd(child)",
                "second.onEnd(child)",
            ),
            childCalls.callbacks(),
        )
        assertTrue(childCalls.all { it.thread === starter.thread })
        assertEquals(1, first.ends.count { it.name == "child" })
        assertTrue(first.ends.single { it.name == "child" }.isTruncated)
        assertNull(child.get().listenerStates)
    }

    @Test
    fun `GIVEN a listener that ends the root from a child's onStart WHEN it runs THEN nothing deadlocks and every state ends once`() {
        val root = AtomicReference<Span>()
        first.onStartHook = { span, _ ->
            if (span.name == "child") root.get().end()
            span.name
        }
        val tracer = tracer(first, second)
        root.set(tracer.startTrace("root"))

        val child = testThread { root.get().startChild("child") }
        child.join()

        for (listener in listOf(first, second)) {
            assertEquals(listOf("root", "child"), listener.ends.map { it.name }, "${listener.ends}")
        }
        assertEquals(1, telemeter.traces.size)
        assertTrue(trace.spans.single { it.name == "child" }.isTruncated)
    }

    // Inert and dropped paths

    @Test
    fun `GIVEN a start rejected by the span limit WHEN listeners are registered THEN its diagnostic is recorded and no callback runs`() {
        val tracer = tracer(first)
        val root = tracer.startTrace("root")
        repeat(TraceLimits.MAX_SPANS - 1) { root.startChild("child$it") }
        log.clear()

        val dropped = root.startChild("dropped")
        dropped.startChild("under dropped").end()
        dropped.end()

        assertTrue(log.isEmpty(), log.toString())
        assertEquals(LIMIT_EXCEEDED, telemeter.diagnostics.single().kind)
        root.end()
        assertTrue(first.ends.none { it.name == "dropped" })
    }

    @Test
    fun `GIVEN inert spans WHEN used THEN listeners see nothing`() {
        val tracer = tracer(first)
        val root = tracer.startTrace("root")
        root.end()
        log.clear()

        root.startChild("late").end()
        Tracer.NoOp.trace("noop") { it.child("child") { } }

        assertTrue(log.isEmpty(), log.toString())
    }

    @Test
    fun `GIVEN no listeners WHEN spans start THEN no SpanStart is built`() {
        val tracer = tracer()
        val root = tracer.startTrace("root") as SpanImpl
        val child = root.startChild("child") as SpanImpl

        // With no listeners the fan-out never runs, so no states are ever published.
        assertEquals(null, root.listenerStates)
        assertEquals(null, child.listenerStates)
        child.end()
        root.end()
    }

    @Test
    fun `GIVEN the builder's listener list WHEN changed after build THEN the tracer is unaffected`() {
        val builder = Tracer.Builder().apply { listeners += first }
        val tracer = builder.build()
        builder.listeners += second

        tracer.trace("root") { }

        assertTrue(log.all { it.listener == "first" })
    }

    // Propagation headers

    @Test
    fun `GIVEN headers in flight WHEN the request ends THEN its states are released without disrupting remaining listeners`() {
        val inHeaders = CountDownLatch(1)
        val release = CountDownLatch(1)
        val headers = AtomicReference<Map<String, String>>()
        first.headersHook = { state ->
            inHeaders.countDown()
            release.awaitOrFail()
            mapOf("x-first" to state)
        }
        second.headersHook = { mapOf("x-second" to it) }
        val tracer = tracer(first, second)
        val root = tracer.startTrace("root")
        val request = root.startHttpRequest("GET", "https://h/a") as HttpSpanImpl

        val reader = testThread { headers.set(request.propagationHeaders()) }
        inHeaders.awaitOrFail()
        request.end()
        val statesAfterEnd = request.listenerStates
        release.countDown()
        reader.join()
        root.end()

        assertNull(statesAfterEnd)
        assertEquals(mapOf("x-first" to "HTTP GET", "x-second" to "HTTP GET"), headers.get())
        assertTrue(telemeter.diagnostics.isEmpty())
    }

    @Test
    fun `GIVEN two listeners WHEN headers are merged THEN list order wins case-insensitively and empty entries are skipped`() {
        first.headersHook = { mapOf("X-Foo" to "first", "x-only-first" to "1", "" to "empty name", "x-empty" to "") }
        second.headersHook = { mapOf("x-foo" to "second", "X-ONLY-FIRST" to "2", "x-second" to "2", "x-empty" to "kept") }
        val tracer = tracer(first, second)

        tracer.trace("root") { root ->
            val headers = root.startHttpRequest("GET", "https://h/a").propagationHeaders()
            assertEquals(mapOf("X-Foo" to "first", "x-only-first" to "1", "x-empty" to "kept", "x-second" to "2"), headers)
        }
    }

    @Test
    fun `GIVEN a provider whose map throws during iteration WHEN merged THEN only its contribution is lost`() {
        first.headersHook = {
            object : AbstractMap<String, String>() {
                override val entries: Set<Map.Entry<String, String>>
                    get() =
                        object : AbstractSet<Map.Entry<String, String>>() {
                            override val size = 2

                            override fun iterator() =
                                iterator<Map.Entry<String, String>> {
                                    yield(java.util.AbstractMap.SimpleEntry("x-partial", "leaked"))
                                    throw ConcurrentModificationException()
                                }
                        }
            }
        }
        second.headersHook = { mapOf("x-second" to "ok") }
        val tracer = tracer(first, second)

        tracer.trace("root") { root ->
            assertEquals(mapOf("x-second" to "ok"), root.startHttpRequest("GET", "https://h/a").propagationHeaders())
        }
    }

    @Test
    fun `GIVEN a Java-style provider returning nulls WHEN merged THEN null maps, names and values are skipped`() {
        @Suppress("UNCHECKED_CAST")
        val nulls = hashMapOf<String?, String?>(null to "v", "x-null" to null, "x-ok" to "v") as Map<String, String>
        first.headersHook = { nullMap() }
        second.headersHook = { nulls }
        val tracer = tracer(first, second)

        tracer.trace("root") { root ->
            assertEquals(mapOf("x-ok" to "v"), root.startHttpRequest("GET", "https://h/a").propagationHeaders())
        }
        assertTrue(telemeter.diagnostics.isEmpty())
    }

    /** A null typed as non-null, the way a Java listener's return looks to Kotlin. A generic cast doesn't null-check. */
    private fun nullMap(): Map<String, String> = nullAs()

    @Suppress("UNCHECKED_CAST")
    private fun <T> nullAs(): T = null as T

    @Test
    fun `GIVEN an ended HTTP span WHEN headers are requested THEN they are empty and no listener is called`() {
        first.headersHook = { mapOf("x-first" to it) }
        val tracer = tracer(first)

        tracer.trace("root") { root ->
            val request = root.startHttpRequest("GET", "https://h/a")
            request.end()
            log.clear()
            assertEquals(emptyMap<String, String>(), request.propagationHeaders())
        }
        assertTrue(log.none { it.callback == "headers" })
    }

    @Test
    fun `GIVEN listeners that declined the HTTP span WHEN headers are requested THEN only listeners with state are asked`() {
        first.onStartHook = { span, _ -> if (span.isHttpRequest) null else span.name }
        first.headersHook = { mapOf("x-first" to it) }
        second.headersHook = { mapOf("x-second" to it) }
        val tracer = tracer(first, second)

        tracer.trace("root") { root ->
            assertEquals(mapOf("x-second" to "HTTP GET"), root.startHttpRequest("GET", "https://h/a").propagationHeaders())
        }
        assertEquals(listOf("second"), log.filter { it.callback == "headers" }.map { it.listener })
    }
}
