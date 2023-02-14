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

package com.kroger.telemetry

import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.facet.FacetResolver
import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.Prefix
import com.kroger.telemetry.facet.ThreadData
import com.kroger.telemetry.facet.UnresolvedFacet
import com.kroger.telemetry.util.FakeEvent
import com.kroger.telemetry.util.FakeRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
internal class TelemeterTest {
    private val scope = TestCoroutineScope()

    @AfterEach
    fun teardown() {
        scope.cleanupTestCoroutines()
    }

    @Test
    fun `GIVEN telemeter with relays WHEN event recorded THEN relays receive events`() {
        var relayOneProcessCount = 0
        val relayOne = FakeRelay { relayOneProcessCount += 1 }

        var relayTwoProcessCount = 0
        val relayTwo = FakeRelay { relayTwoProcessCount += 1 }

        val telemeter = Telemeter.build(
            relays = listOf(relayOne, relayTwo),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )

        val numEvents = 10_000
        for (i in 1..numEvents) {
            val event = FakeEvent(description = "event num $i")
            telemeter.record(event)
        }
        assertTrue(relayOneProcessCount == numEvents && relayTwoProcessCount == numEvents)
    }

    @Test
    fun `GIVEN faceted telemeter WHEN event recorded THEN facets are added to incoming events`() {
        class TelemeterFacet : Facet

        var facetIsPresent = false
        val relay = FakeRelay { event ->
            facetIsPresent = event.facets.any { facet -> facet is TelemeterFacet }
        }

        val telemeter = Telemeter.build(
            relays = listOf(relay),
            facets = listOf(TelemeterFacet()),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )

        val event = FakeEvent()
        telemeter.record(event)

        assertTrue(facetIsPresent)
    }

    // Please don't actually do this. In a perfect world, no mutable data would enter the pipeline
    // and relays would avoid trying to mutate data
    @Test
    fun `GIVEN mutable facet WHEN facet is mutated downstream THEN changes are propagated`() {
        class MutableFacet(var mutableField: String) : Facet

        val mutatedString = "i'm a mutation"
        val mutatingRelay = FakeRelay {
            it.facets.filterIsInstance<MutableFacet>().forEach { facet ->
                facet.mutableField = mutatedString
            }
        }

        var facetIsMutated = false
        val mutationCheckingRelay = FakeRelay {
            facetIsMutated = it.facets.filterIsInstance<MutableFacet>().any { facet ->
                facet.mutableField == mutatedString
            }
        }

        val telemeter = Telemeter.build(
            relays = listOf(mutatingRelay, mutationCheckingRelay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )

        val event = FakeEvent("", listOf(MutableFacet("i'm mutating")))

        telemeter.record(event)
        assertTrue(facetIsMutated)
    }

    @Test
    fun `GIVEN parent telemeter WHEN child telemeter created THEN child can attach additional facets scoped to child`() =
        runBlockingTest {
            class ParentFacet : Facet
            class ChildFacet : Facet

            val recordedEvents = mutableListOf<Event>()
            val fakeRelay = FakeRelay {
                recordedEvents.add(it)
            }
            val parentFacet = ParentFacet()
            val childFacet = ChildFacet()
            val parent = Telemeter.build(
                flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
                relays = listOf(fakeRelay),
                facets = listOf(parentFacet),
            )
            val child = parent.child(facets = listOf(childFacet))

            val event = FakeEvent()
            parent.record(event)
            child.record(event)

            assertEquals(1, recordedEvents[0].facets.size)
            assertEquals(parentFacet, recordedEvents[0].facets[0])
            assertEquals(2, recordedEvents[1].facets.size)
            assertEquals(parentFacet, recordedEvents[1].facets[0])
            assertEquals(childFacet, recordedEvents[1].facets[1])
        }

    @Test
    fun `GIVEN telemeter with default flow config WHEN event recorded by relay with long running process function THEN shorter relay processing not blocked`() {
        var completedLongRelayJobs = 0
        val longRelay = FakeRelay {
            delay(500)
            completedLongRelayJobs += 1
        }
        var completedShortRelayJobs = 0
        val shortRelay = FakeRelay {
            completedShortRelayJobs += 1
        }

        val telemeter = Telemeter.build(
            relays = listOf(longRelay, shortRelay),
            facets = listOf(),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )

        val numEvents = 10_000
        for (i in 1..numEvents) telemeter.record(FakeEvent())

        assertTrue(completedShortRelayJobs > completedLongRelayJobs)
        scope.advanceUntilIdle()
        assertEquals(numEvents, completedShortRelayJobs)
    }

    @Test
    fun `GIVEN telemeter with default shared flow config WHEN event recorded by relay with long running process function THEN all events processed`() {
        var completedLongRelayJobs = 0
        val longRelay = FakeRelay {
            delay(500)
            completedLongRelayJobs += 1
        }
        var completedShortRelayJobs = 0
        val shortRelay = FakeRelay {
            completedShortRelayJobs += 1
        }

        val telemeter = Telemeter.build(
            relays = listOf(longRelay, shortRelay),
            facets = listOf(),
            flowConfig = Telemeter.defaultSharedFlowConfig.copy(scope = scope),
        )

        val numEvents = 10_000
        for (i in 1..numEvents) telemeter.record(FakeEvent())

        assertTrue(completedShortRelayJobs > completedLongRelayJobs)
        scope.advanceUntilIdle()
        assertEquals(numEvents, completedShortRelayJobs)
        assertEquals(numEvents, completedLongRelayJobs)
    }

    @Test
    fun `GIVEN child telemeter with additional relay WHEN event recorded THEN additional relay will receive events`() {
        val childFacet = object : Facet {}
        val parent = Telemeter.build(
            relays = listOf(),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )

        var childProcessed = false
        val childRelay = FakeRelay {
            println(it)
            childProcessed = true
        }
        val child = parent.child(
            listOf(childRelay),
            listOf(childFacet),
        )

        child.record(FakeEvent())
        assertTrue(childProcessed)
    }

    /**
     * This might not be desired behavior, but ultimately users should take care not to add repeated
     * relays.
     */
    @Test
    fun `GIVEN parent and child telemeter with identical relay added to both WHEN events recorded THEN processed twice`() {
        var numProcessed = 0

        class RepeatedRelay : Relay {
            override suspend fun process(event: Event) {
                numProcessed += 1
                println(event)
            }
        }

        val parent = Telemeter.build(
            relays = listOf(RepeatedRelay()),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )
        val child = parent.child(relays = listOf(RepeatedRelay()))

        child.record(FakeEvent())
        assertTrue(numProcessed == 2)
    }

    @Test
    fun `GIVEN event to be recorded WHEN passed additional facets THEN facets are relayed`() {
        val recorded = mutableListOf<Event>()
        val fakeRelay = FakeRelay {
            if (it.facets.any { facet -> facet is Prefix.App }) {
                recorded.add(it)
            }
        }

        val telemeter = Telemeter.build(
            relays = listOf(fakeRelay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )

        val additionalFacet = Prefix.App("")
        val event = object : Event {
            override val description: String = "test"
            override val facets: List<Facet> = listOf()
        }

        telemeter.record(event, listOf(additionalFacet))

        assertTrue(recorded[0].facets[0] is Prefix.App)
    }

    @Test
    fun `GIVEN prefixes attached through several children WHEN event recorded THEN prefixes remain in order they were added`() {
        val recorded = mutableListOf<Event>()
        val fakeRelay = FakeRelay {
            recorded.add(it)
        }

        val firstPrefix = Prefix.App("")
        val secondPrefix = Prefix.LocalScope("")
        val thirdPrefix = Prefix.Screen("")
        val grandChild = Telemeter
            .build(
                relays = listOf(fakeRelay),
                facets = listOf(firstPrefix),
                flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
            )
            .child(facets = listOf(secondPrefix))
            .child(facets = listOf(thirdPrefix))

        grandChild.record(
            object : Event {
                override val description: String = ""
                override val facets: List<Facet> = listOf()
            },
        )

        assertEquals(firstPrefix, recorded[0].facets[0])
        assertEquals(secondPrefix, recorded[0].facets[1])
        assertEquals(thirdPrefix, recorded[0].facets[2])
    }

    @Test
    fun `GIVEN computed facet WHEN recorded THEN computation can be run in relay`() {
        var processed = false
        val relay = object : TypedRelay<Facet.Computed<*>> {
            override val type: Class<Facet.Computed<*>> = Facet.Computed::class.java
            override suspend fun processFacet(facet: Facet.Computed<*>) {
                processed = facet.compute() as Boolean
            }
        }

        val telemeter = Telemeter.build(
            relays = listOf(relay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )
        val computedFacet = object : Facet.Computed<Boolean> {
            override val compute: () -> Boolean = {
                true
            }
        }

        telemeter.record(FakeEvent(facets = listOf(computedFacet)))

        assertTrue(processed)
    }

    @Test
    fun `GIVEN lazy facet WHEN recorded THEN lazy value will be result of computation `() {
        var computedCount: Int? = null
        val relay = object : TypedRelay<Facet.Lazy<*>> {
            override val type: Class<Facet.Lazy<*>> = Facet.Lazy::class.java
            override suspend fun processFacet(facet: Facet.Lazy<*>) {
                computedCount = facet.value as Int
            }
        }

        val telemeter = Telemeter.build(
            relays = listOf(relay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )
        val lazyFacet = object : Facet.Lazy<Int>() {
            override val compute: () -> Int = { 1 }
        }

        telemeter.record(FakeEvent(facets = listOf(lazyFacet)))

        assertEquals(1, computedCount)
    }

    @Test
    fun `GIVEN telemeter configured to track thread data WHEN event recorded THEN additional facet is included`() {
        val currentThreadName = Thread.currentThread().name
        val recordedFacets = mutableListOf<Facet>()
        val relay = FakeRelay { recordedFacets.addAll(it.facets) }

        val telemeter = Telemeter.build(
            relays = listOf(relay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(
                shouldPropagateThreadData = true,
                scope = scope,
            ),
        )

        telemeter.record(FakeEvent())

        val threadData = recordedFacets[0] as ThreadData
        assertEquals(currentThreadName, threadData.threadName)
        assertTrue(threadData.currentStackTrace.isNotEmpty())
    }

    @Test
    fun `GIVEN relay that processes on different thread WHEN event recorded with thread data THEN original thread is preserved`() {
        val currentThreadName = Thread.currentThread().name
        val recordedFacets = mutableListOf<Facet>()
        var processedThread = ""
        val relay = FakeRelay {
            scope.launch {
                withContext(Dispatchers.IO) {
                    recordedFacets.addAll(it.facets)
                    processedThread = Thread.currentThread().name
                }
            }
        }

        val telemeter = Telemeter.build(
            relays = listOf(relay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(
                shouldPropagateThreadData = true,
                scope = scope,
            ),
        )

        telemeter.record(FakeEvent())

        while (processedThread.isEmpty()) Unit
        val threadData = recordedFacets[0] as ThreadData
        assertEquals(currentThreadName, threadData.threadName)
        assertTrue(processedThread != "" && processedThread != threadData.threadName)
    }

    @Test
    fun `GIVEN telemeter tree with more than one node configured to record thread data WHEN event recorded THEN only one thread data recorded`() {
        val recordedFacets = mutableListOf<Facet>()
        val relay = FakeRelay { recordedFacets.addAll(it.facets) }

        val child = Telemeter.build(
            relays = listOf(relay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(
                shouldPropagateThreadData = true,
                scope = scope,
            ),
        ).child(listOf())

        child.record(FakeEvent())

        val threadDataFacets = recordedFacets.filterIsInstance<ThreadData>()
        assertEquals(1, threadDataFacets.size)
    }

    @Test
    fun `GIVEN telemeter with relay WHEN relay throws THEN telemeter catches and records error without looping`() {
        val recorded = mutableListOf<Event>()
        val goodRelay = FakeRelay {
            recorded.add(it)
        }
        val exception = NullPointerException()
        val badRelay = FakeRelay {
            // This would loop if the implementation didn't prevent it
            throw exception
        }

        val telemeter = Telemeter.build(
            relays = listOf(badRelay, goodRelay),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )

        telemeter.record(FakeEvent())

        val failureEvents = recorded.filter { it.facets.any { facet -> facet is Failure } }
        val failureFacet = failureEvents[0].facets[0] as Failure
        assertEquals(exception, failureFacet.throwable)
    }

    @Test
    fun `Given telemeter with facetResolvers, When record is called with unresolved facets, Then they should be resolved`() {
        val recorded = mutableListOf<Event>()
        val recordingRelay = FakeRelay {
            recorded.add(it)
        }

        class TestUnresolvedFacet : UnresolvedFacet {
            val badNumber = 2
        }

        data class ResolvedFacet(val goodNumber: Int) : Facet

        class TestFacetResolver : FacetResolver {
            override fun getType(): Class<*> = TestUnresolvedFacet::class.java

            override fun resolve(unresolvedFacet: UnresolvedFacet): List<Facet> =
                if (unresolvedFacet is TestUnresolvedFacet) {
                    listOf(ResolvedFacet(unresolvedFacet.badNumber * 2))
                } else {
                    listOf(unresolvedFacet)
                }
        }

        val testFacetResolver = TestFacetResolver()

        val telemeter = Telemeter.build(
            relays = listOf(recordingRelay),
            facetResolvers = mapOf(testFacetResolver.getType() to testFacetResolver),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )

        val numEvents = 10
        for (i in 1..numEvents) {
            val event = FakeEvent(description = "event num $i", listOf(TestUnresolvedFacet()))
            telemeter.record(event)
        }

        recorded.forEach {
            assert(it.facets.filterIsInstance<UnresolvedFacet>().isEmpty())

            assert(it.facets.first() is ResolvedFacet)
            assert((it.facets.first() as ResolvedFacet).goodNumber == 4)
        }
    }

    @Test
    fun `Given telemeter with a facetResolver that returns two facets, When record is called with an unresolved facet, Then return 2 resolved facets`() {
        val recorded = mutableListOf<Event>()
        val recordingRelay = FakeRelay {
            recorded.add(it)
        }

        class TestUnresolvedFacet : UnresolvedFacet {
            val badNumber = 2
        }

        data class ResolvedFacet(val goodNumber: Int) : Facet

        class TestFacetResolver : FacetResolver {
            override fun getType(): Class<*> = TestUnresolvedFacet::class.java

            override fun resolve(unresolvedFacet: UnresolvedFacet): List<Facet> =
                if (unresolvedFacet is TestUnresolvedFacet) {
                    listOf(
                        ResolvedFacet(unresolvedFacet.badNumber * 2),
                        ResolvedFacet(unresolvedFacet.badNumber * unresolvedFacet.badNumber * unresolvedFacet.badNumber),
                    )
                } else {
                    listOf(unresolvedFacet)
                }
        }

        val testFacetResolver = TestFacetResolver()

        val telemeter = Telemeter.build(
            relays = listOf(recordingRelay),
            facetResolvers = mapOf(testFacetResolver.getType() to testFacetResolver),
            flowConfig = Telemeter.defaultTelemetryFlowConfig.copy(scope = scope),
        )

        val numEvents = 10
        for (i in 1..numEvents) {
            val event = FakeEvent(description = "event num $i", listOf(TestUnresolvedFacet()))
            telemeter.record(event)
        }

        recorded.forEach {
            assert(it.facets.filterIsInstance<UnresolvedFacet>().isEmpty())

            assert(it.facets.first() is ResolvedFacet)
            assert((it.facets.first() as ResolvedFacet).goodNumber == 4)
            assert(it.facets[1] is ResolvedFacet)
            assert((it.facets[1] as ResolvedFacet).goodNumber == 8)
        }
    }
}
