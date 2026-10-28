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

import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.ATTRIBUTE_DROPPED
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.LIMIT_EXCEEDED
import com.kroger.telemetry.tracing.util.FakeTelemeter
import com.kroger.telemetry.tracing.util.fillBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Diagnostics are reported once per tracer for each (source, kind, detail, exception class). */
internal class DiagnosticDedupTest {
    private val telemeter = FakeTelemeter()
    private val tracer = Tracer.build { telemeter = this@DiagnosticDedupTest.telemeter }

    private fun limitMessages(): List<String> = telemeter.diagnostics.filter { it.kind == LIMIT_EXCEEDED }.map { it.message }

    @Test
    fun `GIVEN one limit hit early WHEN a different limit is hit in a later trace THEN both are reported`() {
        tracer.trace("first") { root -> root.setAttribute("big", "x".repeat(2_000)) }

        tracer.trace("second") { root ->
            root.fillBudget(leaving = 0)
            root.addEvent("dropped")
        }

        assertEquals(
            listOf("Attribute value truncated to 1024 chars", "Text budget exhausted; event dropped"),
            limitMessages(),
        )
    }

    @Test
    fun `GIVEN the same limit WHEN hit repeatedly across traces THEN it is reported once per tracer`() {
        repeat(3) {
            tracer.trace("root") { root ->
                root.setAttribute("a", "x".repeat(2_000))
                root.setAttribute("b", "y".repeat(2_000))
            }
        }
        val other = Tracer.build { telemeter = this@DiagnosticDedupTest.telemeter }
        other.trace("root") { root -> root.setAttribute("a", "x".repeat(2_000)) }

        // Once for the first tracer, once more for the second: deduplication is per tracer.
        assertEquals(2, limitMessages().size)
    }

    @Test
    fun `GIVEN budget drops of different items WHEN hit THEN they share one report`() {
        tracer.trace("root") { root ->
            // Fill from a child, so the root keeps free attribute slots and only the budget can reject.
            root.startChild("filler").apply {
                fillBudget(leaving = 0)
                end()
            }
            root.setAttribute("k", "v")
            root.addEvent("e")
            root.startChild("c")
            root.setStatus(SpanStatus.Error("d"))
        }

        // The budget is one limit, whatever it drops.
        assertEquals(listOf("Text budget exhausted; attribute dropped"), limitMessages())
    }

    @Test
    fun `GIVEN every limit WHEN each is hit THEN each is reported once`() {
        val sanitizerLongUrl =
            Tracer.build {
                telemeter = this@DiagnosticDedupTest.telemeter
                urlSanitizer = UrlSanitizer.None
            }
        sanitizerLongUrl.trace("root") { root ->
            root.startHttpRequest("m".repeat(20), "https://h/" + "p".repeat(2_000)).end()
        }
        tracer.trace("n".repeat(300)) { root ->
            root.addEvent("e".repeat(300))
            root.setAttribute("k".repeat(200), 1)
            root.setAttribute("v", "x".repeat(2_000))
            root.setStatus(SpanStatus.Error("d".repeat(600)))
            repeat(64) { root.setAttribute("a$it", true) }
            repeat(129) { root.addEvent("e") }
        }
        tracer.trace("spans") { root -> repeat(256) { root.startChild("c") } }
        tracer.trace("scan", (0..TraceLimits.MAX_INITIAL_ENTRIES_READ).associate { "n$it" to null }) { }
        tracer.trace("budget") { root ->
            root.fillBudget(leaving = 0)
            root.addEvent("e")
        }

        assertEquals(Limit.entries.size, limitMessages().size)
        assertEquals(limitMessages().size, limitMessages().toSet().size)
    }

    @Test
    fun `GIVEN two unsupported types WHEN each is set repeatedly THEN each type is reported once`() {
        tracer.trace("root") { root ->
            repeat(3) { root.setAttribute("list$it", listOf(it)) }
            repeat(3) { root.setAttribute("char$it", 'c') }
        }

        val dropped = telemeter.diagnostics.filter { it.kind == ATTRIBUTE_DROPPED }
        assertEquals(2, dropped.size)
        assertEquals(
            6,
            telemeter.traces
                .single()
                .root.droppedAttributeCount,
        )
    }
}
