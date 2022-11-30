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

/**
 * Relays should be created for each type of processing desired for a pipeline. This might
 * include things like a LogRelay or a FirebaseRelay. They should be specific to a single
 * kind of processing.
 */
public interface Relay {
    /**
     * Consumes [Event]s that are recorded by a [Telemeter]. Note that the
     * type of an Event will be overwritten when recorded, so type-checks on Events should be
     * avoided. Instead, type-checks should be used on [Facet]s.
     */
    public suspend fun process(event: Event)

    public companion object {
        /**
         * This function can be used to avoid some boilerplate in creating [TypedRelay]s.
         *
         * @sample createTypedRelay
         */
        public inline fun <reified T : Facet> buildTypedRelay(crossinline processFacet: suspend (T) -> Unit): TypedRelay<T> =
            object : TypedRelay<T> {
                override val type: Class<T> = T::class.java

                override suspend fun processFacet(facet: T) = processFacet(facet)
            }
    }
}

/**
 * A Relay that only cares about a single [Facet] type. This is a convenience type
 * to avoid boiler-plate for extracting relevant Facets from Events.
 */
public interface TypedRelay<T : Facet> : Relay {
    public val type: Class<T>
    override suspend fun process(event: Event) {
        event.facets.filterIsInstance(type).forEach { facet ->
            processFacet(facet)
        }
    }

    /**
     * Consumes the specific [Facet] type that this Relay cares about. If a single event has
     * multiple instances of that type of [Facet], all instances will be received by this method.
     */
    public suspend fun processFacet(facet: T)
}
