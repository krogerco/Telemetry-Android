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

import com.kroger.telemetry.Event
import com.kroger.telemetry.facet.Facet.Computed
import com.kroger.telemetry.facet.Facet.Lazy

/**
 * Facets should be implemented to organize data and metadata about [Event]s. If the data attached
 * to a Facet is complex or computationally expensive to generate, consider using [Computed] or [Lazy]
 * to keep pipeline latency low. This will help events reach relays faster, before the computations are
 * done.
 */
public interface Facet {
    public interface Computed<T> : Facet {
        public val compute: () -> T
    }

    public abstract class Lazy<T> : Computed<T> {
        public val value: T by lazy {
            compute()
        }
    }
}
