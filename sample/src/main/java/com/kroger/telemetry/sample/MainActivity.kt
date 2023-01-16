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

package com.kroger.telemetry.sample

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.kroger.telemetry.Event
import com.kroger.telemetry.Relay
import com.kroger.telemetry.Telemeter
import com.kroger.telemetry.child
import com.kroger.telemetry.contextaware.ContextAwareFacetResolver
import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.facet.Prefix
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.sample.databinding.ActivityMainBinding
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.components.ActivityComponent
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    @Inject
    lateinit var telemeter: ModuleOneTelemeter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        /*
        The events recorded by the telemeter in this activity will be processed
        by the ActivityRelay, because the MainActivity.prefix is attached by the
        to the child telemeter created during injection.

        The FooRelay, LogRelay, BarRelay, and ToastRelay attached to the parent telemeter will also process them.
        Each event has BarFacets added to it as defined by the event class hierarchy, so the BarRelay will get all of them.
        The ToastRelay will only use the event with the Significance.INTERNAL_ERROR facet due to its configuration.
        The LogRelay and FooRelay will process everything.
         */
        telemeter.record(ActivityEvent.Created)

        binding.eventButton.setOnClickListener {
            telemeter.record(ActivityEvent.ButtonClicked(it.tag.toString()))
        }
        binding.catastropheButton.setOnClickListener {
            val event = ActivityEvent.ButtonClicked(it.tag.toString())
            telemeter.record(event, withFacets = listOf(Significance.INTERNAL_ERROR))
        }
    }

    companion object {
        internal val prefix = Prefix.Screen("Sample App Main Activity")
    }
}

sealed class ActivityEvent : Event {
    protected val activityFacets = listOf(
        BarFacet("some bar:data from the activity"),
    )

    object Created : ActivityEvent() {
        override val description = "Activity Created"
        override val facets = activityFacets
    }

    class ButtonClicked(buttonTag: String) : ActivityEvent() {
        override val description: String = "$buttonTag clicked"
        override val facets: List<Facet> =
            activityFacets + listOf(
                StringResourceFormattedToastFacet(
                    R.string.button_click_message,
                    buttonTag,
                ),
            )
    }
}

class ActivityRelay : Relay {
    override suspend fun process(event: Event) {
        for (screen in event.facets.filterIsInstance(Prefix.Screen::class.java)) {
            if (screen == MainActivity.prefix) {
                Log.d("Telemetry - Activity", "ActivityRelay processed: ${event.description}")
            }
        }
    }
}

class ModuleOneTelemeter(
    telemeter: Telemeter,
    contextAwareFacetResolver: ContextAwareFacetResolver,
) :
    Telemeter by telemeter.child(
        relays = listOf(ActivityRelay()),
        facets = listOf(MainActivity.prefix),
        facetResolvers = mapOf(StringResourceFormattedToastFacet::class.java to contextAwareFacetResolver),
    )

@InstallIn(ActivityComponent::class)
@Module
object ModuleOneModule {
    @Provides
    fun provideModuleOneTelemeter(
        appTelemeter: AppTelemeter,
        contextAwareFacetResolver: ContextAwareFacetResolver,
    ): ModuleOneTelemeter =
        ModuleOneTelemeter(appTelemeter, contextAwareFacetResolver)
}
