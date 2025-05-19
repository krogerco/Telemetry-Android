/**
 * MIT License
 *
 * Copyright (c) 2021 The Kroger Co. All rights reserved.
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

package com.kroger.telemetry.relay

import com.kroger.telemetry.Event
import com.kroger.telemetry.Telemeter
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.util.FakeRelay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TelemeterLogExtensionsTest {
    private val captured = mutableListOf<Event>()
    private val fakeRelay =
        FakeRelay {
            captured.add(it)
        }

    private val message = "hello there"

    @AfterEach
    fun teardown() {
        captured.clear()
    }

    private fun TestScope.createTelemeter() =
        Telemeter.build(
            relays = listOf(fakeRelay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = backgroundScope),
        )

    @Test
    fun `log records significance passed to it`() =
        runTest {
            val telemeter = createTelemeter()
            telemeter.log(message = message, significance = Significance.ERROR)
            testScheduler.runCurrent()
            assertTrue(captured[0].facets[0] == Significance.ERROR)
        }

    @Test
    fun `tag is ignored if null`() =
        runTest {
            val telemeter = createTelemeter()
            telemeter.log(message = message, significance = Significance.ERROR)
            testScheduler.runCurrent()
            assertTrue(captured[0].description == message)
        }

    @Test
    fun `tag is prepended if present`() =
        runTest {
            val telemeter = createTelemeter()
            val tag = Telemeter.TAG
            telemeter.log(tag = tag, message = message, significance = Significance.ERROR)
            testScheduler.runCurrent()
            val expected = "$tag - $message"
            assertEquals(expected, captured[0].description)
        }

    @Test
    fun `v records verbose significant event`() =
        runTest {
            val telemeter = createTelemeter()
            telemeter.v(message = message)
            testScheduler.runCurrent()
            assertTrue(captured[0].facets[0] == Significance.VERBOSE)
        }

    @Test
    fun `d records debug significant event`() =
        runTest {
            val telemeter = createTelemeter()
            telemeter.d(message = message)
            testScheduler.runCurrent()
            assertTrue(captured[0].facets[0] == Significance.DEBUG)
        }

    @Test
    fun `i records informational significant event`() =
        runTest {
            val telemeter = createTelemeter()
            telemeter.i(message = message)
            testScheduler.runCurrent()
            assertTrue(captured[0].facets[0] == Significance.INFORMATIONAL)
        }

    @Test
    fun `w records warn significant event`() =
        runTest {
            val telemeter = createTelemeter()
            telemeter.w(message = message)
            testScheduler.runCurrent()
            assertTrue(captured[0].facets[0] == Significance.WARNING)
        }

    @Test
    fun `e records exceptional significant event`() =
        runTest {
            val telemeter = createTelemeter()
            telemeter.e(message = message)
            testScheduler.runCurrent()
            assertTrue(captured[0].facets[0] == Significance.ERROR)
        }

    @Test
    fun `wtf records internal_error significant event`() =
        runTest {
            val telemeter = createTelemeter()
            telemeter.wtf(message = message)
            testScheduler.runCurrent()
            assertTrue(captured[0].facets[0] == Significance.INTERNAL_ERROR)
        }

    @Test
    fun `throwable is not used if not specified`() =
        runTest {
            val telemeter = createTelemeter()
            telemeter.wtf(message = message)
            testScheduler.runCurrent()
            assertEquals(message, captured[0].description)
        }

    @Test
    fun `throwable is used if specified`() =
        runTest {
            val telemeter = createTelemeter()
            val exceptionMessage = "oh no"
            telemeter.wtf(message = message, throwable = IllegalStateException(exceptionMessage))
            testScheduler.runCurrent()
            val expectedMessage = "$message - $exceptionMessage"
            assertEquals(expectedMessage, captured[0].description)
        }
}
