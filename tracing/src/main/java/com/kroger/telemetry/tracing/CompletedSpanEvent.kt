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

import com.kroger.telemetry.Event
import com.kroger.telemetry.facet.DeveloperMetricsFacet
import com.kroger.telemetry.facet.Facet

/**
 * A telemetry [Event] emitted when a [CompletedSpan] is recorded.
 *
 * Carries a [CompletedSpanFacet] so that relays can extract span data
 * via facet type-checking.
 */
public class CompletedSpanEvent(
    public val completedSpan: CompletedSpan,
) : Event {
    override val description: String = "Span completed: ${completedSpan.name}"
    override val facets: List<Facet> = listOf(CompletedSpanFacet(completedSpan))
}

/**
 * A [DeveloperMetricsFacet] that exposes [CompletedSpan] data as a metrics event.
 *
 * The [eventName] is the span's name, and [compute] returns a map containing
 * the span's identifiers, duration, and attributes.
 */
public data class CompletedSpanFacet(
    val completedSpan: CompletedSpan,
) : DeveloperMetricsFacet {
    override val eventName: String = completedSpan.name
    override val compute: () -> Map<String, Any?> = {
        buildMap {
            put("spanId", completedSpan.spanId)
            put("traceId", completedSpan.traceId)
            put("duration_ms", completedSpan.duration.inWholeMilliseconds)
            putAll(completedSpan.attributes)
        }
    }
}
