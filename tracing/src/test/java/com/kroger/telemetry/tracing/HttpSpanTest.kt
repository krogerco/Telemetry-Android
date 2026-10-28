/**
 * MIT License
 *
 * Copyright (c) 2026 The Kroger Co. All rights reserved.
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
@file:OptIn(ExperimentalTracingApi::class)

package com.kroger.telemetry.tracing

import com.kroger.telemetry.facet.Failure
import com.kroger.telemetry.facet.Significance
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.SANITIZER_THREW
import com.kroger.telemetry.tracing.util.FakeTelemeter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

internal class HttpSpanTest {
    private val telemeter = FakeTelemeter()

    private fun tracer(sanitizer: UrlSanitizer = UrlSanitizer.Default): Tracer =
        Tracer.build {
            telemeter = this@HttpSpanTest.telemeter
            urlSanitizer = sanitizer
        }

    private val trace: TraceFacet get() = telemeter.traces.single()

    /** Runs [block] with one HTTP span under a root, then returns the HTTP span's snapshot. */
    private fun request(
        tracer: Tracer = tracer(),
        method: String = "GET",
        url: String = "https://example.com/a",
        attributes: Map<String, Any?> = emptyMap(),
        block: (HttpRequestSpan) -> Unit = {},
    ): SpanData {
        tracer.trace("root") { root ->
            val span = root.startHttpRequest(method, url, attributes)
            block(span)
            span.end()
        }
        return trace.spans.single { it.http != null }
    }

    // UrlSanitizer.Default: OTel-style redaction

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock =
            """
            https://u:p@h:8443/a?q=1#f                        | https://REDACTED:REDACTED@h:8443/a?q=1
            https://u@host/p                                  | https://REDACTED@host/p
            https://a@b@host/p                                | https://REDACTED@host/p
            https://host/a@b/c                                | https://host/a@b/c
            https://host/path                                 | https://host/path
            https://host/p#frag?notquery                      | https://host/p
            https://host/search?q=milk&page=2                 | https://host/search?q=milk&page=2
            https://host/p?token=abc&q=milk                   | https://host/p?token=REDACTED&q=milk
            https://host/p?access_token=a&refresh_token=b     | https://host/p?access_token=REDACTED&refresh_token=REDACTED
            https://host/p?password=hunter2                   | https://host/p?password=REDACTED
            https://host/p?Token=abc                          | https://host/p?Token=abc
            https://host/p?mytoken=abc&token                  | https://host/p?mytoken=abc&token
            https://host/p?token=                             | https://host/p?token=REDACTED
            https://host/p?a=1&&token=x                       | https://host/p?a=1&&token=REDACTED
            host/path?token=x                                 | host/path?token=REDACTED
            u@host/path                                       | u@host/path
            mailto:user@example.com                           | mailto:user@example.com""",
    )
    fun `GIVEN Default WHEN sanitizing THEN credentials and built-in secret parameters are redacted, and the rest is kept`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, UrlSanitizer.Default.sanitize(input))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock =
            """
            https://h/?%74oken=SECRET                         | https://h/?%74oken=REDACTED
            https://h/?acc%65ss_token=S&q=1                   | https://h/?acc%65ss_token=REDACTED&q=1
            https://h/?a=1;token=SECRET                       | https://h/?a=1;token=REDACTED
            https://h/?q=1;refresh_token=S&password=P         | https://h/?q=1;refresh_token=REDACTED&password=REDACTED
            https://h/?token=prefix;PRIVATE_SUFFIX&ok=1       | https://h/?token=REDACTED&ok=1
            https://h/?q=milk;token=literal                   | https://h/?q=milk;token=REDACTED
            https://h/?;;flag;token=SECRET&ok=1               | https://h/?;;flag;token=REDACTED&ok=1
            https://h/?q=token=literal;flag;token=SECRET       | https://h/?q=token=literal;flag;token=REDACTED
            https://h/?a=1;;flag;z=2;;                        | https://h/?a=1;;flag;z=2;;
            https://h/?;flag;;                               | https://h/?;flag;;
            https://h/?a=1;b=2;password=p;c=3&d=4             | https://h/?a=1;b=2;password=REDACTED&d=4
            https://h/?%zztoken=keep                          | https://h/?%zztoken=keep
            https://h/?tok%en=keep                            | https://h/?tok%en=keep""",
    )
    fun `GIVEN encoded or semicolon-separated parameters WHEN Default sanitizes THEN names are matched decoded`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, UrlSanitizer.Default.sanitize(input))
    }

    @Test
    fun `GIVEN a non-ASCII vendor key WHEN its name is literal or percent-encoded THEN both are redacted`() {
        val sanitizer = UrlSanitizer.redacting("clé\uD83D\uDD11")

        assertEquals("https://h/?clé\uD83D\uDD11=REDACTED", sanitizer.sanitize("https://h/?clé\uD83D\uDD11=s"))
        assertEquals("https://h/?cl%C3%A9%F0%9F%94%91=REDACTED", sanitizer.sanitize("https://h/?cl%C3%A9%F0%9F%94%91=s"))
    }

    @Test
    fun `GIVEN a key spelled with a non-ASCII digit WHEN matched THEN it's compared literally, not decoded`() {
        val url = "https://h/?%\uFF174oken=keep"

        assertEquals(url, UrlSanitizer.Default.sanitize(url))
        assertEquals("https://h/?%\uFF178=REDACTED", UrlSanitizer.redacting("%\uFF178").sanitize("https://h/?%\uFF178=PRIVATE"))
    }

    @Test
    fun `GIVEN a vendor key with a space WHEN its name is plus-encoded THEN it is still redacted`() {
        assertEquals("https://h/?api+key=REDACTED", UrlSanitizer.redacting("api key").sanitize("https://h/?api+key=SECRET"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["api;key", "api%3Bkey", "%61pi;key", "api;%6Bey"])
    fun `GIVEN a vendor key containing a semicolon WHEN sanitizing THEN its entire value is redacted`(name: String) {
        val sanitizer = UrlSanitizer.redacting("api;key")

        assertEquals(
            "https://h/?$name=REDACTED&ok=1",
            sanitizer.sanitize("https://h/?$name=PRIVATE_VALUE;PRIVATE_SUFFIX&ok=1"),
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock =
            """
            //alice:SECRET@[::1]:443/a?token=t                | //REDACTED:REDACTED@[::1]:443/a?token=REDACTED
            //alice@host/a                                    | //REDACTED@host/a
            /cache/http://alice@bob?x=1                       | /cache/http://alice@bob?x=1
            relative/https://u:p@h/x                          | relative/https://u:p@h/x
            https://u:p@host?token=x                          | https://REDACTED:REDACTED@host?token=REDACTED
            HTTPS://u:p@host/                                 | HTTPS://REDACTED:REDACTED@host/
            a+b.c-d://u@host/                                 | a+b.c-d://REDACTED@host/""",
    )
    fun `GIVEN authority edge cases WHEN Default sanitizes THEN only a real leading authority's user info is redacted`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, UrlSanitizer.Default.sanitize(input))
    }

    @Test
    fun `GIVEN the public default parameter set WHEN code tries to change it THEN it can't, and redacting stays protected`() {
        @Suppress("UNCHECKED_CAST")
        val asMutable = UrlSanitizer.DefaultRedactedParameters as MutableSet<String>

        assertThrows<UnsupportedOperationException> { asMutable.remove("token") }
        assertThrows<UnsupportedOperationException> { asMutable.add("other") }
        assertEquals("https://h/?token=REDACTED", UrlSanitizer.redacting("sig").sanitize("https://h/?token=S"))
    }

    @Test
    fun `GIVEN vendor keys WHEN redacting THEN they're redacted in addition to the built-in keys`() {
        val sanitizer = UrlSanitizer.redacting("X-Amz-Signature", "sig")

        assertEquals(
            "https://REDACTED@h/p?X-Amz-Signature=REDACTED&sig=REDACTED&token=REDACTED&x-amz-signature=keep&q=1",
            sanitizer.sanitize("https://user@h/p?X-Amz-Signature=s&sig=t&token=u&x-amz-signature=keep&q=1#f"),
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        textBlock =
            """
            https://u:p@h:8443/a?q=1#f       | https://h:8443/a
            https://u@host                   | https://host
            https://host/a@b/c               | https://host/a@b/c
            https://host/p?q=1@evil          | https://host/p
            host/path?q=1                    | host/path
            /relative/path?q=1               | /relative/path""",
    )
    fun `GIVEN StripQuery WHEN sanitizing THEN user info, query and fragment are removed`(
        input: String,
        expected: String,
    ) {
        assertEquals(expected, UrlSanitizer.StripQuery.sanitize(input))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "://", "?", "#", "@", "://@", "https://", "https://@", "https:///", "a@b:///x", "\u0000", "http://[::1",
            "?=", "?&&", "?token", "?=x&token=",
        ],
    )
    fun `GIVEN malformed input WHEN the built-ins sanitize THEN they never throw and never keep a fragment`(input: String) {
        for (sanitizer in listOf(UrlSanitizer.Default, UrlSanitizer.StripQuery, UrlSanitizer.redacting("k"))) {
            assertFalse('#' in sanitizer.sanitize(input), input)
        }
        assertFalse('?' in UrlSanitizer.StripQuery.sanitize(input))
    }

    @Test
    fun `GIVEN None WHEN sanitizing THEN the URL is unchanged`() {
        val url = "https://u:p@h/a?q=1#f"

        assertEquals(url, UrlSanitizer.None.sanitize(url))
        assertEquals(url, request(tracer(UrlSanitizer.None), url = url).http?.url)
    }

    @Test
    fun `GIVEN the default tracer WHEN a request starts THEN the stored URL is redacted, keeping the query`() {
        val data = request(url = "https://user:secret@example.com/search?q=milk&token=t1#top")

        assertEquals("https://REDACTED:REDACTED@example.com/search?q=milk&token=REDACTED", data.http?.url)
    }

    @Test
    fun `GIVEN a sanitized URL over 1,024 chars WHEN stored THEN it is truncated`() {
        val data = request(tracer(UrlSanitizer.None), url = "https://h/" + "p".repeat(2_000))

        assertEquals(1_024, data.http?.url?.length)
    }

    // A throwing sanitizer

    @Test
    fun `GIVEN a throwing sanitizer WHEN a request starts THEN the URL is omitted and appears nowhere`() {
        val secret = "token=SECRET"
        val raw = "https://user:pw@example.com/a?$secret"
        val throwing = UrlSanitizer { url -> throw IllegalArgumentException("bad url: $url") }

        val data = request(tracer(throwing), url = raw)

        assertEquals("", data.http?.url)
        val diagnostic = telemeter.diagnostics.single()
        assertEquals(SANITIZER_THREW, diagnostic.kind)
        assertNull(diagnostic.throwable)
        assertEquals(throwing.javaClass.name, diagnostic.source)
        assertTrue("IllegalArgumentException" in diagnostic.message, diagnostic.message)
        val event = telemeter.events.single { it.facets.any { facet -> facet is TracingDiagnostic } }
        assertEquals(listOf(diagnostic, Significance.WARNING), event.facets)
        assertTrue(event.facets.none { it is Failure })
        // Neither the exported trace nor any recorded event mentions the raw URL.
        val everything =
            telemeter.events.joinToString("\n") { it.description + it.facets.joinToString() } +
                trace.spans.joinToString { "$it ${it.http} ${it.http?.url} ${it.attributes}" } +
                diagnostic.message + diagnostic.source
        assertFalse("SECRET" in everything || "pw@" in everything || "example.com" in everything)
    }

    @Test
    fun `GIVEN a stricter custom sanitizer WHEN it throws THEN no weaker sanitizer runs in its place`() {
        // A sanitizer that templates identifiers in paths, failing on a shape it doesn't recognise.
        val templating =
            UrlSanitizer { url ->
                require("/orders/" in url) { "unrecognised" }
                url.replace(Regex("/patients/[^/]+"), "/patients/{id}")
            }

        val data = request(tracer(templating), url = "https://h/patients/private-patient-id/visits")

        assertEquals("", data.http?.url)
        assertFalse("private-patient-id" in trace.toString() + trace.spans.joinToString { "${it.http}" })
    }

    @Test
    fun `GIVEN a sanitizer that returns null from Java WHEN a request starts THEN the URL is omitted`() {
        // Simulates a Java implementation returning null, which Kotlin's type system can't express directly.
        val returnsNull = UrlSanitizer { nullAs<String>() }

        val data = request(tracer(returnsNull), url = "https://h/a?token=t")

        assertEquals("", data.http?.url)
        assertEquals(SANITIZER_THREW, telemeter.diagnostics.single().kind)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> nullAs(): T = null as T

    // Naming and method

    @Test
    fun `GIVEN a lower-case method WHEN a request starts THEN the name is HTTP GET and the method is upper-cased`() {
        val data = request(method = "get", url = "https://example.com/users/123")

        assertEquals("HTTP GET", data.name)
        assertEquals("GET", data.http?.method)
        assertFalse("example" in data.name)
    }

    @Test
    fun `GIVEN a method over 16 chars WHEN a request starts THEN the method is truncated after upper-casing`() {
        val data = request(method = "m".repeat(20))

        assertEquals("M".repeat(16), data.http?.method)
        assertEquals("HTTP " + "M".repeat(16), data.name)
    }

    @Test
    fun `GIVEN initial attributes WHEN a request starts THEN they reach SpanData`() {
        val data = request(attributes = mapOf("retry" to 1, "cached" to false, "skip" to null))

        assertEquals(mapOf("retry" to 1L, "cached" to false), data.attributes)
    }

    @Test
    fun `GIVEN a request span WHEN it is used like any span THEN children, events and attributes work`() {
        request { span ->
            span.setAttribute("bytes", 10)
            span.addEvent("retry")
            span.startChild("decode").end()
        }

        val http = trace.spans.single { it.http != null }
        assertEquals(mapOf("bytes" to 10L), http.attributes)
        assertEquals(listOf("retry"), http.events.map { it.name })
        assertEquals(http.spanId, trace.spans.single { it.name == "decode" }.parentSpanId)
    }

    @Test
    fun `GIVEN a non-HTTP span WHEN exported THEN http is null`() {
        tracer().trace("root") { it.child("step") { } }

        assertTrue(trace.spans.all { it.http == null })
    }

    // Response status

    @Test
    fun `GIVEN no response WHEN exported THEN statusCode is null and the status is Ok`() {
        val data = request()

        assertNull(data.http?.statusCode)
        assertEquals(SpanStatus.Ok, data.status)
    }

    @ParameterizedTest
    @CsvSource("200, Ok", "304, Ok", "399, Ok", "400, Error", "404, Error", "503, Error")
    fun `GIVEN a response code WHEN exported THEN 400 and above read as Error`(
        code: Int,
        expected: String,
    ) {
        val data = request { it.setResponse(code) }

        assertEquals(code, data.http?.statusCode)
        val expectedStatus = if (expected == "Ok") SpanStatus.Ok else SpanStatus.Error("HTTP $code")
        assertEquals(expectedStatus, data.status)
    }

    @Test
    fun `GIVEN an explicit Ok WHEN a 404 arrives THEN the status stays Ok`() {
        val data =
            request { span ->
                span.setStatus(SpanStatus.Ok)
                span.setResponse(404)
            }

        assertEquals(SpanStatus.Ok, data.status)
        assertEquals(404, data.http?.statusCode)
    }

    @Test
    fun `GIVEN a 500 WHEN a later 200 replaces it THEN the status is Ok`() {
        val data =
            request { span ->
                span.setResponse(500)
                span.setResponse(200)
            }

        assertEquals(SpanStatus.Ok, data.status)
        assertEquals(200, data.http?.statusCode)
    }

    @Test
    fun `GIVEN a 500 and no explicit status WHEN the span is truncated THEN it is Cancelled`() {
        tracer().trace("root") { root ->
            val step = root.startChild("step")
            step.startHttpRequest("GET", "https://h/a").setResponse(500)
            step.end()
        }

        val data = trace.spans.single { it.http != null }
        assertTrue(data.isTruncated)
        assertEquals(SpanStatus.Cancelled, data.status)
        assertEquals(500, data.http?.statusCode)
    }

    @Test
    fun `GIVEN an explicit Error WHEN a 200 arrives THEN the explicit status wins`() {
        val data =
            request { span ->
                span.setStatus(SpanStatus.Error("IOException"))
                span.setResponse(200)
            }

        assertEquals(SpanStatus.Error("IOException"), data.status)
    }

    @Test
    fun `GIVEN an ended request WHEN a response arrives THEN it is ignored`() {
        tracer().trace("root") { root ->
            val span = root.startHttpRequest("GET", "https://h/a")
            span.end()
            span.setResponse(503)
        }

        assertNull(
            trace.spans
                .single { it.http != null }
                .http
                ?.statusCode,
        )
    }

    // Propagation headers (merging across listeners is tested with the realtime tier)

    @Test
    fun `GIVEN no listeners WHEN headers are requested THEN they are empty, open or ended`() {
        tracer().trace("root") { root ->
            val span = root.startHttpRequest("GET", "https://h/a")
            assertEquals(emptyMap<String, String>(), span.propagationHeaders())
            span.end()
            assertEquals(emptyMap<String, String>(), span.propagationHeaders())
        }
    }

    @Test
    fun `GIVEN an inert parent WHEN a request starts THEN it is inert`() {
        val fromNoOp = Tracer.NoOp.startTrace("root").startHttpRequest("GET", "https://h/a")
        val tracer = tracer()
        val root = tracer.startTrace("root")
        root.end()
        val fromEnded = root.startHttpRequest("GET", "https://h/a")

        listOf(fromNoOp, fromEnded).forEach { span ->
            assertEquals(Ids.INVALID_SPAN_ID, span.spanId)
            span.setResponse(500)
            assertEquals(emptyMap<String, String>(), span.propagationHeaders())
        }
    }
}
