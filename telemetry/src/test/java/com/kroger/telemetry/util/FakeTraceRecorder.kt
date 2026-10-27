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

package com.kroger.telemetry.util

import com.kroger.telemetry.tracing.NetworkRequestTimingScope
import com.kroger.telemetry.tracing.NoOpNetworkRequestTimingScope
import com.kroger.telemetry.tracing.SpanHandle
import com.kroger.telemetry.tracing.SpanStatus
import com.kroger.telemetry.tracing.TraceRecorder
import kotlin.time.Duration

public class FakeTraceRecorder(
    override val recorderId: String = "fake",
) : TraceRecorder {
    public class FakeHandle(
        public val id: Int = 0,
    ) : SpanHandle

    public data class StartSpanCall(
        val name: String,
        val attributes: Map<String, Any?>,
        val parent: SpanHandle?,
        val handle: FakeHandle,
    )

    public data class SetAttributeCall(
        val handle: SpanHandle,
        val key: String,
        val value: Any?,
    )

    public data class AddEventCall(
        val handle: SpanHandle,
        val name: String,
    )

    public data class EndSpanCall(
        val handle: SpanHandle,
        val status: SpanStatus,
        val duration: Duration,
        val error: Throwable?,
    )

    public data class StartNetworkTimingCall(
        val requestTag: String,
        val url: String,
    )

    public val startSpanCalls: MutableList<StartSpanCall> = mutableListOf()
    public val setAttributeCalls: MutableList<SetAttributeCall> = mutableListOf()
    public val addEventCalls: MutableList<AddEventCall> = mutableListOf()
    public val endSpanCalls: MutableList<EndSpanCall> = mutableListOf()
    public val getNetworkContextCalls: MutableList<SpanHandle> = mutableListOf()
    public val startNetworkTimingCalls: MutableList<StartNetworkTimingCall> = mutableListOf()

    public var networkContext: String? = null
    public var fakeRequestTagHeaderName: String = ""
    public var networkTimingScope: NetworkRequestTimingScope = NoOpNetworkRequestTimingScope
    private var handleCounter = 0

    override fun startSpan(
        name: String,
        attributes: Map<String, Any?>,
        parent: SpanHandle?,
    ): SpanHandle {
        val handle = FakeHandle(handleCounter++)
        startSpanCalls.add(StartSpanCall(name, attributes, parent, handle))
        return handle
    }

    override fun setAttribute(
        span: SpanHandle,
        key: String,
        value: Any?,
    ) {
        setAttributeCalls.add(SetAttributeCall(span, key, value))
    }

    override fun addEvent(
        span: SpanHandle,
        name: String,
    ) {
        addEventCalls.add(AddEventCall(span, name))
    }

    override fun endSpan(
        span: SpanHandle,
        status: SpanStatus,
        duration: Duration,
        error: Throwable?,
    ) {
        endSpanCalls.add(EndSpanCall(span, status, duration, error))
    }

    override fun getNetworkTraceContext(span: SpanHandle): String? {
        getNetworkContextCalls.add(span)
        return networkContext
    }

    override fun getRequestTagHeaderName(): String = fakeRequestTagHeaderName

    override fun startNetworkRequestTiming(
        requestTag: String,
        url: String,
    ): NetworkRequestTimingScope {
        startNetworkTimingCalls.add(StartNetworkTimingCall(requestTag, url))
        return networkTimingScope
    }
}
