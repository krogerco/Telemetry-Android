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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private object ModuleFacetOne : Facet
private object ModuleFacetTwo : Facet
private object ModuleFacetThree : Facet

private const val featureOneEventDesc = "i'm a feature one event"

private sealed class ModuleEvent : Event {
    sealed class FeatureOneEvent : ModuleEvent() {
        override val description: String = featureOneEventDesc
        override val facets: List<Facet> = listOf(ModuleFacetOne)

        object EventOne : FeatureOneEvent() {
            override val facets: List<Facet> = super.facets + listOf(ModuleFacetTwo)
        }

        object EventTwo : FeatureOneEvent() {
            override val description: String = "i'm an event two!"
            override val facets: List<Facet>
                get() = listOf(ModuleFacetThree)
        }
    }
}

public class EventTest {
    @Test
    public fun `GIVEN event hierarchy WHEN description defined by super THEN unnecessary in subclasses`() {
        assertEquals(featureOneEventDesc, ModuleEvent.FeatureOneEvent.EventOne.description)
    }

    @Test
    public fun `GIVEN facets defined by super and subclasses WHEN created THEN facets are composed with super`() {
        val expectedFacets = listOf(ModuleFacetOne, ModuleFacetTwo)
        assertEquals(expectedFacets, ModuleEvent.FeatureOneEvent.EventOne.facets)
    }

    @Test
    public fun `GIVEN properties defined by super WHEN subclassed with override THEN super is hidden`() {
        val expectedFacets = listOf(ModuleFacetThree)
        assertEquals("i'm an event two!", ModuleEvent.FeatureOneEvent.EventTwo.description)
        assertEquals(expectedFacets, ModuleEvent.FeatureOneEvent.EventTwo.facets)
    }
}
