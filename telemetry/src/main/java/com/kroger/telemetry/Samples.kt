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
import com.kroger.telemetry.facet.Prefix
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.relay.PrintRelay

private data class MyDataType(val data: String)

internal fun topLevelExample() {
    data class MyFacet(override val compute: () -> MyDataType) : Facet.Computed<MyDataType>

    data class MyEvent(val thingThatHappened: String) : Event {
        override val description: String = thingThatHappened
        override val facets: List<Facet> = listOf(
            Significance.INFORMATIONAL,
            MyFacet { /* expensive logic that results in */ MyDataType(thingThatHappened) },
            /* other facets for other relays */
        )
    }

    class MyRelay : TypedRelay<MyFacet> {
        override val type: Class<MyFacet> = MyFacet::class.java
        override suspend fun processFacet(facet: MyFacet) {
            /* do something with */ facet.compute().data
        }
    }

    /* Make appTelemeter available to your application */
    val appTelemeter = Telemeter.build(
        relays = listOf(MyRelay()),
        facets = listOf(Prefix.App("My Application Name")),
    )

    fun onThingHappened() {
        appTelemeter.record(MyEvent("a thing happened"))
    }
}

internal fun childTelemeterSample(parentTelemeter: Telemeter) {
    val modulePrefixFacet = Prefix.Module("My Module Name")
    parentTelemeter.child(facets = listOf(modulePrefixFacet))
}

internal fun createTypedRelay() {
    data class MyFacet(val myValue: String) : Facet
    class MyTypedRelay : TypedRelay<MyFacet> by Relay.buildTypedRelay(
        { myFacet ->
            myFacet.myValue
        },
    )
}

private interface Toggles {
    operator fun get(key: String): Boolean
}

internal fun samplePrintConfig() {
    class PropertyBehaviorChangeConfig(private val toggles: Toggles) :
        PrintRelay.Configuration by PrintRelay.Configuration.Default() {
        override var detailedMode: Boolean
            get() = toggles["PrintRelay Toggle"]
            set(_) = Unit
    }
}
