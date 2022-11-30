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

@file:Suppress("unused")

package com.kroger.telemetry.firebase

import javax.inject.Inject

/**
 * This serves to make the `RealCrashlytics` sample below compile without crashlytics on the classpath
 */
private interface FirebaseCrashlytics {
    fun setCustomKey(key: String, value: Any)
    fun recordException(e: Throwable)
    fun log(message: String)
}

/**
 * A sample of how one might implement [CrashlyticsWrapper] to forward calls to the real thing
 */
internal fun crashlyticsWrapperImplementation() {
    class RealCrashlytics @Inject constructor(private val crashlytics: FirebaseCrashlytics) :
        CrashlyticsWrapper {
        override fun setCustomKey(key: String, value: String) {
            crashlytics.setCustomKey(key, value)
        }

        override fun setCustomKey(key: String, value: Boolean) {
            crashlytics.setCustomKey(key, value)
        }

        override fun setCustomKey(key: String, value: Int) {
            crashlytics.setCustomKey(key, value)
        }

        override fun setCustomKey(key: String, value: Long) {
            crashlytics.setCustomKey(key, value)
        }

        override fun setCustomKey(key: String, value: Float) {
            crashlytics.setCustomKey(key, value)
        }

        override fun setCustomKey(key: String, value: Double) {
            crashlytics.setCustomKey(key, value)
        }

        override fun recordException(e: Throwable) {
            crashlytics.recordException(e)
        }

        override fun log(message: String) {
            crashlytics.log(message)
        }
    }
}
