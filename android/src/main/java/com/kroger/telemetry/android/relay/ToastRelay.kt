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

import android.content.Context
import android.widget.Toast
import com.kroger.telemetry.Event
import com.kroger.telemetry.Relay
import com.kroger.telemetry.android.facet.ToastFacet
import com.kroger.telemetry.android.relay.ToastRelay.Configuration
import com.kroger.telemetry.facet.Significance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Relay that will Toast messages. It will Toast either the first [ToastFacet] attached to the [Event]
 * or if none is available it will show the Event's description, given that the event has a high enough
 * [Significance] and [Configuration.toastSignificantEvents] is enabled.
 */

public class ToastRelay internal constructor(
    private val toaster: Toaster,
    public val configuration: Configuration,
) : Relay {

    /**
     * A set of configurable options for a ToastRelay.
     * @property toastLength Should be one of [Toast.LENGTH_SHORT] or [Toast.LENGTH_LONG]. Defaults to short.
     * @property enabled Whether this Relay will display any Toasts. For example, this could be set to false in production builds.
     * @property toastSignificantEvents Whether events with a minimum significance should be shown, even if they have no [ToastFacet]s.
     * @property minimumSignificance The minimum significance required for an [Event] to be shown if it has no [ToastFacet]s and [toastSignificantEvents] is enabled.
     */
    public interface Configuration {
        public var enabled: Boolean
        public var toastLength: Int
        public var toastSignificantEvents: Boolean
        public var minimumSignificance: Significance

        /**
         * A set of common defaults for configuration. Can be initialized with specific properties changed,
         * or delegated to in order to override specific property behavior.
         *
         * @sample sampleToastConfig()
         */
        public data class Default(
            override var enabled: Boolean = true,
            override var toastLength: Int = Toast.LENGTH_SHORT,
            override var toastSignificantEvents: Boolean = false,
            override var minimumSignificance: Significance = Significance.ERROR,
        ) : Configuration
    }

    /**
     * @param context The context that will show Toasts
     * @param configuration An optional configuration for the Relay.
     */
    public constructor(
        context: Context,
        configuration: Configuration = Configuration.Default(),
    ) : this(ToasterImpl(context), configuration)

    override suspend fun process(event: Event) {
        val toastFacets = event.facets.filterIsInstance(ToastFacet::class.java)
        val shouldToastWithoutToastFacet = event.hasHighEnoughSignificance() &&
            configuration.toastSignificantEvents
        val shouldToast = toastFacets.isNotEmpty() || shouldToastWithoutToastFacet
        if (shouldToast && configuration.enabled) {
            val message = toastFacets.firstOrNull()?.message ?: event.description
            val correctedLength = when (configuration.toastLength) {
                Toast.LENGTH_SHORT -> configuration.toastLength
                Toast.LENGTH_LONG -> configuration.toastLength
                else -> Toast.LENGTH_SHORT
            }
            toaster.toast(message, correctedLength)
        }
    }

    private fun Event.hasHighEnoughSignificance(): Boolean = facets
        .filterIsInstance(Significance::class.java)
        .any { it >= configuration.minimumSignificance }
}

internal interface Toaster {
    suspend fun toast(message: String, length: Int)
}

private class ToasterImpl(private val context: Context) : Toaster {
    override suspend fun toast(message: String, length: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(context, message, length).show()
    }
}

private interface Toggles {
    operator fun get(key: String): Boolean
}

private fun sampleToastConfig() {
    val propertyChangeConfig = ToastRelay.Configuration.Default(toastSignificantEvents = true)

    class PropertyBehaviorChangeConfig(private val toggles: Toggles) :
        ToastRelay.Configuration by ToastRelay.Configuration.Default() {
        override var enabled: Boolean
            get() = toggles["ToastRelay Toggle"]
            set(_) = Unit
    }
}
