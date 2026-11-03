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

import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.ATTRIBUTES_UNREADABLE
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.ATTRIBUTE_DROPPED
import com.kroger.telemetry.tracing.TracingDiagnostic.Kinds.LIMIT_EXCEEDED

/**
 * Bounds on library-owned data per trace. Internal by design: changing a value is a release-notes item.
 *
 * A span, attribute or event past a count limit or the text budget is dropped and counted in the export; a status
 * description that doesn't fit the budget is dropped and the status kept. Text past a length cap is truncated, except
 * an attribute key, which is dropped so that two distinct keys can't merge. Each case reports a `LIMIT_EXCEEDED`
 * diagnostic.
 */
internal object TraceLimits {
    const val MAX_SPANS: Int = 256
    const val MAX_ATTRIBUTES: Int = 64

    /**
     * Entries read from one initial attribute map, storable or not; the rest are counted as dropped unread. Bounds the
     * work and memory of a huge map. It's well above [MAX_ATTRIBUTES], so every map that could be stored in full is
     * read in full, and whether an entry fits is decided the same way as for [Span.setAttribute].
     */
    const val MAX_INITIAL_ENTRIES_READ: Int = 4 * MAX_ATTRIBUTES
    const val MAX_EVENTS: Int = 128

    /** Applies to span and event names. */
    const val MAX_NAME_LENGTH: Int = 256
    const val MAX_ATTRIBUTE_KEY_LENGTH: Int = 128
    const val MAX_STRING_VALUE_LENGTH: Int = 1_024
    const val MAX_URL_LENGTH: Int = 1_024
    const val MAX_METHOD_LENGTH: Int = 16
    const val MAX_DESCRIPTION_LENGTH: Int = 512

    /** Chars of budgeted text per trace: names, methods, URLs, attribute keys, string values and status descriptions. */
    const val TEXT_BUDGET: Int = 65_536
}

/**
 * Which limit a `LIMIT_EXCEEDED` diagnostic is about. Each one is deduplicated separately, so hitting one limit
 * early never hides a different limit later: at most one report per limit per tracer.
 */
internal enum class Limit {
    SPANS,
    ATTRIBUTES,
    INITIAL_ENTRIES_READ,
    EVENTS,
    SPAN_NAME,
    EVENT_NAME,
    ATTRIBUTE_KEY,
    ATTRIBUTE_VALUE,
    URL,
    HTTP_METHOD,
    STATUS_DESCRIPTION,
    TEXT_BUDGET,
}

/** Reports a `LIMIT_EXCEEDED` diagnostic, deduplicated by [limit]. */
internal fun PendingDiagnostics.limitExceeded(
    limit: Limit,
    message: String,
): Unit = report(LIMIT_EXCEEDED, message, detail = limit.name)

/** Truncates to at most [max] chars without splitting a surrogate pair, and reports when it does. */
internal fun String.capTo(
    max: Int,
    limit: Limit,
    what: String,
    pending: PendingDiagnostics,
): String {
    if (length <= max) return this
    pending.limitExceeded(limit, "$what truncated to $max chars")
    val end = if (Character.isHighSurrogate(this[max - 1])) max - 1 else max
    return substring(0, end)
}

/**
 * An attribute write after validation and normalization, which needs no lock.
 * [value] is null when the write must be dropped and counted; [cost] is what it charges to the text budget.
 */
internal class NormalizedAttribute(
    val key: String,
    val value: Any?,
    val cost: Int,
)

/**
 * Validates and converts an attribute write to its stored form, as described on [Span.setAttribute]: checks the key
 * length, caps strings, widens numbers and reports anything dropped or truncated. Needs no lock, so it runs before the
 * write takes one. The count limit and text budget are checked later, under the lock.
 *
 * Returns null for a null value, which is skipped without being counted.
 */
internal fun normalizeAttribute(
    key: String,
    value: Any?,
    pending: PendingDiagnostics,
): NormalizedAttribute? {
    if (value == null) return null
    if (key.length > TraceLimits.MAX_ATTRIBUTE_KEY_LENGTH) {
        // Dropped rather than truncated: truncating could merge two distinct keys.
        pending.limitExceeded(Limit.ATTRIBUTE_KEY, "Attribute key longer than ${TraceLimits.MAX_ATTRIBUTE_KEY_LENGTH} chars dropped")
        return NormalizedAttribute(key, null, 0)
    }
    val normalized: Any =
        when (value) {
            is String -> {
                value.capTo(TraceLimits.MAX_STRING_VALUE_LENGTH, Limit.ATTRIBUTE_VALUE, "Attribute value", pending)
            }

            is Long, is Double, is Boolean -> {
                value
            }

            is Int -> {
                value.toLong()
            }

            is Short -> {
                value.toLong()
            }

            is Byte -> {
                value.toLong()
            }

            // Via the decimal form: 0.1f becomes 0.1, not its binary expansion 0.10000000149011612.
            is Float -> {
                value.toString().toDouble()
            }

            is Enum<*> -> {
                value.name.capTo(
                    TraceLimits.MAX_STRING_VALUE_LENGTH,
                    Limit.ATTRIBUTE_VALUE,
                    "Attribute value",
                    pending,
                )
            }

            else -> {
                // Keyed by type, like limits: one unsupported type never hides another. The key isn't included: it's
                // caller-supplied text that could contain a URL or user data.
                pending.report(
                    ATTRIBUTE_DROPPED,
                    "Attribute with unsupported type ${value.javaClass.name} dropped",
                    detail = value.javaClass.name,
                )
                return NormalizedAttribute(key, null, 0)
            }
        }
    return NormalizedAttribute(key, normalized, key.length + ((normalized as? String)?.length ?: 0))
}

/** Adds without overflowing: a dropped counter stops at [Int.MAX_VALUE] rather than wrapping negative. */
internal fun Int.saturatingPlus(other: Int): Int = if (other > Int.MAX_VALUE - this) Int.MAX_VALUE else this + other

/**
 * Reads and normalizes the caller's initial attributes outside the lock. Every call on the map is guarded, `isEmpty`
 * included.
 *
 * Reading stops after [TraceLimits.MAX_INITIAL_ENTRIES_READ] entries, so a huge map costs bounded work and memory.
 * What's read is then stored under the lock by the same count and budget checks as [Span.setAttribute], so an
 * entry that fits is never dropped early. Entries left unread are counted as dropped, using the map's size.
 */
internal fun readInitialAttributes(
    attributes: Map<String, Any?>,
    pending: PendingDiagnostics,
): InitialAttributes {
    val entries = mutableListOf<NormalizedAttribute>()
    var read = 0
    var unread = 0
    try {
        if (attributes.isEmpty()) return InitialAttributes(entries, 0)
        val iterator = attributes.entries.iterator()
        while (iterator.hasNext()) {
            if (read == TraceLimits.MAX_INITIAL_ENTRIES_READ) {
                // The iterator has more, so at least one entry is unread, whatever size claims. Set before
                // reading size, so a size() that throws still leaves the truncation counted.
                unread = 1
                unread = (attributes.size - read).coerceAtLeast(1)
                break
            }
            val entry = iterator.next()
            read++
            // Typed loosely: from Java, the key can be null.
            val key: String? = entry.key
            val normalized = if (key == null) null else normalizeAttribute(key, entry.value, pending)
            if (normalized != null) entries += normalized
        }
    } catch (e: Exception) {
        // The map is foreign code and can throw while being read; keep what was read. An Error is not caught.
        pending.report(
            ATTRIBUTES_UNREADABLE,
            "Initial attributes could not be fully read after $read entries: ${e.javaClass.name}",
            throwable = e,
        )
    }
    return InitialAttributes(entries, unread)
}

/** Initial attributes read outside the lock: the [entries] to store, and how many were left [unread] past the scan bound. */
internal class InitialAttributes(
    val entries: List<NormalizedAttribute>,
    val unread: Int,
)
