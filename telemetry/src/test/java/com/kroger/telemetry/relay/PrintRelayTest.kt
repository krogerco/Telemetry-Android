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

import com.kroger.telemetry.Telemeter
import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.facet.Prefix
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.util.FakeEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PrintRelayTest {
    private val messages: MutableList<PrintRelay.Message> = mutableListOf()
    private val capturePrinter: (PrintRelay.Message) -> Unit = {
        messages.add(it)
    }

    private lateinit var relay: PrintRelay

    @BeforeEach
    fun setup() {
        relay = PrintRelay(printer = capturePrinter)
        messages.clear()
    }

    private fun TestScope.createTelemeter() =
        Telemeter.build(
            relays = listOf(relay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = backgroundScope),
        )

    @Test
    fun `GIVEN detailed mode is enabled WHEN event logged with multiple prefixes THEN all are included in tag`() =
        runTest {
            val telemeter = createTelemeter()
            val appName = "app"
            val moduleName = "module"
            val className = "class"
            val localName = "local"
            val event =
                FakeEvent(
                    facets =
                        listOf(
                            Prefix.App(appName),
                            Prefix.Module(moduleName),
                            Prefix.Class(className),
                            Prefix.LocalScope(localName),
                        ),
                )
            relay.configuration.detailedMode = true

            telemeter.record(event)
            testScheduler.runCurrent()
            val expectedTag =
                Telemeter.TAG + PrintRelay.SEPARATOR +
                    relay.configuration.defaultSignificance.toString() + PrintRelay.SEPARATOR +
                    appName + PrintRelay.SEPARATOR +
                    moduleName + PrintRelay.SEPARATOR +
                    className + PrintRelay.SEPARATOR +
                    localName
            assertEquals(expectedTag, messages[0].tag)
        }

    @Test
    fun `GIVEN detailed mode is enabled WHEN event logged with multiple prefixes in bad order THEN order is retained in tag`() =
        runTest {
            val telemeter = createTelemeter()
            val appName = "app"
            val moduleName = "module"
            val screenName = "screen"
            val localName = "local"
            val event =
                FakeEvent(
                    facets =
                        listOf(
                            Prefix.Screen(screenName),
                            Prefix.App(appName),
                            Prefix.LocalScope(localName),
                            Prefix.Module(moduleName),
                        ),
                )
            relay.configuration.detailedMode = true

            telemeter.record(event)
            testScheduler.runCurrent()
            val expectedTag =
                Telemeter.TAG + PrintRelay.SEPARATOR +
                    relay.configuration.defaultSignificance.toString() + PrintRelay.SEPARATOR +
                    screenName + PrintRelay.SEPARATOR +
                    appName + PrintRelay.SEPARATOR +
                    localName + PrintRelay.SEPARATOR +
                    moduleName
            assertEquals(expectedTag, messages[0].tag)
        }

    @Test
    fun `GIVEN detailed mode is disabled WHEN event logged with multiple prefixes THEN most recent prefix is used`() =
        runTest {
            val telemeter = createTelemeter()
            val appName = "app"
            val moduleName = "module"
            val screenName = "screen"
            val localName = "local"
            val event =
                FakeEvent(
                    facets =
                        listOf(
                            Prefix.App(appName),
                            Prefix.Module(moduleName),
                            Prefix.Screen(screenName),
                            Prefix.LocalScope(localName),
                        ),
                )

            telemeter.record(event)
            testScheduler.runCurrent()
            val expectedTag =
                Telemeter.TAG + PrintRelay.SEPARATOR +
                    relay.configuration.defaultSignificance.toString() + PrintRelay.SEPARATOR +
                    localName
            assertEquals(expectedTag, messages[0].tag)
        }

    @Test
    fun `GIVEN detailed mode is disabled WHEN event logged with multiple prefixes in bad order THEN most recent prefix is used`() =
        runTest {
            val telemeter = createTelemeter()
            val appName = "app"
            val moduleName = "module"
            val screenName = "screen"
            val localName = "local"
            val event =
                FakeEvent(
                    facets =
                        listOf(
                            Prefix.Screen(screenName),
                            Prefix.App(appName),
                            Prefix.LocalScope(localName),
                            Prefix.Module(moduleName),
                        ),
                )

            telemeter.record(event)
            testScheduler.runCurrent()
            val expectedTag =
                Telemeter.TAG + PrintRelay.SEPARATOR +
                    relay.configuration.defaultSignificance.toString() + PrintRelay.SEPARATOR +
                    moduleName
            assertEquals(expectedTag, messages[0].tag)
        }

    @Test
    fun `tags will include highest significance attached to event`() =
        runTest {
            val telemeter = createTelemeter()
            val event =
                FakeEvent(
                    facets =
                        listOf(
                            Significance.VERBOSE,
                            Significance.INTERNAL_ERROR,
                            Significance.WARNING,
                        ),
                )

            telemeter.record(event)
            testScheduler.runCurrent()
            val expectedTag =
                Telemeter.TAG + PrintRelay.SEPARATOR + Significance.INTERNAL_ERROR.toString()
            assertEquals(expectedTag, messages[0].tag)
        }

    @Test
    fun `relay significance will be attached if not specified on event`() =
        runTest {
            val telemeter = createTelemeter()
            val event = FakeEvent()

            telemeter.record(event)
            testScheduler.runCurrent()
            assertEquals(Significance.DEBUG, messages[0].significance)
        }

    @Test
    fun `if configured for detailedMode, all facets will be included in message value`() =
        runTest {
            val telemeter = createTelemeter()
            relay.configuration.detailedMode = true
            val description = "hello there"
            val firstMsg = "general"
            val secondMsg = "kenobi"
            val facet1 =
                object : Facet {
                    override fun toString(): String = firstMsg
                }
            val facet2 =
                object : Facet {
                    override fun toString(): String = secondMsg
                }

            val event =
                FakeEvent(
                    description = description,
                    facets = listOf(facet1, facet2),
                )
            telemeter.record(event)
            testScheduler.runCurrent()
            val expectedMessage =
                """
                $description
                $firstMsg
                $secondMsg
                """.trimIndent()
            assertEquals(expectedMessage, messages[0].value)

            relay.configuration.detailedMode = false
        }

    @Test
    fun `if not configured for detailed mode, only description is include in message value`() =
        runTest {
            val telemeter = createTelemeter()
            val description = "hello there"
            val firstMsg = "general"
            val secondMsg = "kenobi"
            val facet1 =
                object : Facet {
                    override fun toString(): String = firstMsg
                }
            val facet2 =
                object : Facet {
                    override fun toString(): String = secondMsg
                }

            val event =
                FakeEvent(
                    description = description,
                    facets = listOf(facet1, facet2),
                )
            telemeter.record(event)
            testScheduler.runCurrent()
            assertEquals(description, messages[0].value)
        }

    @Test
    fun `GIVEN event with significance lower than minimum WHEN processed THEN nothing will be printed`() =
        runTest {
            val telemeter = createTelemeter()
            relay.configuration.minimumSignificance = Significance.INTERNAL_ERROR
            val event = FakeEvent(facets = listOf(Significance.ERROR))

            telemeter.record(event)
            testScheduler.runCurrent()
            assertEquals(0, messages.size)
        }

    @Test
    fun `GIVEN event with significance equal to minimum WHEN processed THEN message will be printed`() =
        runTest {
            val telemeter = createTelemeter()
            relay.configuration.minimumSignificance = Significance.ERROR
            val event = FakeEvent(facets = listOf(Significance.ERROR))

            telemeter.record(event)
            testScheduler.runCurrent()
            assertEquals(1, messages.size)
        }

    @Test
    fun `GIVEN event with significance higher than minimum WHEN processed THEN message will be printed`() =
        runTest {
            val telemeter = createTelemeter()
            relay.configuration.minimumSignificance = Significance.VERBOSE
            val event = FakeEvent(facets = listOf(Significance.ERROR))

            telemeter.record(event)
            testScheduler.runCurrent()
            assertEquals(1, messages.size)
        }

    @Test
    fun `GIVEN config delegated to default WHEN property is accessed THEN backing prop is accessed`() =
        runTest {
            var backingProp = true

            class Config : PrintRelay.Configuration by PrintRelay.Configuration.Default() {
                override var detailedMode: Boolean
                    get() = backingProp
                    set(value) {
                        backingProp = value
                    }
            }

            val config = Config()
            assertTrue(config.detailedMode)
        }

    @Test
    fun `GIVEN config delegated to default WHEN property is written THEN backing prop is written`() =
        runTest {
            var backingProp = true

            class Config : PrintRelay.Configuration by PrintRelay.Configuration.Default() {
                override var detailedMode: Boolean
                    get() = backingProp
                    set(value) {
                        backingProp = value
                    }
            }

            val config = Config()
            config.detailedMode = false
            assertFalse(backingProp)
        }
}
