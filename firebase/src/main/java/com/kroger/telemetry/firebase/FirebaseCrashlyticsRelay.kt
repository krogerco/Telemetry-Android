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

package com.kroger.telemetry.firebase

import com.kroger.telemetry.Event
import com.kroger.telemetry.Relay
import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.Significance
import javax.inject.Inject

/**
 * A [Relay] that sends data to Firebase Crashlytics to be included in crash reports and non-fatal error reports.
 *
 * This relay supports three ways to send data:
 * 1. Adding custom keys - Events with a [CrashlyticsKey] facet will add the key/value pair to the crash report.
 *
 * 2. Logging - Any event with [Significance.DEBUG] or higher is logged to the crash report.
 *
 * 3. Recording non-fatal errors - Any event that includes a [Failure] will record a non-fatal exception, viewable in the
 *    Crashlytics console.
 *
 * See [the Crashlytics docs](https://firebase.google.com/docs/crashlytics/customize-crash-reports?platform=android) for
 * more info about how this data appears in reports.
 *
 * Note that this class takes a [CrashlyticsWrapper] so that you can avoid including Firebase Crashlytics in library
 * modules (which can cause build issues when Crashlytics tries to self-initialize). You will need to provide an
 * implementation of that interface with methods to forward all of the calls to the [FirebaseCrashlytics] instance.
 * Sample code follows for such an implementation.
 *
 * @sample crashlyticsWrapperImplementation
 */
public class FirebaseCrashlyticsRelay @Inject constructor(private val crashlytics: CrashlyticsWrapper) : Relay {

    override suspend fun process(event: Event) {
        event.facets.filterIsInstance<CrashlyticsKey>().forEach {
            when (it) {
                is StringState -> {
                    crashlytics.setCustomKey(it.key, it.value)
                }
                is BooleanState -> {
                    crashlytics.setCustomKey(it.key, it.value)
                }
                is IntState -> {
                    crashlytics.setCustomKey(it.key, it.value)
                }
                is LongState -> {
                    crashlytics.setCustomKey(it.key, it.value)
                }
                is FloatState -> {
                    crashlytics.setCustomKey(it.key, it.value)
                }
                is DoubleState -> {
                    crashlytics.setCustomKey(it.key, it.value)
                }
            }
        }

        if (event.facets.filterIsInstance<Significance>().any { it >= Significance.DEBUG }) {
            crashlytics.log(event.description)
        }

        event.facets.filterIsInstance<Failure>().mapNotNull { it.throwable }.forEach {
            crashlytics.recordException(it)
        }
    }
}

/**
 * A [Facet] that adds a key/value pair to crash and non-fatal exception reports in Firebase Crashlytics. All keys must
 * be unique Strings. There are six types of data can be set as values:
 * - String
 * - Boolean
 * - Int
 * - Long
 * - Float
 * - Double
 *
 * The value of an existing key can be modified by sending the same key again with a different value.
 *
 * NOTE: Crashlytics supports up to 64 key/value pairs in a single report. Each key/value pair must be no larger than 1 kB in size.
 */
public sealed class CrashlyticsKey(public val key: String) : Facet {

    public companion object {
        /**
         * Builds a [CrashlyticsKey] for the key/value pair.
         *
         * @param key a unique key to identify the value
         * @param value a String value
         */
        public fun build(key: String, value: String): CrashlyticsKey = StringState(key, value)

        /**
         * Builds a [CrashlyticsKey] for the key/value pair.
         *
         * @param key a unique key to identify the value
         * @param value a Boolean value
         */
        public fun build(key: String, value: Boolean): CrashlyticsKey = BooleanState(key, value)

        /**
         * Builds a [CrashlyticsKey] for the key/value pair.
         *
         * @param key a unique key to identify the value
         * @param value an Int value
         */
        public fun build(key: String, value: Int): CrashlyticsKey = IntState(key, value)

        /**
         * Builds a [CrashlyticsKey] for the key/value pair.
         *
         * @param key a unique key to identify the value
         * @param value a Long value
         */
        public fun build(key: String, value: Long): CrashlyticsKey = LongState(key, value)

        /**
         * Builds a [CrashlyticsKey] for the key/value pair.
         *
         * @param key a unique key to identify the value
         * @param value a Float value
         */
        public fun build(key: String, value: Float): CrashlyticsKey = FloatState(key, value)

        /**
         * Builds a [CrashlyticsKey] for the key/value pair.
         *
         * @param key a unique key to identify the value
         * @param value a Double value
         */
        public fun build(key: String, value: Double): CrashlyticsKey = DoubleState(key, value)
    }
}

/*
* These classes keep the key/value pairs typesafe, while the CrashlyticsKey build methods keep the public API
* simple, i.e. CrashlyticsKey.build(key, value).
*/
private class StringState(key: String, val value: String) : CrashlyticsKey(key)
private class BooleanState(key: String, val value: Boolean) : CrashlyticsKey(key)
private class IntState(key: String, val value: Int) : CrashlyticsKey(key)
private class LongState(key: String, val value: Long) : CrashlyticsKey(key)
private class FloatState(key: String, val value: Float) : CrashlyticsKey(key)
private class DoubleState(key: String, val value: Double) : CrashlyticsKey(key)
