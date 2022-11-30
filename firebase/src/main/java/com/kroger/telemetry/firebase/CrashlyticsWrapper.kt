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

/**
 * An interface which wraps the Crashlytics API calls used by the [FirebaseCrashlyticsRelay]. Crashlytics cannot be added
 * to the classpath of library modules, so this interface inverts the dependency and allows the real implementation of Crashlytics
 * to only be included in the application module. The sample shows a possible implementation.
 *
 * @sample crashlyticsWrapperImplementation
 */
public interface CrashlyticsWrapper {

    /**
     * Records a custom key and value to be associated with subsequent fatal and non-fatal reports. Multiple calls to this method
     * with the same key will update the value for that key. The value of any key at the time of a fatal or non-fatal event will
     * be associated with that event. Keys and associated values are visible in the session view on the Firebase Crashlytics
     * console. A maximum of 64 key/value pairs can be written, and new keys added beyond that limit will be ignored. Keys or
     * values that exceed 1024 characters will be truncated.
     *
     * @param key A unique key
     *
     *@param value A value to be associated with the given key
     */
    public fun setCustomKey(key: String, value: String)

    /**
     * Records a custom key and value to be associated with subsequent fatal and non-fatal reports. Multiple calls to this method
     * with the same key will update the value for that key. The value of any key at the time of a fatal or non-fatal event will
     * be associated with that event. Keys and associated values are visible in the session view on the Firebase Crashlytics
     * console. A maximum of 64 key/value pairs can be written, and new keys added beyond that limit will be ignored. Keys or
     * values that exceed 1024 characters will be truncated.
     *
     * @param key A unique key
     *
     *@param value A value to be associated with the given key
     */
    public fun setCustomKey(key: String, value: Boolean)

    /**
     * Records a custom key and value to be associated with subsequent fatal and non-fatal reports. Multiple calls to this method
     * with the same key will update the value for that key. The value of any key at the time of a fatal or non-fatal event will
     * be associated with that event. Keys and associated values are visible in the session view on the Firebase Crashlytics
     * console. A maximum of 64 key/value pairs can be written, and new keys added beyond that limit will be ignored. Keys or
     * values that exceed 1024 characters will be truncated.
     *
     * @param key A unique key
     *
     *@param value A value to be associated with the given key
     */
    public fun setCustomKey(key: String, value: Int)

    /**
     * Records a custom key and value to be associated with subsequent fatal and non-fatal reports. Multiple calls to this method
     * with the same key will update the value for that key. The value of any key at the time of a fatal or non-fatal event will
     * be associated with that event. Keys and associated values are visible in the session view on the Firebase Crashlytics
     * console. A maximum of 64 key/value pairs can be written, and new keys added beyond that limit will be ignored. Keys or
     * values that exceed 1024 characters will be truncated.
     *
     * @param key A unique key
     *
     *@param value A value to be associated with the given key
     */
    public fun setCustomKey(key: String, value: Long)

    /**
     * Records a custom key and value to be associated with subsequent fatal and non-fatal reports. Multiple calls to this method
     * with the same key will update the value for that key. The value of any key at the time of a fatal or non-fatal event will
     * be associated with that event. Keys and associated values are visible in the session view on the Firebase Crashlytics
     * console. A maximum of 64 key/value pairs can be written, and new keys added beyond that limit will be ignored. Keys or
     * values that exceed 1024 characters will be truncated.
     *
     * @param key A unique key
     *
     *@param value A value to be associated with the given key
     */
    public fun setCustomKey(key: String, value: Float)

    /**
     * Records a custom key and value to be associated with subsequent fatal and non-fatal reports. Multiple calls to this method
     * with the same key will update the value for that key. The value of any key at the time of a fatal or non-fatal event will
     * be associated with that event. Keys and associated values are visible in the session view on the Firebase Crashlytics
     * console. A maximum of 64 key/value pairs can be written, and new keys added beyond that limit will be ignored. Keys or
     * values that exceed 1024 characters will be truncated.
     *
     * @param key A unique key
     *
     *@param value A value to be associated with the given key
     */
    public fun setCustomKey(key: String, value: Double)

    /**
     * Records a non-fatal report to send to Crashlytics.
     *
     * @param e a [Throwable] to be recorded as a non-fatal event.
     */
    public fun recordException(e: Throwable)

    /**
     * Logs a message that's included in the next fatal or non-fatal report. Logs are visible in the session view on the Firebase
     * Crashlytics console. Newline characters are stripped and extremely long messages are truncated. The maximum log size is
     * 64k. If exceeded, the log rolls such that messages are removed, starting from the oldest.
     *
     * @param message the message to be logged
     */
    public fun log(message: String)
}
