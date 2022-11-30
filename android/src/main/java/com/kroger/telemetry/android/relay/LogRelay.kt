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

import android.util.Log
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.relay.PrintRelay

/**
 * A Relay that will print events to to Logcat. Note that if the default and minimum significance parameters are
 * left unchanged, the Relay will print all Events, even those without a [Significance].
 *
 * @param configuration An optional set of configuration options for the Relay. See [PrintRelay.Configuration].
 */
public class LogRelay(
    configuration: Configuration = Configuration.Default(),
) : PrintRelay(configuration, logPrinter)

private val logPrinter: (PrintRelay.Message) -> Unit = { message ->
    Log.println(message.significance.toLogPriority(), message.tag, message.value)
}

private fun Significance.toLogPriority(): Int = when (this) {
    Significance.VERBOSE -> Log.VERBOSE
    Significance.DEBUG -> Log.DEBUG
    Significance.INFORMATIONAL -> Log.INFO
    Significance.WARNING -> Log.WARN
    Significance.ERROR -> Log.ERROR
    Significance.INTERNAL_ERROR -> Log.ERROR
}
