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

package com.kroger.sample_app

import android.app.Application
import android.content.Context
import com.kroger.telemetry.Event
import com.kroger.telemetry.android.facet.ToastFacet
import com.kroger.telemetry.facet.Facet
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

@HiltAndroidApp
class SampleApplication : Application() {
    @Inject
    lateinit var appTelemeter: AppTelemeter

    override fun onCreate() {
        super.onCreate()
        appTelemeter.record(ApplicationStartupEvent("init finished", toast = true))
    }
}

data class ApplicationStartupEvent(val message: String, val toast: Boolean = false) : Event {
    override val description = message
    override val facets: List<Facet> = if (!toast) listOf() else listOf(ToastFacet(message))
}

@InstallIn(SingletonComponent::class)
@Module
object TelemeterModule {
    @Provides
    fun provideContext(@ApplicationContext context: Context): Context = context
}
