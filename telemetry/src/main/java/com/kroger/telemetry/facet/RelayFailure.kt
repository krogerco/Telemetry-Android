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

import com.kroger.telemetry.Relay

/**
 * A facet representing a failure during a Relay's processing of events.
 *
 * A RelayFailure contains a reference to the relay that failed, allowing the Telemeter to prevent
 * infinite loops by preventing that specific relay from processing its own failure events. A
 * RelayFailure may include another RelayFailure as a [cause]
 *
 * The [cause] property enables progressive relay disqualification: when a relay fails
 * while processing a RelayFailure, the new RelayFailure references the original as its cause.
 * This creates a cause chain that prevents infinite loops when multiple relays fail
 * while processing each other's failures.
 *
 * This facet is created automatically by the Telemeter. Consumers should not add this facet to their
 * own events.
 *
 * @param sourceRelay the Relay where the failure occurred
 * @param message a message associated with the failure
 * @param throwable a [Throwable] associated with the failure
 * @param cause an optional RelayFailure that was the cause of this failure
 */
public data class RelayFailure(
    val sourceRelay: Relay,
    val message: String? = null,
    val throwable: Throwable? = null,
    val cause: RelayFailure? = null,
) : Facet
