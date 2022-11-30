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

package com.kroger.telemetry.relay

import com.kroger.telemetry.Event
import com.kroger.telemetry.Relay
import com.kroger.telemetry.Telemeter
import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.Prefix
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.samplePrintConfig

/**
 * A PrintRelay is an abstraction for Relays that might print Event data to human-readable IO.
 * This could include things like a console or terminal for testing, or to Logcat. There is a LogRelay
 * implementation available in the Android artifact.
 *
 * @property printer Method for printing. Defaults to println.
 */
public open class PrintRelay(
    public val configuration: Configuration = Configuration.Default(),
    public val printer: (Message) -> Unit = { message ->
        println("${message.tag} --- ${message.value}")
    },
) : Relay {

    /**
     * @property defaultSignificance Significance level to attach to Events if the Event does not include one.
     * @property minimumSignificance If an Event is less significant, it will not be processed.
     * @property detailedMode If enabled, tags will include all [Prefix] information and all [Facet]s will be printed.
     *
     */
    public interface Configuration {
        public var defaultSignificance: Significance
        public var minimumSignificance: Significance
        public var detailedMode: Boolean

        /**
         * A set of common defaults for configuration. Can be initialized with specific properties changed,
         * or delegated to in order to override specific property behavior.
         *
         * @sample samplePrintConfig()
         */
        public data class Default(
            override var defaultSignificance: Significance = Significance.DEBUG,
            override var minimumSignificance: Significance = Significance.DEBUG,
            override var detailedMode: Boolean = false,
        ) : Configuration
    }

    override suspend fun process(event: Event) {
        val tag = event.generateTag()
        val significance = event.getSignificance()
        if (significance < configuration.minimumSignificance) return
        val message = Message(
            tag = tag,
            significance = significance,
            value = if (configuration.detailedMode.not()) {
                event.toSimpleMessage()
            } else {
                event.toDetailedMessage()
            },
        )
        printer(message)
    }

    private fun Event.toSimpleMessage(): String = this.description

    private fun Event.toDetailedMessage(): String {
        val nonSignificanceFacets = facets.filter { it !is Significance }
        return "$description\n" + nonSignificanceFacets.joinToString("\n")
    }

    private fun Event.generateTag(): String {
        val prefixes = facets.filterIsInstance<Prefix>()
        val significance = getSignificance()
        return if (configuration.detailedMode) {
            val allPrefixes = prefixes.joinToString(separator) { it.value }
            listOf(
                Telemeter.TAG,
                significance,
                allPrefixes,
            ).joinToString(separator)
        } else {
            val mostLocalPrefix = prefixes.lastOrNull()?.value
            listOfNotNull(
                Telemeter.TAG,
                significance,
                mostLocalPrefix,
            ).joinToString(separator)
        }
    }

    // Get the highest significance level attached to the event.
    private fun Event.getSignificance(): Significance = facets
        .filterIsInstance<Significance>().maxOrNull() ?: configuration.defaultSignificance

    public data class Message(
        val tag: String,
        val significance: Significance,
        val value: String,
    )

    public companion object {
        internal const val separator = " | "
    }
}

/**
 * Convenience method to log a message to Logcat with a custom [Significance]
 */
public fun Telemeter.log(
    tag: String? = null,
    message: String,
    significance: Significance = Significance.DEBUG,
): Unit = record(
    object : Event {
        override val description: String = (tag?.let { "$tag - " } ?: "") + message
        override val facets: List<Facet> = listOf(significance)
    },
)

/**
 * Convenience method to log a message to Logcat with a custom [Significance]
 * Throwables will be converted to [Failure] facets and included in the event.
 */
public fun Telemeter.logError(
    tag: String? = null,
    message: String,
    significance: Significance = Significance.ERROR,
    throwable: Throwable? = null,
): Unit = record(
    object : Event {
        val usedTag = tag?.let {
            "$tag "
        } ?: ""
        val usedThrowableMessage = throwable?.let {
            " - ${throwable.message}"
        } ?: ""
        val usedMessage = usedTag + message + usedThrowableMessage

        // This is a list so it can be easily combined below
        val failureFacet = throwable?.let {
            listOf(Failure(usedMessage, throwable))
        } ?: listOf()
        override val description: String = usedMessage
        override val facets: List<Facet> = listOf(significance) + failureFacet
    },
)

/**
 * Convenience method to log a message to Logcat with [Significance.VERBOSE]
 */
public fun Telemeter.v(tag: String? = null, message: String): Unit =
    log(tag, message, Significance.VERBOSE)

/**
 * Convenience method to log a message to Logcat with [Significance.DEBUG]
 */
public fun Telemeter.d(tag: String? = null, message: String): Unit =
    log(tag, message, Significance.DEBUG)

/**
 * Convenience method to log a message to Logcat with [Significance.INFORMATIONAL]
 */
public fun Telemeter.i(tag: String? = null, message: String): Unit =
    log(tag, message, Significance.INFORMATIONAL)

/**
 * Convenience method to log a message to Logcat with [Significance.WARNING]
 */
public fun Telemeter.w(tag: String? = null, message: String): Unit =
    log(tag, message, Significance.WARNING)

/**
 * Convenience method to log a message to Logcat with [Significance.ERROR].
 * Throwables will be converted to [Failure] facets and included in the event.
 */
public fun Telemeter.e(tag: String? = null, message: String, throwable: Throwable? = null): Unit =
    logError(tag, message, Significance.ERROR, throwable)

/**
 * Convenience method to log a message to Logcat with [Significance.INTERNAL_ERROR]
 * Throwables will be converted to [Failure] facets and included in the event.
 */
public fun Telemeter.wtf(tag: String? = null, message: String, throwable: Throwable? = null): Unit =
    logError(tag, message, Significance.INTERNAL_ERROR, throwable)
