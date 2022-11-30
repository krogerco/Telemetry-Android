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

package com.kroger.telemetry.facet

/**
 * A type for defining facets that contain developer metrics.
 * Suggested use is to implement this interface for each distinct type of event an
 * application is interested in logging. Override [Facet.Computed.compute] to return
 * a map defining additional data parameters for the event. The default implementation
 * returns null.
 *
 * @sample sampleDeveloperMetricsFacet
 */
public interface DeveloperMetricsFacet : Facet.Computed<Map<String, Any?>?> {
    public val eventName: String
    public override val compute: () -> Map<String, Any?>?
        get() = { null }
}

internal fun sampleDeveloperMetricsFacet() {
    data class AThingyHappenedDeveloperMetricsFacet(val tag: String) : DeveloperMetricsFacet {
        override val eventName: String = "A thingy happened!"
        override val compute: () -> Map<String, Any?> = {
            mapOf<String, Any?>(
                "tag" to tag,
            )
        }
    }
}
