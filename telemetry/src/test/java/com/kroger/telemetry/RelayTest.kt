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

package com.kroger.telemetry

import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.util.FakeEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
internal class RelayTest {
    @Test
    fun `GIVEN relay with filter in processing WHEN event processed THEN facets easily extracted`() =
        runBlockingTest {
            data class StringFacet(val value: String) : Facet
            data class IntFacet(val value: Int) : Facet

            class BasicRelay : Relay {
                var processedCount = 0
                override suspend fun process(event: Event) {
                    event.facets.filterIsInstance<StringFacet>().forEach(::process)
                }

                private fun process(facet: StringFacet) {
                    println(facet.value)
                    processedCount += 1
                }
            }

            val relay = BasicRelay()
            val event = FakeEvent(
                description = "test post please ignore",
                facets = listOf(StringFacet("test facet please confirm"), IntFacet(1)),
            )
            relay.process(event)
            assertEquals(1, relay.processedCount)
        }

    @Test
    fun `GIVEN typed relay WHEN event processed with relevant facet type THEN event is processed`() =
        runBlockingTest {
            data class StringFacet(val value: String) : Facet
            data class IntFacet(val value: Int) : Facet

            class BasicRelay : TypedRelay<StringFacet> {
                var processedCount = 0
                override val type: Class<StringFacet> = StringFacet::class.java

                override suspend fun processFacet(facet: StringFacet) {
                    println(facet.value)
                    processedCount += 1
                }
            }

            val relay = BasicRelay()
            val event = FakeEvent(
                description = "test post please ignore",
                facets = listOf(StringFacet("test facet please confirm"), IntFacet(1)),
            )
            relay.process(event)
            assertEquals(1, relay.processedCount)
        }

    @Test
    fun `GIVEN typed relay WHEN event processed without relevant facet type THEN event is not processed`() =
        runBlockingTest {
            data class StringFacet(val value: String) : Facet
            data class IntFacet(val value: Int) : Facet

            class BasicRelay : TypedRelay<StringFacet> {
                var processedCount = 0
                override val type: Class<StringFacet> = StringFacet::class.java

                override suspend fun processFacet(facet: StringFacet) {
                    println(facet.value)
                    processedCount += 1
                }
            }

            val relay = BasicRelay()
            val event = FakeEvent(
                description = "test post please ignore",
                facets = listOf(IntFacet(1)),
            )
            relay.process(event)
            assertEquals(0, relay.processedCount)
        }

    @Test
    fun `GIVEN facet to use in typed relay WHEN defining class THEN helper function is useful as delegate`() {
        data class TestFacet(val passed: Boolean) : Facet

        var passed = false

        class TestTypedRelay :
            TypedRelay<TestFacet> by Relay.buildTypedRelay({ facet -> passed = facet.passed })

        val passingFacet = TestFacet(passed = true)
        runBlockingTest {
            TestTypedRelay().process(FakeEvent(facets = listOf(passingFacet)))
        }

        assertTrue(passed)
    }
}
