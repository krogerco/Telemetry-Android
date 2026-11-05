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

import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.LIMIT_EXCEEDED
import com.kroger.telemetry.tracing.util.FakeTelemeter
import com.kroger.telemetry.tracing.util.TIMEOUT_SECONDS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A start that ends up inert reports only why it was dropped, and a late start reports nothing.
 *
 * Each limit is deduplicated separately, so a leaked diagnostic (e.g. a truncated URL) would be recorded alongside the
 * drop reason, and `single()` would fail.
 */
internal class SpanStartRejectionTest {
    private val telemeter = FakeTelemeter()
    private var sanitizerCalls = 0
    private var sanitizerBody: (String) -> String = { it }
    private val tracer =
        Tracer.build {
            telemeter = this@SpanStartRejectionTest.telemeter
            urlSanitizer =
                UrlSanitizer { url ->
                    sanitizerCalls++
                    sanitizerBody(url)
                }
        }

    private val longUrl = "https://example.com/" + "a".repeat(2 * TraceLimits.MAX_URL_LENGTH)

    @Test
    fun `GIVEN an ended parent WHEN an HTTP request starts THEN the sanitizer never runs and nothing is reported`() {
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")
        parent.end()

        val request = parent.startHttpRequest("GET", longUrl)

        assertEquals(Ids.INVALID_SPAN_ID, request.spanId)
        assertEquals(0, sanitizerCalls)
        assertTrue(telemeter.diagnostics.isEmpty())
    }

    @Test
    fun `GIVEN a full trace WHEN an HTTP request starts THEN the sanitizer never runs and only the drop is reported`() {
        val root = tracer.startTrace("root")
        repeat(TraceLimits.MAX_SPANS - 1) { root.startChild("child") }

        val request = root.startHttpRequest("GET", longUrl)
        root.end()

        assertEquals(Ids.INVALID_SPAN_ID, request.spanId)
        assertEquals(0, sanitizerCalls)
        val diagnostic = telemeter.diagnostics.single()
        assertEquals(LIMIT_EXCEEDED, diagnostic.kind)
        assertTrue(diagnostic.message.startsWith("Span limit"), diagnostic.message)
        assertEquals(1, telemeter.traces.single().droppedSpanCount)
    }

    @Test
    fun `GIVEN an ended parent WHEN a child starts with an attribute map THEN the map is never read and nothing is reported`() {
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")
        parent.end()
        val attributes = TouchTrackingMap()

        val child = parent.startChild("child", attributes)

        assertEquals(Ids.INVALID_SPAN_ID, child.spanId)
        assertFalse(attributes.touched, "the caller's map was read for a rejected start")
        assertTrue(telemeter.diagnostics.isEmpty())
    }

    @Test
    fun `GIVEN a full trace WHEN a child starts with an attribute map THEN the map is never read and only the drop is reported`() {
        val root = tracer.startTrace("root")
        repeat(TraceLimits.MAX_SPANS - 1) { root.startChild("child") }
        val attributes = TouchTrackingMap()

        val child = root.startChild("overflow", attributes)
        root.end()

        assertEquals(Ids.INVALID_SPAN_ID, child.spanId)
        assertFalse(attributes.touched, "the caller's map was read for a rejected start")
        val diagnostic = telemeter.diagnostics.single()
        assertTrue(diagnostic.message.startsWith("Span limit"), diagnostic.message)
        assertEquals(1, telemeter.traces.single().droppedSpanCount)
    }

    @Test
    fun `GIVEN a parent that ends while the sanitizer runs WHEN the start completes THEN it is inert and silent`() {
        val root = tracer.startTrace("root")
        val parent = root.startChild("parent")
        // Another thread ends the parent while the sanitizer waits. Monitors are reentrant, so ending it from this same
        // thread would pass even if the sanitizer ran under the trace lock; a second thread would instead block. The
        // outcome is captured in a flag and asserted outside tracing, which would swallow an exception thrown here.
        val endedWhileSanitizing = AtomicBoolean(false)
        sanitizerBody = {
            val ended = CountDownLatch(1)
            thread {
                parent.end()
                ended.countDown()
            }
            endedWhileSanitizing.set(ended.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            throw IllegalStateException("contains the raw URL")
        }

        val request = parent.startHttpRequest("GET", longUrl)
        root.end()

        assertTrue(endedWhileSanitizing.get(), "the sanitizer ran while the trace lock was held")

        assertEquals(Ids.INVALID_SPAN_ID, request.spanId)
        assertEquals(1, sanitizerCalls)
        assertTrue(telemeter.diagnostics.isEmpty())
        assertEquals(
            listOf("root", "parent"),
            telemeter.traces
                .single()
                .spans
                .map { it.name },
        )
    }

    @Test
    fun `GIVEN the span limit fills while the sanitizer runs WHEN the start completes THEN only the drop is reported`() {
        val root = tracer.startTrace("root")
        repeat(TraceLimits.MAX_SPANS - 2) { root.startChild("child") }
        // Takes the last slot after the early check passed; the over-long URL would report a truncation.
        sanitizerBody = { url ->
            root.startChild("filler")
            url
        }

        val request = root.startHttpRequest("GET", longUrl)
        root.end()

        assertEquals(Ids.INVALID_SPAN_ID, request.spanId)
        assertEquals(1, sanitizerCalls)
        val diagnostic = telemeter.diagnostics.single()
        assertTrue(diagnostic.message.startsWith("Span limit"), diagnostic.message)
        assertEquals(
            TraceLimits.MAX_SPANS,
            telemeter.traces
                .single()
                .spans.size,
        )
    }

    /** An empty attribute map that records whether anything looked at it. */
    private class TouchTrackingMap : AbstractMap<String, Any?>() {
        var touched = false
            private set

        override val entries: Set<Map.Entry<String, Any?>>
            get() {
                touched = true
                return emptySet()
            }

        override val size: Int
            get() {
                touched = true
                return 0
            }
    }
}
