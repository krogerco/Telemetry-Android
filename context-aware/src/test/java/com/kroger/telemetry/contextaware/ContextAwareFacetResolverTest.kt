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

package com.kroger.telemetry.contextaware

import android.content.Context
import com.kroger.telemetry.facet.Facet
import com.kroger.telemetry.facet.UnresolvedFacet
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

public class ContextAwareFacetResolverTest {

    private val context: Context = mockk()
    private lateinit var contextAwareFacetResolver: ContextAwareFacetResolver

    @BeforeEach
    public fun setup() {
        contextAwareFacetResolver = ContextAwareFacetResolver(context)
    }

    @Test
    public fun `Given an ContextAwareFacetResolver, When resolve is called on another type of unresolvedFacet, Then return the unresolvedFacet`() {
        val testUnresolvedFacet = object : UnresolvedFacet {}

        val sut = contextAwareFacetResolver.resolve(testUnresolvedFacet)

        assert(sut.first() == testUnresolvedFacet)
    }

    @Test
    public fun `Given an ContextAwareFacetResolver, When resolve is called on a ContextAwareFacet, Then return the resolved Facet`() {
        val testFacet = object : Facet {}
        val testUnresolvedFacet = object : ContextAwareFacet {
            override fun resolve(context: Context): Facet {
                return testFacet
            }
        }

        val sut = contextAwareFacetResolver.resolve(testUnresolvedFacet)

        assert(sut.first() == testFacet)
    }
}
