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
import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.ThreadData
import com.kroger.telemetry.facet.UnresolvedFacet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

internal class StandardTelemeter(
    relays: List<Relay>,
    private val facetResolvers: Map<Class<*>, FacetResolver>,
    private val facets: List<Facet>,
    private val parent: Telemeter?,
    internal val flowConfig: Telemeter.EventFlowConfig,
) : Telemeter {

    private val coroutineScope = flowConfig.scope ?: CoroutineScope(Dispatchers.Default)
    private val events = MutableSharedFlow<Event>(
        replay = flowConfig.replay,
        extraBufferCapacity = flowConfig.extraBufferCapacity,
        onBufferOverflow = flowConfig.onBufferOverflow,
    )

    init {
        relays.forEach { relay ->
            events.onEach { event ->
                Result.runCatching {
                    relay.process(event)
                }.onFailure {
                    val message = "An error was caught during Relay processing. It was $it"
                    val facet = Failure(message = message, throwable = it)
                    // Avoid loops
                    if (event.facets.contains(facet)) return@onEach
                    val failureEvent = object : Event {
                        override val description: String = message
                        override val facets: List<Facet> = listOf(facet)
                    }
                    record(failureEvent)
                }
            }.launchIn(coroutineScope)
        }
    }

    override fun record(event: Event, withFacets: List<Facet>?) {
        // Note that this composition of facets into an anonymous object erases the original
        // type of the event. This means that any type checking in relays should depend on facets.
        val allFacets = (resolveFacets(event.facets) + (withFacets ?: listOf())).addMetaFacets()

        val additionallyFacetedEvent = object : Event {
            override val description = event.description
            override val facets = allFacets
        }

        coroutineScope.launch {
            events.emit(additionallyFacetedEvent)
        }

        parent?.record(additionallyFacetedEvent)
    }

    private fun resolveFacets(facets: List<Facet>): List<Facet> = if (facetResolvers.isNotEmpty()) {
        val unresolvedFacets: List<UnresolvedFacet> = facets.filterIsInstance<UnresolvedFacet>()
        val resolvedFacets: List<Facet> = facets.filterNot { it is UnresolvedFacet }

        val finalFacets: List<Facet> = unresolvedFacets.flatMap { unresolvedFacet ->
            try {
                facetResolvers[unresolvedFacet::class.java]?.resolve(unresolvedFacet)
                    ?: listOf(unresolvedFacet)
            } catch (e: Exception) {
                // We must swallow this error to continue processing the rest of the facets, and continue on to the relays
                listOf(unresolvedFacet)
            }
        } + resolvedFacets

        finalFacets
    } else {
        facets
    }

    private fun List<Facet>.addMetaFacets(): List<Facet> {
        // Only a leaf node Telemeter needs to propagate ThreadData to a Relay, so check if
        // any ThreadData has already been recorded
        val threadFacet =
            if (flowConfig.shouldPropagateThreadData && this.any { it is ThreadData }.not()) {
                listOf(
                    Thread.currentThread().let { ThreadData(it.name, it.stackTrace) },
                )
            } else listOf()
        /* telemeter facets start the list so that as we move back upwards through the
        telemeter chain events get automatic scoping, for example:
        grandChildFacets + ...
        childFacets + (grandChildFacets + ...)
        parentFacets + (childFacets + (grandChildFacets + ...))
        so they could be something like:
        listOf(Prefix.App + (Prefix.Module + (Prefix.Class)))
        */
        return facets + threadFacet + this
    }
}
