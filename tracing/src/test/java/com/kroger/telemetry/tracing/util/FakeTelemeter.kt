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

package com.kroger.telemetry.tracing.util

import com.kroger.telemetry.Event
import com.kroger.telemetry.Telemeter
import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.tracing.ExperimentalTracingApi
import com.kroger.telemetry.tracing.TraceFacet
import com.kroger.telemetry.tracing.TracingDiagnostic
import java.util.concurrent.CopyOnWriteArrayList

/** Records synchronously on the calling thread, so tests can assert right after the triggering call. */
internal class FakeTelemeter(
    private val onRecord: (Event) -> Unit = {},
) : Telemeter {
    val events = CopyOnWriteArrayList<Event>()

    val traces: List<TraceFacet> get() = events.flatMap { it.facets.filterIsInstance<TraceFacet>() }

    val diagnostics: List<TracingDiagnostic> get() = events.flatMap { it.facets.filterIsInstance<TracingDiagnostic>() }

    override fun record(
        event: Event,
        withFacets: List<Facet>?,
    ) {
        val merged =
            object : Event {
                override val description = event.description
                override val facets = event.facets + withFacets.orEmpty()
            }
        events += merged
        onRecord(merged)
    }
}
