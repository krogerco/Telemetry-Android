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

package com.kroger.telemetry.firebase

import com.kroger.telemetry.Event
import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.Significance
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

internal class FirebaseCrashlyticsRelayTest {

    private val mockCrashlytics = mockk<CrashlyticsWrapper>(relaxed = true)
    private val relay = FirebaseCrashlyticsRelay(mockCrashlytics)

    @Test
    fun `events with significance VERBOSE or higher should be logged to crashlytics, and lower should not be logged`() {
        runBlocking {
            relay.process(TestEvent("Testing VERBOSE", Significance.VERBOSE))
            relay.process(TestEvent("Testing DEBUG", Significance.DEBUG))
            relay.process(TestEvent("Testing INFORMATIONAL", Significance.INFORMATIONAL))
            relay.process(TestEvent("Testing WARNING", Significance.WARNING))
            relay.process(TestEvent("Testing ERROR", Significance.ERROR))
            relay.process(TestEvent("Testing INTERNAL_ERROR", Significance.INTERNAL_ERROR))
        }

        verify {
            mockCrashlytics.log("Testing DEBUG")
            mockCrashlytics.log("Testing INFORMATIONAL")
            mockCrashlytics.log("Testing WARNING")
            mockCrashlytics.log("Testing ERROR")
            mockCrashlytics.log("Testing INTERNAL_ERROR")
        }

        verify(inverse = true) {
            mockCrashlytics.log("Testing VERBOSE")
        }
    }

    @Test
    fun `given CrashlyticsKey facets, should record custom keys to Crashlytics for each type`() {
        runBlocking {
            relay.process(
                TestEvent(
                    keys = arrayOf(
                        CrashlyticsKey.build("STRING", "hello-crashlytics"),
                        CrashlyticsKey.build("BOOLEAN", true),
                        CrashlyticsKey.build("INT", 42),
                        CrashlyticsKey.build("LONG", 42L),
                        CrashlyticsKey.build("FLOAT", 4.2f),
                        CrashlyticsKey.build("DOUBLE", 4.2),
                    ),
                ),
            )
        }

        verify {
            mockCrashlytics.setCustomKey("STRING", "hello-crashlytics")
            mockCrashlytics.setCustomKey("BOOLEAN", true)
            mockCrashlytics.setCustomKey("INT", 42)
            mockCrashlytics.setCustomKey("LONG", 42L)
            mockCrashlytics.setCustomKey("FLOAT", 4.2f)
            mockCrashlytics.setCustomKey("DOUBLE", 4.2)
        }
    }

    @Test
    fun `given Failure facets, should record only those with non-null throwables to Crashlytics`() {
        val e1 = RuntimeException("Oh no")
        val e2 = RuntimeException("Oh no 2")
        runBlocking {
            listOf(
                TestEvent(throwable = e1),
                TestEvent(throwable = e2),
                TestEvent(throwable = null),
            ).forEach {
                relay.process(it)
            }
        }

        verify(exactly = 1) {
            mockCrashlytics.recordException(e1)
            mockCrashlytics.recordException(e2)
        }
    }

    @Test
    fun `given CrashlyticsKey, Significance and Failure facets, should process in the correct order`() {
        val testMessage = "Testing ordering"
        val e1 = RuntimeException("Oh no")
        val key = "some key"
        val value = "some value"
        val event = TestEvent(
            message = testMessage,
            significance = Significance.ERROR,
            throwable = e1,
            keys = arrayOf(CrashlyticsKey.build(key, value)),
        )

        runBlocking { relay.process(event) }

        verifySequence {
            mockCrashlytics.setCustomKey(key, value)
            mockCrashlytics.log(testMessage)
            mockCrashlytics.recordException(e1)
        }
    }

    private class TestEvent(
        private val message: String? = null,
        private val significance: Significance? = null,
        private val throwable: Throwable? = null,
        private vararg val keys: CrashlyticsKey = emptyArray(),
    ) : Event {
        override val description: String
            get() = message ?: ""

        override val facets: List<Facet>
            get() = (
                listOf(significance) +
                    keys +
                    throwable?.let { Failure(throwable = it) }
                )
                .filterNotNull()
    }
}
