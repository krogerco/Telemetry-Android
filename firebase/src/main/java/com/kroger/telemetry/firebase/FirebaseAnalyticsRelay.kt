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

import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.firebase.analytics.FirebaseAnalytics
import com.kroger.telemetry.Relay
import com.kroger.telemetry.facet.DeveloperMetricsFacet
import javax.inject.Inject

/**
 * A Relay for logging analytic events to Firebase. See [DeveloperMetricsFacet]
 * for more details.
 *
 * @param firebaseAnalytics The FirebaseAnalytics instance that will be used to log events.
 */
public class FirebaseAnalyticsRelay @Inject constructor(
    private val firebaseAnalytics: FirebaseAnalytics,
) : Relay by Relay.buildTypedRelay<DeveloperMetricsFacet>(
    { facet ->
        firebaseAnalytics.logEvent(facet.eventName, facet.compute()?.toBundle())
    },
)

private fun Map<String, Any?>.toBundle(): Bundle = bundleOf(*this.toList().toTypedArray())
