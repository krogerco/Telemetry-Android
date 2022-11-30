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

package com.kroger.telemetry.android.relay

import android.widget.Toast
import com.kroger.telemetry.Event
import com.kroger.telemetry.android.facet.ToastFacet
import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.facet.Significance
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class ToastRelayTest {
    private class FakeToaster : Toaster {
        var didToast = false
        var fakeFunToast: (String, Int) -> Unit = { _, _ -> didToast = true }
        override suspend fun toast(message: String, length: Int) = fakeFunToast(message, length)
    }

    private data class TestConfig(
        val toaster: FakeToaster = FakeToaster(),
        override var toastLength: Int = Toast.LENGTH_SHORT,
        override var enabled: Boolean = true,
        override var toastSignificantEvents: Boolean = false,
        override var minimumSignificance: Significance = Significance.ERROR,
    ) : ToastRelay.Configuration

    private fun TestConfig.getRelay(): ToastRelay =
        ToastRelay(
            toaster = toaster,
            this,
        )

    private val fakeMessage = "fake"
    private val fakeToastFacet = ToastFacet(fakeMessage)

    @Test
    fun `GIVEN toast relay disabled WHEN event received THEN nothing is toasted`() =
        runBlockingTest {
            val config = TestConfig().copy(enabled = false)
            val relay = config.getRelay()

            relay.process(
                object : Event {
                    override val facets: List<Facet> = listOf(fakeToastFacet)
                },
            )

            assertFalse(config.toaster.didToast)
        }

    @Test
    fun `GIVEN toast relay WHEN event received with toast facet THEN facet is toasted`() =
        runBlockingTest {
            val config = TestConfig()
            val relay = config.getRelay()

            relay.process(
                object : Event {
                    override val facets: List<Facet> = listOf(fakeToastFacet)
                },
            )

            assertTrue(config.toaster.didToast)
        }

    @Test
    fun `GIVEN toast relay configured to toast all WHEN event received no significance THEN event is not toasted`() =
        runBlockingTest {
            val config = TestConfig().copy(
                toastSignificantEvents = true,
                minimumSignificance = Significance.ERROR,
            )
            val relay = config.getRelay()

            relay.process(
                object : Event {
                    override val facets: List<Facet> = listOf()
                },
            )

            assertFalse(config.toaster.didToast)
        }

    @Test
    fun `GIVEN toast relay configured to toast all WHEN event received with lower than minimum significance THEN event is not toasted`() =
        runBlockingTest {
            val config = TestConfig().copy(
                toastSignificantEvents = true,
                minimumSignificance = Significance.ERROR,
            )
            val relay = config.getRelay()

            relay.process(
                object : Event {
                    override val facets: List<Facet> = listOf(Significance.DEBUG)
                },
            )

            assertFalse(config.toaster.didToast)
        }

    @Test
    fun `GIVEN toast relay configured to toast all WHEN event received with minimum significance THEN event is toasted`() =
        runBlockingTest {
            val config = TestConfig().copy(
                toastSignificantEvents = true,
                minimumSignificance = Significance.ERROR,
            )
            val relay = config.getRelay()

            relay.process(
                object : Event {
                    override val facets: List<Facet> = listOf(Significance.ERROR)
                },
            )

            assertTrue(config.toaster.didToast)
        }

    @Test
    fun `GIVEN toast relay configured with bad length WHEN toasting THEN uses length short as default`() =
        runBlockingTest {
            val toaster = FakeToaster()
            var lengthUsed = 42
            toaster.fakeFunToast = { _, length -> lengthUsed = length }
            val config = TestConfig().copy(toaster = toaster, toastLength = lengthUsed)
            val relay = config.getRelay()

            relay.process(
                object : Event {
                    override val facets: List<Facet> = listOf(fakeToastFacet)
                },
            )

            assertEquals(Toast.LENGTH_SHORT, lengthUsed)
        }

    @Test
    fun `GIVEN an enabled toast relay WHEN disabled THEN toasts will not be shown`() =
        runBlockingTest {
            val config = TestConfig().copy(enabled = true)
            val relay = config.getRelay()

            config.enabled = false

            relay.process(
                object : Event {
                    override val facets: List<Facet> = listOf(fakeToastFacet)
                },
            )

            assertFalse(config.toaster.didToast)
        }

    @Test
    fun `GIVEN config with mutable backing data WHEN backing data is changed THEN config reflects update`() {
        val mutableBackingInstance = mutableListOf(false)

        class MutableConfig(private val mutableBackingProp: List<Boolean>) :
            ToastRelay.Configuration by ToastRelay.Configuration.Default() {
            override var enabled: Boolean
                get() = mutableBackingProp.first()
                set(_) = Unit
        }

        val config = MutableConfig(mutableBackingInstance)
        assertFalse(config.enabled)

        mutableBackingInstance[0] = true
        assertTrue(config.enabled)
    }
}
