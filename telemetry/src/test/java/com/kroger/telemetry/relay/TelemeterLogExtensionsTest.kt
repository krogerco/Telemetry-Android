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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
internal class TelemeterLogExtensionsTest {
    private val scope = TestCoroutineScope()

    private val captured = mutableListOf<Event>()
    private val fakeRelay = FakeRelay {
        captured.add(it)
    }
    private val telemeter = Telemeter.build(
        relays = listOf(fakeRelay),
        flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
    )

    private val message = "hello there"

    @AfterEach
    fun teardown() {
        captured.clear()
        scope.cleanupTestCoroutines()
    }

    @Test
    fun `log records significance passed to it`() {
        telemeter.log(message = message, significance = Significance.ERROR)
        assertTrue(captured[0].facets[0] == Significance.ERROR)
    }

    @Test
    fun `tag is ignored if null`() {
        telemeter.log(message = message, significance = Significance.ERROR)
        assertTrue(captured[0].description == message)
    }

    @Test
    fun `tag is prepended if present`() {
        val tag = Telemeter.TAG
        telemeter.log(tag = tag, message = message, significance = Significance.ERROR)

        val expected = "$tag - $message"
        assertEquals(expected, captured[0].description)
    }

    @Test
    fun `v records verbose significant event`() {
        telemeter.v(message = message)
        assertTrue(captured[0].facets[0] == Significance.VERBOSE)
    }

    @Test
    fun `d records debug significant event`() {
        telemeter.d(message = message)
        assertTrue(captured[0].facets[0] == Significance.DEBUG)
    }

    @Test
    fun `i records informational significant event`() {
        telemeter.i(message = message)
        assertTrue(captured[0].facets[0] == Significance.INFORMATIONAL)
    }

    @Test
    fun `w records warn significant event`() {
        telemeter.w(message = message)
        assertTrue(captured[0].facets[0] == Significance.WARNING)
    }

    @Test
    fun `e records exceptional significant event`() {
        telemeter.e(message = message)
        assertTrue(captured[0].facets[0] == Significance.ERROR)
    }

    @Test
    fun `wtf records internal_error significant event`() {
        telemeter.wtf(message = message)
        assertTrue(captured[0].facets[0] == Significance.INTERNAL_ERROR)
    }

    @Test
    fun `throwable is not used if not specified`() {
        telemeter.wtf(message = message)
        assertEquals(message, captured[0].description)
    }

    @Test
    fun `throwable is used if specified`() {
        val exceptionMessage = "oh no"
        telemeter.wtf(message = message, throwable = IllegalStateException(exceptionMessage))

        val expectedMessage = "$message - $exceptionMessage"
        assertEquals(expectedMessage, captured[0].description)
    }
}
