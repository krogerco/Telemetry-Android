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
 * A copy of [original] that keeps what's needed to find the bug, its class name and stack trace, and drops the free
 * text: the message, any suppressed exceptions, and the cause chain. Recorded in place of a listener's exception, whose
 * message is the listener's own free text and can contain an unsanitized URL, a credential or user data.
 */
internal class RedactedThrowable(
    original: Throwable,
) : RuntimeException(redactedMessage(original), null, false, true) {
    /** The original exception's class, since this copy is always a [RedactedThrowable]. */
    val originalClassName: String = original.javaClass.name

    init {
        // A Throwable can override getStackTrace() to throw an exception or to return a malformed array (setStackTrace rejects null
        // elements). Either way the diagnostic must still be recorded, so a failure leaves the stack empty.
        try {
            stackTrace = original.stackTrace
        } catch (_: Exception) {
            stackTrace = emptyArray()
        }
    }

    private companion object {
        /**
         * Crash reporters show the runtime type (always RedactedThrowable) and the message, so the message carries the
         * original class name: the one piece of the original that's safe and needed to triage.
         */
        fun redactedMessage(original: Throwable): String = "${original.javaClass.name} (message redacted by tracing)"
    }
}
