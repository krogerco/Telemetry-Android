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
import com.kroger.telemetry.facet.FacetResolver
import com.kroger.telemetry.facet.ThreadData
import com.kroger.telemetry.facet.UnresolvedFacet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Telemeters are the top-level component for controlling an [Event] logging pipeline.
 * Telemeters can [record] [Event]s. These will be distributed to the [Relay]s attached to the telemeter
 * for processing. Relays should be configured to extract pertinent data out of the Event by
 * filtering the [Facet]s of them. Facets that are complex or computationally expensive should
 * implement [Facet.Computed] or [Facet.Lazy] to reduce latency times between Events being
 * recorded by a Telemeter and processed by Relays.
 *
 * Parent-child telemeter chains can be set up to create tree-like structures with increasing
 * data specificity. This could be used to accomplish something like module- or class-scoping
 * to track the locations that Events are being generated. See [Telemeter.child] for more
 * information.
 *
 * @sample com.kroger.telemetry.topLevelExample
 */
public interface Telemeter {
    /**
     * Record an [Event]. Additional facets can be attached using [withFacets]. Note that the
     * type of an Event will be overwritten when recorded, so type-checks on Events should be
     * avoided. Instead, type-checks should be used on [Facet]s.
     */
    public fun record(event: Event, withFacets: List<Facet>? = null)

    /**
     * Used to configure the event flow that propagates events to relays.
     * By default, Telemeters will run on [Dispatchers.Default] and be their [CoroutineScope] will be tied
     * to the lifetime of the Telemeter. The other parameters mirror
     * those defined for [MutableSharedFlow]. The default for Telemetry is configured such that
     * events will start to be lost if the slowest relay is 128 events behind the fastest. These
     * defaults were chosen in the case that a relay had effectively stopped, events sent to the
     * rest of the Telemetry system would not be lost. This default is defined as
     * [Telemeter.defaultTelemetryFlowConfig]. To ensure no events are dropped, update
     * the config to share the defaults defined by [MutableSharedFlow], which can be referenced
     * using [Telemeter.defaultSharedFlowConfig].
     *
     * Additionally, [shouldPropagateThreadData] can be enabled to attach call-site stack information
     * as a [ThreadData] facet to recorded events.
     */
    public data class EventFlowConfig(
        val replay: Int,
        val extraBufferCapacity: Int,
        val onBufferOverflow: BufferOverflow,
        val shouldPropagateThreadData: Boolean = false,
        val scope: CoroutineScope? = null,
    )

    public companion object {
        internal const val TAG = "Telemetry"

        /**
         * A factory function for creating top-level Telemeters.
         *
         * @param relays A list of [Relay]s to that will process [Event]s recorded by the Telemeter.
         * @param facets [Facet]s to describe the telemeter. These will get attached to all events recorded by the telemeter. The default is none.
         * @param facetResolvers A list of [FacetResolver]s which will resolve any [UnresolvedFacet]s into [Facet]s before hittin the [Relay]s
         * @param flowConfig Configuration options for the flow that streams events to relays.
         */
        public fun build(
            relays: List<Relay> = listOf(),
            facets: List<Facet> = listOf(),
            facetResolvers: Map<Class<*>, FacetResolver> = mapOf(),
            flowConfig: EventFlowConfig = defaultTelemetryFlowConfig,
        ): Telemeter {
            return StandardTelemeter(
                relays = relays,
                facetResolvers = facetResolvers,
                facets = facets,
                parent = null,
                flowConfig = flowConfig,
            )
        }

        /**
         * Default event flow configuration for Telemetry, to ensure event processing is not
         * blocked by slowest relay.
         */
        public val defaultTelemetryFlowConfig: EventFlowConfig = EventFlowConfig(
            replay = 64,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /**
         * Default event flow configuration used by the coroutine standard library, which
         * will suspend new events until the slowest relay has finished processed the last event.
         */
        public val defaultSharedFlowConfig: EventFlowConfig = EventFlowConfig(
            replay = 0,
            extraBufferCapacity = 0,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )
    }
}

/**
 * Construct a child telemeter. This can be useful for creating logical scopes. For example,
 * you could add [Facet]s that were specific to a module, a class, or even a function in order
 * to better trace where [Event]s were being generated in the pipeline.
 *
 * For example, you could create a module-specific Telemeter during injection by doing the following:
 * @sample com.kroger.telemetry.childTelemeterSample
 */
public fun Telemeter.child(
    relays: List<Relay> = listOf(),
    facets: List<Facet> = listOf(),
    facetResolvers: Map<Class<*>, FacetResolver> = mapOf(),
): Telemeter = StandardTelemeter(
    relays = relays,
    facets = facets,
    parent = this,
    flowConfig = (this as? StandardTelemeter)?.flowConfig ?: Telemeter.defaultTelemetryFlowConfig,
    facetResolvers = facetResolvers,
)
