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

package com.kroger.telemetry.tracing.util

import com.kroger.telemetry.tracing.ExperimentalTracingApi
import com.kroger.telemetry.tracing.RealtimeTracingApi
import com.kroger.telemetry.tracing.SpanData
import com.kroger.telemetry.tracing.SpanListener
import com.kroger.telemetry.tracing.SpanStart
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Logs every callback, with its thread, to [log]. Several listeners can share one log to check cross-listener order.
 * [onStartHook] runs inside `onStart` and its result is the state; by default the state is the span's name.
 */
internal class FakeSpanListener(
    private val id: String,
    val log: MutableList<Call> = CopyOnWriteArrayList(),
    var onStartHook: (SpanStart, String?) -> String? = { span, _ -> span.name },
    var onEndHook: (String, SpanData) -> Unit = { _, _ -> },
    var headersHook: (String) -> Map<String, String> = { emptyMap() },
) : SpanListener<String> {
    data class Call(
        val listener: String,
        val callback: String,
        val span: String,
        val parent: String? = null,
        val thread: Thread = Thread.currentThread(),
    ) {
        override fun toString(): String = "$listener.$callback($span${parent?.let { ", parent=$it" } ?: ""})"
    }

    val ends = CopyOnWriteArrayList<SpanData>()

    override fun onStart(
        span: SpanStart,
        parent: String?,
    ): String? {
        log += Call(id, "onStart", span.name, parent)
        return onStartHook(span, parent)
    }

    override fun onEnd(
        state: String,
        span: SpanData,
    ) {
        log += Call(id, "onEnd", state)
        ends += span
        onEndHook(state, span)
    }

    override fun propagationHeaders(state: String): Map<String, String> {
        log += Call(id, "headers", state)
        return headersHook(state)
    }
}

/**
 * A fake vendor SDK in the style of Dynatrace OneAgent: each action holds its parent, closing a parent closes its open
 * children, and every call on a finished action is recorded as a violation. [SpanListener] logic mirrors the
 * reference adapter: check `isFinished` before every call, report data at end, and leave in `finally`.
 */
internal class FakeVendor {
    val calls = CopyOnWriteArrayList<String>()
    val violations = CopyOnWriteArrayList<String>()

    inner class Action(
        val name: String,
        val parent: Action?,
    ) {
        private val children = CopyOnWriteArrayList<Action>()

        @Volatile
        var isFinished: Boolean = false
            private set

        init {
            parent?.children?.add(this)
            calls += "enter $name"
        }

        fun report(key: String) = call("report $name.$key")

        fun leave() {
            call("leave $name")
            close()
        }

        private fun close() {
            children.forEach { if (!it.isFinished) it.close() }
            isFinished = true
        }

        private fun call(what: String) {
            if (isFinished) violations += what else calls += what
        }
    }

    val listener =
        object : SpanListener<Action> {
            override fun onStart(
                span: SpanStart,
                parent: Action?,
            ): Action? {
                if (span.isRoot) return Action(span.name, parent = null)
                val open = parent?.takeUnless { it.isFinished } ?: return null
                return Action(span.name, open)
            }

            override fun onEnd(
                state: Action,
                span: SpanData,
            ) {
                try {
                    span.attributes.keys.forEach { key -> if (!state.isFinished) state.report(key) }
                    span.events.forEach { event -> if (!state.isFinished) state.report("event:${event.name}") }
                    if (!state.isFinished) state.report("status")
                } finally {
                    if (!state.isFinished) state.leave()
                }
            }
        }
}
