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

/**
 * A trace rooted at a single span.
 *
 * [Trace] owns the [traceId] for the entire span tree and delegates all [Span]
 * operations to the [rootSpan]. This allows a [Trace] to be used anywhere a [Span]
 * is expected while providing a clear entry point for starting new traces via
 * [Telemeter.trace][com.kroger.telemetry.Telemeter.trace].
 *
 * Construction is restricted to the library via the `internal` constructor.
 *
 * @param traceId Unique identifier for this trace, shared by all child spans.
 * @param rootSpan The root [Span] that this trace delegates to.
 */
public class Trace internal constructor(
    override val traceId: String,
    public val rootSpan: Span,
) : Span by rootSpan
