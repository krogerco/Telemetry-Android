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

package com.kroger.telemetry.tracing

import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * Decides what part of a request URL tracing may store. It runs before any HTTP span data exists.
 *
 * The default follows OpenTelemetry's HTTP conventions: it redacts credentials and known secret parameters, and keeps
 * the rest of the URL, query included, so relays have the full context of a request. Exported traces can then carry
 * personal data from query strings, such as search terms; a relay that sends traces off the device decides what its
 * backend may see, and should scrub accordingly.
 */
@ExperimentalTracingApi
public fun interface UrlSanitizer {
    /**
     * Returns the URL to store. Must not throw; if it does, the URL is omitted (stored as "") and a diagnostic is
     * reported. Nothing weaker runs in its place, so a stricter sanitizer's policy is never undone.
     */
    public fun sanitize(url: String): String

    public companion object {
        /**
         * Query parameters whose values [Default] redacts. Unmodifiable. Matched case-sensitively, like OpenTelemetry,
         * against each parameter's decoded name, so `%74oken=` and `token=` are both redacted.
         */
        public val DefaultRedactedParameters: Set<String> =
            Collections.unmodifiableSet(linkedSetOf("token", "access_token", "refresh_token", "password"))

        /**
         * Redacts credentials and the values of [DefaultRedactedParameters], keeps the rest, and drops the fragment
         * (it's never sent to the server): `https://u:p@host/a?q=milk&token=t#f` becomes
         * `https://REDACTED:REDACTED@host/a?q=milk&token=REDACTED`. Never throws.
         *
         * For vendor-specific secrets, such as signed-URL signatures, use [redacting].
         */
        public val Default: UrlSanitizer = redacting()

        /**
         * Removes user info, query and fragment entirely: `https://u:p@host:8443/a?q=1#f` becomes
         * `https://host:8443/a`. For apps that want privacy at the source. Never throws.
         */
        public val StripQuery: UrlSanitizer = UrlSanitizer(::stripUserInfoQueryAndFragment)

        /** Keeps the URL unchanged, credentials included. Opt in deliberately. */
        public val None: UrlSanitizer = UrlSanitizer { it }

        /**
         * Like [Default], and also redacts the values of [parameters], e.g.
         * `UrlSanitizer.redacting("X-Amz-Signature", "X-Amz-Credential")`. The keys add to
         * [DefaultRedactedParameters]; they never replace it. Matched case-sensitively against decoded names, so give
         * them unencoded (`"api key"` matches `api+key=` and `api%20key=`). Never throws.
         */
        public fun redacting(vararg parameters: String): UrlSanitizer {
            val keys = DefaultRedactedParameters + parameters
            return UrlSanitizer { url -> redact(url, keys) }
        }
    }
}

private const val REDACTED = "REDACTED"

/** Pure string operations, so malformed input can't throw the way `java.net.URI` would. */
private fun redact(
    url: String,
    keys: Set<String>,
): String {
    val withoutFragment = url.substringBefore('#')
    val queryStart = withoutFragment.indexOf('?')
    val base = if (queryStart < 0) withoutFragment else withoutFragment.substring(0, queryStart)
    val redactedBase = redactUserInfo(base)
    if (queryStart < 0) return redactedBase
    return redactedBase + "?" + redactQuery(withoutFragment.substring(queryStart + 1), keys)
}

/**
 * `user:password@` becomes `REDACTED:REDACTED@`, and `user@` becomes `REDACTED@`, in a leading authority only: one
 * that follows `scheme://` at the start, or a network-path reference starting `//`. A `://` later in a path is data.
 */
private fun redactUserInfo(base: String): String {
    val authority = authorityRange(base) ?: return base
    val at = base.lastIndexOf('@', authority.last)
    if (at < authority.first) return base
    val userInfo = if (':' in base.substring(authority.first, at)) "$REDACTED:$REDACTED" else REDACTED
    return base.substring(0, authority.first) + userInfo + base.substring(at)
}

/**
 * Redacts listed values under both common query grammars. Parameters are split on `&` only, as OkHttp and most servers
 * parse queries, so a listed name redacts its whole value, semicolons included (`token=a;b` keeps no part of `a;b`).
 * Because some servers also split on `;`, a `;name=` later in a parameter whose decoded name is listed is redacted to
 * the end of that parameter too. A value that merely contains `;token=` is therefore over-redacted, never under.
 * Separators, encoding, order and every other parameter are kept as-is.
 */
private fun redactQuery(
    query: String,
    keys: Set<String>,
): String = query.split('&').joinToString("&") { parameter -> redactParameter(parameter, keys) }

private fun redactParameter(
    parameter: String,
    keys: Set<String>,
): String {
    var equals = parameter.indexOf('=')
    if (equals < 0) return parameter
    // In the '&'-only grammar, semicolons can be part of the name.
    if (decodeName(parameter.substring(0, equals)) in keys) {
        return parameter.substring(0, equals + 1) + REDACTED
    }

    var semicolon = parameter.indexOf(';')
    while (semicolon >= 0) {
        val segmentStart = semicolon + 1
        // Reuse a later '=' until its segment is reached, keeping the scan linear.
        if (equals < segmentStart) {
            equals = parameter.indexOf('=', segmentStart)
            if (equals < 0) break
        }
        semicolon = parameter.indexOf(';', segmentStart)
        if ((semicolon < 0 || equals < semicolon) && decodeName(parameter.substring(segmentStart, equals)) in keys) {
            return parameter.substring(0, equals + 1) + REDACTED
        }
    }
    return parameter
}

/**
 * Decodes `+` and `%XX` escapes in a parameter name, as UTF-8. A malformed escape leaves the name undecoded, so this
 * never throws; such a name only matches a key spelled the same way.
 */
private fun decodeName(name: String): String {
    if ('%' !in name && '+' !in name) return name
    val bytes = ByteArrayOutputStream(name.length)
    var i = 0
    while (i < name.length) {
        when (val c = name[i]) {
            '+' -> {
                bytes.write(' '.code)
                i++
            }

            '%' -> {
                val hi = name.getOrNull(i + 1)?.asciiHexDigit()
                val lo = name.getOrNull(i + 2)?.asciiHexDigit()
                if (hi == null || lo == null) return name
                bytes.write(hi * 16 + lo)
                i += 3
            }

            else -> {
                // A surrogate pair is encoded together, so non-BMP characters round-trip.
                val end = if (c.isHighSurrogate() && i + 1 < name.length && name[i + 1].isLowSurrogate()) i + 2 else i + 1
                bytes.write(name.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

private fun stripUserInfoQueryAndFragment(url: String): String {
    var result = url.substringBefore('#').substringBefore('?')
    val authority = authorityRange(result) ?: return result
    val at = result.lastIndexOf('@', authority.last)
    if (at >= authority.first) result = result.substring(0, authority.first) + result.substring(at + 1)
    return result
}

/**
 * The leading authority's index range, or null if there's none or it's empty: after `scheme://` where the scheme starts
 * the string (RFC 3986: a letter, then letters, digits, `+`, `-` or `.`), or after a leading `//`. It ends at the next
 * `/`. A `://` anywhere else is part of a path, not an authority.
 */
private fun authorityRange(url: String): IntRange? {
    val start =
        when {
            url.startsWith("//") -> {
                2
            }

            else -> {
                val schemeEnd = url.indexOf("://")
                if (schemeEnd <= 0 || !isScheme(url, schemeEnd)) return null
                schemeEnd + 3
            }
        }
    val end = url.indexOf('/', start).let { if (it < 0) url.length else it }
    return if (end > start) start until end else null
}

private fun isScheme(
    url: String,
    end: Int,
): Boolean =
    url[0].isAsciiLetter() &&
        (1 until end).all { i -> url[i].let { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' } }

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** Percent escapes use ASCII hex digits only; [Char.digitToIntOrNull] would also accept Unicode digits such as U+FF17. */
private fun Char.asciiHexDigit(): Int? =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }
