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

import com.kroger.telemetry.Telemeter
import com.kroger.telemetry.child

/**
 * Prefixes can be attached to [Telemeter]s to create parent-child
 * relationships that demonstrate where an event was recorded.
 *
 * See Telemeter.[child] for more details.
 * @sample com.kroger.telemetry.childTelemeterSample
 */
public sealed class Prefix : Facet {
    public abstract val value: String

    public data class App(override val value: String) : Prefix()

    public data class Module(override val value: String) : Prefix()

    public data class Screen(override val value: String) : Prefix()

    public data class Class(override val value: String) : Prefix()

    public data class LocalScope(override val value: String) : Prefix()
}
