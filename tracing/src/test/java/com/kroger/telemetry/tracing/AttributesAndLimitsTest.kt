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

import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.ATTRIBUTES_UNREADABLE
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.ATTRIBUTE_DROPPED
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.LIMIT_EXCEEDED
import com.kroger.telemetry.tracing.util.FakeTelemeter
import com.kroger.telemetry.tracing.util.budgetLeft
import com.kroger.telemetry.tracing.util.budgetUsed
import com.kroger.telemetry.tracing.util.fillBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.AbstractMap.SimpleEntry

internal class AttributesAndLimitsTest {
    private val telemeter = FakeTelemeter()
    private val tracer = Tracer.build { telemeter = this@AttributesAndLimitsTest.telemeter }

    private val trace: TraceFacet get() = telemeter.traces.single()

    private enum class Mode { GRID }

    private fun limitMessages(): List<String> = telemeter.diagnostics.filter { it.kind == LIMIT_EXCEEDED }.map { it.message }

    // Normalization

    @Test
    fun `GIVEN every supported type WHEN set through setAttribute and an initial map THEN both normalize the same way`() {
        val input =
            mapOf<String, Any?>(
                "string" to "s",
                "long" to 1L,
                "double" to 1.5,
                "boolean" to true,
                "int" to 2,
                "short" to 3.toShort(),
                "byte" to 4.toByte(),
                "float" to 0.5f,
                "enum" to Mode.GRID,
                "null" to null,
            )
        val expected =
            mapOf<String, Any>(
                "string" to "s",
                "long" to 1L,
                "double" to 1.5,
                "boolean" to true,
                "int" to 2L,
                "short" to 3L,
                "byte" to 4L,
                "float" to 0.5,
                "enum" to "GRID",
            )

        val root = tracer.startTrace("root", input)
        val child = root.startChild("child")
        input.forEach { (key, value) -> child.setAttribute(key, value) }
        child.end()
        root.end()

        trace.spans.forEach { span ->
            assertEquals(expected, span.attributes)
            // Equal values of a different boxed type would pass the map comparison; check the types too.
            assertEquals(expected.mapValues { it.value.javaClass }, span.attributes.mapValues { it.value.javaClass })
            // A null is skipped, not dropped.
            assertEquals(0, span.droppedAttributeCount)
        }
        assertTrue(telemeter.diagnostics.isEmpty())
    }

    @Test
    fun `GIVEN a Float WHEN normalized THEN the Double keeps its decimal value, not its binary expansion`() {
        tracer.trace("root") { root ->
            root.setAttribute("ratio", 0.1f)
            root.setAttribute("tiny", 1.0E-7f)
        }

        // 0.1f.toDouble() would be 0.10000000149011612.
        assertEquals(0.1, trace.root.attributes["ratio"])
        assertEquals(1.0E-7, trace.root.attributes["tiny"])
    }

    @Test
    fun `GIVEN a non-null map plus a nullable map WHEN passed as initial attributes THEN it compiles and nulls are skipped`() {
        fun getSearchTraceAttributes(): Map<String, Any?> = mapOf("geofence" to null, "store" to "014")

        tracer.trace("search", mapOf("mode" to "grid") + getSearchTraceAttributes()) { }

        assertEquals(mapOf("mode" to "grid", "store" to "014"), trace.root.attributes)
    }

    @Test
    fun `GIVEN an unsupported type WHEN set THEN it is dropped, counted and reported once without Failure`() {
        tracer.trace("root", mapOf("char" to 'a')) { root ->
            root.setAttribute("char2", 'b')
            root.setAttribute("char3", 'c')
        }

        assertTrue(trace.root.attributes.isEmpty())
        assertEquals(3, trace.root.droppedAttributeCount)
        val event = telemeter.events.single { it.facets.any { facet -> facet is TracingDiagnostic } }
        assertEquals(ATTRIBUTE_DROPPED, (event.facets[0] as TracingDiagnostic).kind)
        assertTrue(Significance.WARNING in event.facets)
        assertTrue(event.facets.none { it is Failure })
    }

    // Caps

    @Test
    fun `GIVEN a 10,000-char value WHEN set THEN it is stored as 1,024 chars with one LIMIT_EXCEEDED`() {
        tracer.trace("root") { root ->
            root.setAttribute("big", "x".repeat(10_000))
            root.setAttribute("bigger", "y".repeat(20_000))
        }

        assertEquals("x".repeat(1_024), trace.root.attributes["big"])
        assertEquals("y".repeat(1_024), trace.root.attributes["bigger"])
        assertEquals(listOf("Attribute value truncated to 1024 chars"), limitMessages())
    }

    @Test
    fun `GIVEN two 200-char keys sharing a 128-char prefix WHEN set THEN both are dropped and counted`() {
        val prefix = "k".repeat(128)

        tracer.trace("root") { root ->
            root.setAttribute(prefix + "a".repeat(72), "first")
            root.setAttribute(prefix + "b".repeat(72), "second")
            root.setAttribute(prefix, "exactly at the limit")
        }

        assertEquals(mapOf(prefix to "exactly at the limit"), trace.root.attributes)
        assertEquals(2, trace.root.droppedAttributeCount)
        assertEquals(1, limitMessages().size)
    }

    @Test
    fun `GIVEN over-long names WHEN a span and an event start THEN both are truncated to 256 chars`() {
        tracer.trace("r".repeat(300)) { root -> root.addEvent("e".repeat(300)) }

        assertEquals(256, trace.root.name.length)
        assertEquals(
            256,
            trace.root.events
                .single()
                .name.length,
        )
    }

    @Test
    fun `GIVEN an over-long status description WHEN set THEN it is truncated to 512 chars`() {
        tracer.trace("root") { root -> root.setStatus(SpanStatus.Error("d".repeat(600))) }

        assertEquals(SpanStatus.Error("d".repeat(512)), trace.root.status)
    }

    @Test
    fun `GIVEN a value with a surrogate pair at the cap WHEN truncated THEN the pair is not split`() {
        val value = "a".repeat(1_023) + "😀" // the emoji's high surrogate sits at index 1,023

        tracer.trace("root") { root -> root.setAttribute("emoji", value) }

        assertEquals("a".repeat(1_023), trace.root.attributes["emoji"])
    }

    // Count limits

    @Test
    fun `GIVEN 64 attributes WHEN the 65th is set THEN it is dropped and counted`() {
        tracer.trace("root") { root ->
            repeat(64) { root.setAttribute("k$it", it) }
            root.setAttribute("k64", 64)
        }

        assertEquals(64, trace.root.attributes.size)
        assertNull(trace.root.attributes["k64"])
        assertEquals(1, trace.root.droppedAttributeCount)
        assertEquals(listOf("Attribute limit of 64 reached; attribute dropped"), limitMessages())
    }

    @Test
    fun `GIVEN 128 events WHEN the 129th is added THEN it is dropped and counted`() {
        tracer.trace("root") { root ->
            repeat(128) { root.addEvent("e$it") }
            root.recordException(IllegalStateException())
        }

        assertEquals(128, trace.root.events.size)
        assertEquals(
            "e127",
            trace.root.events
                .last()
                .name,
        )
        assertEquals(1, trace.root.droppedEventCount)
        assertEquals(listOf("Event limit of 128 reached; event dropped"), limitMessages())
    }

    @Test
    fun `GIVEN 64 attributes WHEN an existing key is overwritten THEN it takes no new slot`() {
        tracer.trace("root") { root ->
            repeat(64) { root.setAttribute("k$it", it) }
            root.setAttribute("k0", "replaced")
        }

        assertEquals("replaced", trace.root.attributes["k0"])
        assertEquals(64, trace.root.attributes.size)
        assertEquals(0, trace.root.droppedAttributeCount)
        // Overwriting keeps the key's original position.
        assertEquals(
            "k0",
            trace.root.attributes.keys
                .first(),
        )
    }

    // Text budget

    @Test
    fun `GIVEN an attribute WHEN overwritten THEN only the difference is charged and a smaller value gives budget back`() {
        val root = tracer.startTrace("root")
        val start = root.budgetUsed

        root.setAttribute("key", "long value")
        assertEquals(start + 3 + 10, root.budgetUsed)
        root.setAttribute("key", "v")
        assertEquals(start + 3 + 1, root.budgetUsed)
        root.setAttribute("key", "a longer value")
        assertEquals(start + 3 + 14, root.budgetUsed)
        // A non-string value costs only its key, so the string's text goes back.
        root.setAttribute("key", 42)
        assertEquals(start + 3, root.budgetUsed)
    }

    @Test
    fun `GIVEN one attribute updated many times WHEN the budget is checked THEN it never grows and nothing is dropped`() {
        val root = tracer.startTrace("root")
        root.setAttribute("progress", "x".repeat(TraceLimits.MAX_STRING_VALUE_LENGTH))
        val used = root.budgetUsed

        // Cumulative charging would exhaust the whole budget well before this many updates.
        repeat(500) { root.setAttribute("progress", "y".repeat(TraceLimits.MAX_STRING_VALUE_LENGTH)) }
        assertEquals(used, root.budgetUsed)
        root.end()

        assertEquals("y".repeat(TraceLimits.MAX_STRING_VALUE_LENGTH), trace.root.attributes["progress"])
        assertEquals(0, trace.root.droppedAttributeCount)
        assertTrue(telemeter.diagnostics.isEmpty())
    }

    @Test
    fun `GIVEN an overwrite that doesn't fit WHEN set THEN the old value stays and writes that don't grow are still accepted`() {
        val root = tracer.startTrace("root")
        val child = root.startChild("c")
        child.fillBudget(leaving = 5)
        child.setAttribute("k", "ab")
        assertEquals(2, child.budgetLeft)

        // Growing from 3 to 8 chars needs 5, and only 2 are left, so the write is dropped and the old value stays.
        child.setAttribute("k", "abcdefg")
        // A replacement that doesn't grow always fits, and a smaller one gives budget back.
        child.setAttribute("k", "xy")
        child.setAttribute("k", "a")
        assertEquals(3, child.budgetLeft)
        // There is no cutoff: a new attribute that fits what's left is accepted.
        child.setAttribute("n", true)

        assertEquals(2, child.budgetLeft)
        child.end()
        root.end()
        val exported = trace.spans.single { it.name == "c" }
        assertEquals(mapOf("k" to "a", "n" to true), exported.attributes.filterKeys { !it.startsWith("f") })
        assertEquals(1, exported.droppedAttributeCount)
    }

    @Test
    fun `GIVEN a span whose budget is nearly used WHEN a write doesn't fit THEN only that write is dropped and other spans are unaffected`() {
        val root = tracer.startTrace("root")
        val full = root.startChild("full")
        full.fillBudget(leaving = 10)

        full.setAttribute("big", "x".repeat(50))
        // Each of these fits what's left of the span's own budget, so they are kept.
        full.setAttribute("a", "b")
        full.addEvent("e")
        // 10 chars no longer fit: the status keeps its kind and loses its description.
        full.setStatus(SpanStatus.Error("0123456789"))
        // A sibling has its own budget, so the same write that was dropped on `full` fits there.
        val sibling = root.startChild("sibling")
        sibling.setAttribute("big", "x".repeat(50))
        sibling.end()
        full.end()
        root.end()

        val exportedFull = trace.spans.single { it.name == "full" }
        assertEquals("b", exportedFull.attributes["a"])
        assertTrue("big" !in exportedFull.attributes)
        assertEquals(1, exportedFull.droppedAttributeCount)
        assertEquals(listOf("e"), exportedFull.events.map { it.name })
        assertEquals(SpanStatus.Error(null), exportedFull.status)
        val exportedSibling = trace.spans.single { it.name == "sibling" }
        assertEquals("x".repeat(50), exportedSibling.attributes["big"])
        assertEquals(0, exportedSibling.droppedAttributeCount)
        // Nothing was ended early or dropped as a span.
        assertTrue(trace.spans.none { it.isTruncated })
        assertEquals(0, trace.droppedSpanCount)
        assertEquals(listOf("Span text budget exhausted; attribute dropped"), limitMessages())
    }

    @Test
    fun `GIVEN a status description WHEN replaced THEN only the new one is charged`() {
        val root = tracer.startTrace("root")
        val start = root.budgetUsed

        root.setStatus(SpanStatus.Error("a long description"))
        assertEquals(start + 18, root.budgetUsed)
        root.setStatus(SpanStatus.Error("short"))
        assertEquals(start + 5, root.budgetUsed)
        root.setStatus(SpanStatus.Ok)
        assertEquals(start, root.budgetUsed)
        repeat(500) { root.setStatus(SpanStatus.Error("x".repeat(TraceLimits.MAX_DESCRIPTION_LENGTH))) }
        assertEquals(start + TraceLimits.MAX_DESCRIPTION_LENGTH, root.budgetUsed)
    }

    @Test
    fun `GIVEN an HTTP response code WHEN a later response replaces it THEN the automatic description is given back`() {
        val root = tracer.startTrace("root")
        val request = root.startHttpRequest("GET", "https://h/p")
        val start = request.budgetUsed

        request.setResponse(503)
        assertEquals(start + "HTTP 503".length, request.budgetUsed)
        request.setResponse(200)
        assertEquals(start, request.budgetUsed)
    }

    @Test
    fun `GIVEN each kind of write WHEN accepted THEN it is charged its normalized text`() {
        val root = tracer.startTrace("root")
        assertEquals(4, root.budgetUsed)

        root.addEvent("hit")
        assertEquals(4 + 3, root.budgetUsed)
        root.recordException(IllegalStateException("not charged"))
        assertEquals(7 + "exception".length, root.budgetUsed)
        root.setStatus(SpanStatus.Error("bad"))
        assertEquals(16 + 3, root.budgetUsed)
        // Replacing the status gives its description back.
        root.setStatus(SpanStatus.Ok)
        assertEquals(16, root.budgetUsed)
        root.setAttribute("enum", Mode.GRID)
        assertEquals(16 + 4 + 4, root.budgetUsed)
        // Truncated text is charged at its stored length.
        root.setAttribute("k", "x".repeat(5_000))
        assertEquals(24 + 1 + 1_024, root.budgetUsed)
        // A child's start text and initial attributes are charged to the child, not to its parent.
        val child = root.startChild("child", mapOf("a" to "bc"))
        assertEquals(5 + 1 + 2, child.budgetUsed)
        val request = root.startHttpRequest("get", "https://h/p?token=t")
        assertEquals("HTTP GET".length + "GET".length + "https://h/p?token=REDACTED".length, request.budgetUsed)
        assertEquals(24 + 1 + 1_024, root.budgetUsed)
        // Ending the trace builds snapshots, which charges nothing.
        val before = root.budgetUsed
        root.end()
        assertEquals(before, root.budgetUsed)
    }

    @Test
    fun `GIVEN rejected writes WHEN they are dropped THEN they consume no budget`() {
        val root = tracer.startTrace("root")
        repeat(64) { root.setAttribute("k$it", true) }
        val before = root.budgetUsed

        root.setAttribute("unsupported", Any())
        root.setAttribute("k".repeat(129), "oversized key")
        root.setAttribute("over the limit", "value")
        root.setAttribute("null", null)

        assertEquals(before, root.budgetUsed)
        root.end()
        assertEquals(3, trace.root.droppedAttributeCount)
    }

    @Test
    fun `GIVEN a status description that doesn't fit WHEN set THEN the span reads Error(null)`() {
        val root = tracer.startTrace("root")
        root.fillBudget(leaving = 3)

        root.setStatus(SpanStatus.Error("four"))
        assertEquals(3, root.budgetLeft)
        root.end()

        assertEquals(SpanStatus.Error(null), trace.root.status)
        assertEquals(listOf("Span text budget exhausted; status description dropped"), limitMessages())
    }

    @Test
    fun `GIVEN an exhausted budget WHEN writing THEN its attributes and events are dropped but children and free writes are unaffected`() {
        val root = tracer.startTrace("root")
        root.fillBudget(leaving = 0)
        assertEquals(TraceLimits.SPAN_TEXT_BUDGET, root.budgetUsed)

        root.setAttribute("x", "y")
        // Even a non-string value is charged for its key, so it's dropped too.
        root.setAttribute("n", 1)
        root.addEvent("z")
        // A child has its own budget, so it starts and records normally.
        val child = root.startChild("c")
        child.setAttribute("ok", "yes")
        child.end()
        // A status with no description costs nothing, so it's still accepted.
        root.setStatus(SpanStatus.Cancelled)
        root.end()

        assertNotEquals(Ids.INVALID_SPAN_ID, child.spanId)
        assertEquals("yes", trace.spans.single { it.name == "c" }.attributes["ok"])
        assertTrue("x" !in trace.root.attributes && "n" !in trace.root.attributes)
        assertEquals(2, trace.root.droppedAttributeCount)
        assertEquals(1, trace.root.droppedEventCount)
        assertEquals(0, trace.droppedSpanCount)
        assertEquals(SpanStatus.Cancelled, trace.root.status)
    }

    /** Every budgeted string a span's snapshot holds, automatic HTTP descriptions included. */
    private fun budgetedText(span: SpanData): Int =
        span.name.length +
            (span.http?.let { it.method.length + it.url.length } ?: 0) +
            span.attributes.entries.sumOf { (k, v) -> k.length + ((v as? String)?.length ?: 0) } +
            span.events.sumOf { it.name.length } +
            ((span.status as? SpanStatus.Error)?.description?.length ?: 0)

    @Test
    fun `GIVEN a trace that reaches every count limit WHEN exported THEN each limit holds before the budget runs out`() {
        // Short text, so the count limits are reached first: 1 + 255 spans of 1-char names, 64 attributes and 128
        // events on one span, all well inside the budget.
        val root = tracer.startTrace("r")
        repeat(64) { root.setAttribute("k$it", true) }
        root.setAttribute("over", true)
        repeat(128) { root.addEvent("e") }
        root.addEvent("over")
        val children = List(255) { root.startChild("c") }
        val overLimit = root.startChild("over")
        children.forEach { it.end() }
        root.end()

        assertEquals(Ids.INVALID_SPAN_ID, overLimit.spanId)
        assertEquals(TraceLimits.MAX_SPANS, trace.spans.size)
        assertEquals(1, trace.droppedSpanCount)
        assertEquals(64, trace.root.attributes.size)
        assertEquals(1, trace.root.droppedAttributeCount)
        assertEquals(128, trace.root.events.size)
        assertEquals(1, trace.root.droppedEventCount)
        assertTrue(root.budgetUsed < TraceLimits.SPAN_TEXT_BUDGET)
    }

    @Test
    fun `GIVEN a span with every kind of text WHEN its budget runs out THEN each kind is exported and none uncharged`() {
        // Every kind of budgeted text is accepted before the budget runs out: the HTTP span's name, method and URL, an
        // explicit and an automatic status description, an event, and attribute keys and values.
        val root = tracer.startTrace("root")
        val request = root.startHttpRequest("get", "https://h/" + "p".repeat(400))
        request.setStatus(SpanStatus.Error("explicit"))
        request.setResponse(503) // automatic Error("HTTP 503"); the explicit status wins
        request.addEvent("event")
        // Each attribute charges about 400 chars, so the budget runs out partway through.
        repeat(100) { i -> request.setAttribute("key$i", "v".repeat(400)) }
        request.end()
        root.end()

        val http = trace.spans.single { it.http != null }
        assertEquals(SpanStatus.Error("explicit"), http.status)
        assertEquals(listOf("event"), http.events.map { it.name })
        assertTrue(http.attributes.isNotEmpty())
        assertTrue(http.attributes.size < 100)
        assertTrue(http.droppedAttributeCount > 0)
        assertTrue(request.budgetUsed <= TraceLimits.SPAN_TEXT_BUDGET)
        // Nothing exported is uncharged.
        assertTrue(budgetedText(http) <= request.budgetUsed, "budgeted text ${budgetedText(http)} > charged ${request.budgetUsed}")
    }

    @Test
    fun `GIVEN an exhausted budget WHEN an HTTP error response arrives THEN its description is dropped, not added uncharged`() {
        val root = tracer.startTrace("root")
        val request = root.startHttpRequest("GET", "https://h/a")
        request.fillBudget(leaving = 0)

        request.setResponse(500)
        request.end()
        root.end()

        val http = trace.spans.single { it.http != null }
        assertEquals(SpanStatus.Error(null), http.status)
        assertEquals(500, http.http?.statusCode)
        assertEquals(TraceLimits.SPAN_TEXT_BUDGET, budgetedText(http))
    }

    // Immutability

    @Test
    fun `GIVEN an input map WHEN mutated after the start THEN the span is unaffected`() {
        val input = mutableMapOf<String, Any?>("a" to 1)

        val root = tracer.startTrace("root", input)
        input["a"] = 2
        input["b"] = 3
        root.end()

        assertEquals(mapOf("a" to 1L), trace.root.attributes)
    }

    @Test
    fun `GIVEN a snapshot WHEN the span is written later or a collection is cast and mutated THEN the snapshot never changes`() {
        val root = tracer.startTrace("root")
        val child = root.startChild("child", mapOf("a" to 1))
        child.addEvent("first")
        child.end()
        val captured =
            run {
                root.end()
                trace.spans.single { it.name == "child" }
            }

        child.setAttribute("late", true)
        child.addEvent("late")

        assertEquals(mapOf("a" to 1L), captured.attributes)
        assertEquals(listOf("first"), captured.events.map { it.name })
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (captured.attributes as MutableMap<String, Any>)["b"] = 2L
        }
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (captured.events as MutableList<SpanEvent>).clear()
        }
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (trace.spans as MutableList<SpanData>).removeAt(0)
        }
        assertThrows<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (captured.attributes as MutableMap<String, Any>).clear()
        }
        assertEquals(2, trace.spans.size)
    }

    @Test
    fun `GIVEN a map whose iteration throws WHEN passed as initial attributes THEN the span still starts`() {
        val hostile =
            object : AbstractMap<String, Any?>() {
                override val entries: Set<Map.Entry<String, Any?>> get() = throw ConcurrentModificationException()
            }

        tracer.trace("root", hostile) { root -> root.setAttribute("ok", true) }

        assertEquals(mapOf("ok" to true), trace.root.attributes)
    }

    // Review fixes

    @Test
    fun `GIVEN a Java map with a null key WHEN passed as initial attributes THEN the entry is skipped and the block runs`() {
        val javaMap =
            HashMap<String?, Any?>().apply {
                put(null, "value")
                put("ok", 1)
            }
        var ran = false

        @Suppress("UNCHECKED_CAST")
        tracer.trace("root", javaMap as Map<String, Any?>) { ran = true }

        assertTrue(ran)
        assertEquals(mapOf("ok" to 1L), trace.root.attributes)
    }

    @Test
    fun `GIVEN an initial map that throws mid-iteration WHEN a span starts THEN entries read are kept and a redacted diagnostic is reported`() {
        val failing =
            object : AbstractMap<String, Any?>() {
                override val entries: Set<Map.Entry<String, Any?>> =
                    object : AbstractSet<Map.Entry<String, Any?>>() {
                        override val size = 2

                        override fun iterator() =
                            iterator {
                                yield(SimpleEntry("ok", 1L as Any?))
                                throw ConcurrentModificationException("secret-token")
                            }
                    }
            }

        var ran = false
        tracer.trace("root", failing) { ran = true }

        assertTrue(ran)
        assertEquals(mapOf("ok" to 1L), trace.root.attributes)
        val diagnostic = telemeter.diagnostics.single { it.kind == ATTRIBUTES_UNREADABLE }
        assertTrue(diagnostic.message.contains("ConcurrentModificationException"))
        assertTrue(!diagnostic.message.contains("secret-token"))
        assertTrue(diagnostic.throwable?.message?.contains("secret-token") == false)
    }

    @Test
    fun `GIVEN an initial map that throws an Error WHEN a span starts THEN the Error propagates`() {
        val failing =
            object : AbstractMap<String, Any?>() {
                override val entries: Set<Map.Entry<String, Any?>> get() = throw OutOfMemoryError("boom")
            }

        assertThrows<OutOfMemoryError> { tracer.startTrace("root", failing) }
    }

    @Test
    fun `GIVEN a huge initial map WHEN a span starts THEN work and diagnostics stay bounded`() {
        var entriesRead = 0
        val huge =
            object : AbstractMap<String, Any?>() {
                override val entries: Set<Map.Entry<String, Any?>> =
                    object : AbstractSet<Map.Entry<String, Any?>>() {
                        override val size = 100_000

                        override fun iterator() =
                            iterator {
                                repeat(size) {
                                    entriesRead++
                                    yield(SimpleEntry("k$it", 'c' as Any?))
                                }
                            }
                    }
            }

        tracer.trace("root", huge) { }

        // Reading stops at a fixed bound, even though none of these entries could ever be stored.
        assertTrue(entriesRead <= TraceLimits.MAX_INITIAL_ENTRIES_READ + 1, "read $entriesRead entries")
        // Every entry is accounted for: the ones read and rejected, and the ones never read.
        assertEquals(100_000, trace.root.droppedAttributeCount)
        // One report per problem, not per entry.
        assertEquals(1, telemeter.diagnostics.count { it.kind == ATTRIBUTE_DROPPED })
        assertEquals(1, telemeter.diagnostics.count { it.kind == LIMIT_EXCEEDED })
    }

    @Test
    fun `GIVEN many identical problems in one operation WHEN pending THEN only one diagnostic is held`() {
        // Emission also dedupes, so this checks the per-operation bound directly: it's what keeps peak memory flat.
        val pending = PendingDiagnostics()

        repeat(10_000) { pending.report(ATTRIBUTE_DROPPED, "dropped", detail = "kotlin.Char") }
        pending.report(ATTRIBUTE_DROPPED, "dropped", detail = "java.lang.Object")

        assertEquals(2, pending.diagnostics?.size)
    }

    @Test
    fun `GIVEN an unsupported value under a URL-shaped key WHEN reported THEN the diagnostic omits the key`() {
        val key = "https://example.com/search?q=private-query"

        tracer.trace("root") { it.setAttribute(key, Any()) }

        val diagnostic = telemeter.diagnostics.single { it.kind == ATTRIBUTE_DROPPED }
        assertTrue("private-query" !in diagnostic.message && "example.com" !in diagnostic.message, diagnostic.message)
    }

    // Regressions from the review fixes

    @Test
    fun `GIVEN an ended span WHEN attributes that would be reported are written THEN nothing is reported`() {
        val root = tracer.startTrace("root")
        val child = root.startChild("child")
        child.end()

        child.setAttribute("unsupported", Any())
        child.setAttribute("long", "x".repeat(2_000))
        child.setAttribute("k".repeat(200), 1)
        root.end()

        assertTrue(telemeter.diagnostics.isEmpty(), telemeter.diagnostics.toString())
        assertTrue(
            trace.spans
                .single { it.name == "child" }
                .attributes
                .isEmpty(),
        )
    }

    @Test
    fun `GIVEN an initial map with unstorable entries mixed in WHEN a span starts THEN every storable entry is kept`() {
        // 10 unstorable entries first (null, unsupported type, over-long key), then 64 valid ones: all 64 fit.
        val map = LinkedHashMap<String, Any?>()
        repeat(4) { map["null$it"] = null }
        repeat(3) { map["unsupported$it"] = Any() }
        repeat(3) { map["k".repeat(200) + it] = 1 }
        repeat(64) { map["valid$it"] = it }

        tracer.trace("root", map) { }

        assertEquals(64, trace.root.attributes.size)
        assertTrue((0 until 64).all { "valid$it" in trace.root.attributes })
        // Nulls are skipped, not dropped; the 6 rejected entries are.
        assertEquals(6, trace.root.droppedAttributeCount)
    }

    @Test
    fun `GIVEN an initial map larger than the limit WHEN a span starts THEN every unread entry is counted as dropped`() {
        val map = LinkedHashMap<String, Any?>()
        repeat(100) { map["k$it"] = it }

        tracer.trace("root", map) { }

        assertEquals(64, trace.root.attributes.size)
        assertEquals(36, trace.root.droppedAttributeCount)
    }

    // Second review

    @Test
    fun `GIVEN 64 large entries then a small one WHEN a span starts THEN the small one is kept if it fits, as with setAttribute`() {
        val map = LinkedHashMap<String, Any?>()
        repeat(64) { map["k%02d".format(it)] = "v".repeat(1_024) }
        map["small"] = true

        tracer.trace("root", map) { }

        // Only some of the large entries fit the span's budget, and each later one needs more than what's left, but
        // there is still room for "small".
        val entryCost = "k00".length + TraceLimits.MAX_STRING_VALUE_LENGTH
        val fitting = (TraceLimits.SPAN_TEXT_BUDGET - "root".length) / entryCost
        assertEquals(true, trace.root.attributes["small"])
        assertEquals(fitting + 1, trace.root.attributes.size)
        assertEquals(64 - fitting, trace.root.droppedAttributeCount)
    }

    @Test
    fun `GIVEN a map that reports a huge size WHEN a span starts and more drops follow THEN the counter saturates instead of wrapping`() {
        val huge =
            object : AbstractMap<String, Any?>() {
                override val entries: Set<Map.Entry<String, Any?>> =
                    object : AbstractSet<Map.Entry<String, Any?>>() {
                        override val size = Int.MAX_VALUE

                        override fun iterator() =
                            iterator {
                                var i = 0
                                while (true) yield(SimpleEntry("k${i++}", 'c' as Any?))
                            }
                    }
            }

        tracer.trace("root", huge) { root -> root.setAttribute("another", Any()) }

        assertEquals(Int.MAX_VALUE, trace.root.droppedAttributeCount)
    }

    @Test
    fun `GIVEN a map past the scan bound WHEN reported THEN the diagnostic names the scan bound and leaves the count limit's slot free`() {
        val map = LinkedHashMap<String, Any?>()
        repeat(TraceLimits.MAX_INITIAL_ENTRIES_READ + 1) { map["n$it"] = null }

        tracer.trace("first", map) { }
        tracer.trace("second") { root -> repeat(65) { root.setAttribute("k$it", true) } }

        // Two distinct reports: the scan bound from the first trace, and the genuine count limit from the second.
        val messages = telemeter.diagnostics.filter { it.kind == LIMIT_EXCEEDED }.map { it.message }
        assertEquals(2, messages.size, messages.toString())
        assertTrue("${TraceLimits.MAX_INITIAL_ENTRIES_READ}" in messages[0] && "Attribute limit" !in messages[0], messages[0])
        assertTrue("Attribute limit of 64" in messages[1], messages[1])
    }

    @Test
    fun `GIVEN a map whose size understates its entries WHEN the scan bound is hit THEN the truncation is still counted and reported`() {
        val lying =
            object : AbstractMap<String, Any?>() {
                override val entries: Set<Map.Entry<String, Any?>> =
                    object : AbstractSet<Map.Entry<String, Any?>>() {
                        override val size = 1

                        override fun iterator() =
                            iterator {
                                repeat(
                                    TraceLimits.MAX_INITIAL_ENTRIES_READ,
                                ) { yield(SimpleEntry("n$it", null as Any?)) }
                                yield(SimpleEntry("valid", 1 as Any?))
                            }
                    }
            }

        tracer.trace("root", lying) { }

        // At least one entry was left unread: the iterator had more after the bound, whatever size claimed.
        assertTrue(trace.root.droppedAttributeCount >= 1)
        assertEquals(1, telemeter.diagnostics.count { it.kind == LIMIT_EXCEEDED })
    }
}
