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

import com.google.firebase.analytics.FirebaseAnalytics
import com.kroger.telemetry.Event
import com.kroger.telemetry.facet.DeveloperMetricsFacet
import com.kroger.telemetry.facet.Facet
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Test

internal class FirebaseAnalyticsRelayTest {
    private val mockFirebaseAnalytics: FirebaseAnalytics = mockk()
    private val firebaseAnalyticsRelay = FirebaseAnalyticsRelay(mockFirebaseAnalytics)

    private val fakeName = "event_name"

    private inner class FakeFirebaseAnalyticsFacet : DeveloperMetricsFacet {
        override val eventName: String = fakeName
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `GIVEN event with firebase facet WHEN recorded THEN name matches in logged event`() =
        runBlockingTest {
            every { mockFirebaseAnalytics.logEvent(any(), any()) } just runs

            firebaseAnalyticsRelay.process(
                object : Event {
                    override val facets: List<Facet> = listOf(FakeFirebaseAnalyticsFacet())
                },
            )

            verify { mockFirebaseAnalytics.logEvent(fakeName, null) }
        }
}
