# Telemetry Tracing (experimental)

Traces made of spans, exported through the Telemetry pipeline you already use.

A **span** is a timed unit of work with a name, attributes, events and a status. Spans form a tree, and a **trace**
is every span under one root. When a root ends, the whole tree is recorded on your `Telemeter` as one `TraceFacet`,
and any `Relay` can export it.

> **Experimental.** Every tracing type requires opting in to `@ExperimentalTracingApi`. The API and its behaviour
> (defaults, limits, status rules) may change between releases, but never silently: every breaking change is listed
> in the release notes with migration guidance. See [Compatibility](#compatibility).

- [Getting started](#getting-started)
- [Writing traces](#writing-traces)
    - [Structuring spans](#structuring-spans)
    - [Parallel work](#parallel-work)
    - [Status](#status)
    - [Attributes and events](#attributes-and-events)
    - [Passing spans through your layers](#passing-spans-through-your-layers)
    - [HTTP requests](#http-requests)
- [Exporting traces](#exporting-traces)
- [Limits](#limits)
- [Privacy](#privacy)
- [Diagnostics](#diagnostics)
- [Testing](#testing)
- [Realtime listeners (for vendor adapters)](#realtime-listeners-for-vendor-adapters)
- [Compatibility](#compatibility)

---

## Getting started

```kotlin
implementation("com.kroger.telemetry:tracing:<version>")
```

**Create one `Tracer`** for the app, and inject it wherever traces start:

```kotlin
val tracer = Tracer.build { telemeter = appTelemeter }
```

**Trace some work:**

```kotlin
val results = tracer.trace("search", mapOf("mode" to "grid")) { root ->
    val items = root.child("load_items") { fetchItems() }
    root.child("rank") { span ->
        span.setAttribute("count", items.size)
        rank(items)
    }
}
```

When `search` returns, the trace is recorded on `appTelemeter` as an event carrying a `TraceFacet`.

---

## Writing traces

### Structuring spans

**Use `trace {}` and `child {}`.** They always end the span, including on exceptions and non-local returns, and they
set the status from anything thrown (see [Status](#status)).

```kotlin
tracer.trace("checkout") { root ->
    root.child("validate_cart") { validate() }
    root.child("submit") { submit() }
}
```

`startTrace` / `startChild` are the escape hatch for a span that outlives one block, e.g. a screen-load span ended
from a lifecycle callback. You then own `end()`: a root that's never ended is never exported.

Calling `tracer.trace {}` inside another trace starts a separate, independent trace. Use `child {}` to nest.

**Spans nest strictly.** When a span ends, every descendant still open is force-ended at the same moment: it's
exported with `isTruncated = true` and status `Cancelled` (unless you set a status yourself). Sibling branches are
unaffected. After a span ends, writes to it are ignored, and starting a child on it returns an inert span that
records nothing.

In correct code a child never outlives its parent, so a truncated span usually points to a structuring bug, most
often an `async` that wasn't awaited. How to model common cases:

| Scenario | Model it as |
|---|---|
| Unawaited async work | A bug. Use `coroutineScope` inside the span (see below). |
| Fire-and-forget follow-up (an upload started by "checkout") | A separate trace, linked with `origin.trace_id` / `origin.span_id` attributes |
| Scheduled background work (WorkManager) | A separate, linked trace |
| Screen load vs. what keeps loading after it | Siblings under a longer span, or separate traces |
| A long-lived `Flow` | A span per emission or batch, or one span for the whole collection |
| A shared in-flight request | A span for the shared work, outside both callers |

Until span links are added, use the `origin.*` attribute convention to say one trace was caused by another:

```kotlin
tracer.trace("upload_receipt", mapOf("origin.trace_id" to checkout.traceId, "origin.span_id" to checkout.spanId)) { }
```

### Parallel work

An inline lambda doesn't scope `async` by itself. Wrap parallel children in `coroutineScope { }` inside the parent's
block, so the parent can't end before them:

```kotlin
suspend fun load() = tracer.trace("search") { root ->
    coroutineScope {
        val items = async { root.child("items") { fetchItems() } }
        val facets = async { root.child("facets") { fetchFacets() } }
        combine(items.await(), facets.await())
    }
}
```

**Early exits.** When a path won't use some steps' results, such as an empty result or an error, cancel the
remaining steps before returning:

```kotlin
coroutineScope {
    val items = async { root.child("items") { fetchItems() } }
    val ads = async { root.child("ads") { fetchAds() } }
    val result = items.await()
    if (result.isEmpty()) {
        coroutineContext.cancelChildren()   // ads ends as Cancelled, not truncated
        return@coroutineScope Empty
    }
    Results(result, ads.await())
}
```

### Status

A span ends with one of three statuses:

| Status | Meaning |
|---|---|
| `SpanStatus.Ok` | Completed normally. Also what a span reads as if nothing set a status. |
| `SpanStatus.Cancelled` | Stopped before completing, e.g. the user left the screen, or the span was truncated. Not a failure. |
| `SpanStatus.Error(description)` | Failed. `description` is short, low-cardinality text such as `"HTTP 503"` or `"IOException"`. |

`trace {}` and `child {}` set the status from what escapes the block:

| Block outcome | Status |
|---|---|
| returns normally | unchanged |
| throws `TimeoutCancellationException` (e.g. `withTimeout`) | the exception is recorded, then `Error("timeout")` |
| throws any other `CancellationException` | `Cancelled` |
| throws anything else | the exception is recorded, then `Error("<exception class name>")`, never the message |

A thrown exception replaces no status or an explicit `Ok`, but never an explicit `Error` or `Cancelled`.

**Returned errors need `setStatus`.** A result type such as Kotlin's `Result` returns failures rather than throwing
them, so the helpers can't see them. Set the status yourself, or write a small helper for your result type:

```kotlin
inline fun <T> Span.resultChild(name: String, block: (Span) -> Result<T>): Result<T> =
    child(name) { span ->
        block(span).onFailure { span.setStatus(SpanStatus.Error(it.javaClass.simpleName)) }
    }
```

The last `setStatus` call wins while the span is open.

### Attributes and events

**Attributes** describe the span as a whole, with one value per key:

```kotlin
span.setAttribute("result_count", 42)
tracer.trace("search", mapOf("mode" to mode, "store" to storeId)) { }
```

Values can be `String`, `Long`, `Double` or `Boolean`. `Int`, `Short` and `Byte` become `Long`, `Float` becomes
`Double` through its decimal form (`0.1f` is stored as `0.1`), and an `Enum` becomes its name. A `null` value is skipped, so you can add a `Map<String, Any?>` to an
attribute map directly. Any other type is dropped and reported (see [Diagnostics](#diagnostics)). Setting an
existing key replaces its value.

**Events** record that something happened at a point in time, and the same name can occur many times:

```kotlin
span.addEvent("cache_miss")
span.recordException(e)   // an event named "exception"; it doesn't change the status by itself
```

Span names, attribute keys and event names should be low-cardinality: never put IDs or user data in a name.

### Untraced Spans

**Deliberately untraced calls pass an explicit no-op span**, so the choice is visible at the call site that
makes it:

```kotlin
val UntracedSpan: Span = Tracer.NoOp.startTrace("untraced")

getItemsUseCase.getItems(query, UntracedSpan)
```

Spans from `Tracer.NoOp` record nothing and cost nothing: every one is the same shared instance. `Tracer.NoOp` is
also what to inject when tracing is turned off.

### HTTP requests

`Span.startHttpRequest(method, url)` starts a child span for one request attempt. It's named `HTTP <METHOD>` (the URL
is never part of the name), and it records the method, the URL and the response code in `SpanData.http`. A response
of 400 or above reads as `Error("HTTP <code>")`, unless you set a status explicitly or the span was truncated; an
explicit `Ok` for an expected 404 wins.

An HTTP span is always a child: a request with no parent span isn't traced. The module has no networking dependency, so integration is yours.

Its rules: tracing never changes the request's outcome, `proceed` runs exactly once, the span always ends, and
headers already on the request are never overwritten.

`propagationHeaders()` returns the headers that [realtime listeners](#realtime-listeners-for-vendor-adapters) want on
the request. With no listeners it's empty.

---

## Exporting traces

When a root ends, the tracer records one event on its telemeter, described `"Trace: <root name>"`, with facets
`TraceFacet` and `Significance.VERBOSE`. Backends are ordinary relays:

```kotlin
val traceExporter = Relay.buildTypedRelay<TraceFacet> { trace ->
    // trace.root, trace.spans (start order), trace.droppedSpanCount
}
```

- **The tree.** `TraceFacet.spans` holds every recorded span, root and truncated spans included, in start order.
  Each `SpanData` has a `parentSpanId` (null for the root), so a relay can rebuild the tree.
- **Snapshots are immutable.** `SpanData`, `SpanEvent`, `HttpRequestInfo` and `TraceFacet` are safe to keep and to
  read from any thread, and their collections can't be modified.
- **Timestamps.** Each trace reads the wall clock once, when its root starts, and measures everything else on a
  monotonic clock. Durations are unaffected by wall clock changes, and no event can appear before its span started.
  IDs use the W3C formats: 32 lowercase hex characters for trace IDs, 16 for span IDs.
- **`VERBOSE` is a default, not a filter.** It keeps traces out of relays that act at `DEBUG` and above, such as
  `LogRelay` and `FirebaseCrashlyticsRelay`. Facets added by a child telemeter can still trigger those relays. A
  relay that must ignore traces should filter on `TraceFacet`.
- **Delivery is best-effort and in-process.** Each trace is recorded exactly once, then follows your telemeter's
  delivery semantics: it can be lost to buffer overflow, to emission while no relay is subscribed, to scope
  cancellation, or to process death. There's no acknowledgement or flush.

---

## Limits

Library-owned data is bounded per trace. The values aren't configurable; changing one is a release-notes item.

| Limit | Value | When it's exceeded |
|---|---|---|
| spans per trace | 256 | new spans are inert, and `TraceFacet.droppedSpanCount` goes up |
| attributes per span | 64 | the attribute is dropped, and `SpanData.droppedAttributeCount` goes up |
| entries read from an initial attribute map | 256 | the rest aren't read, and each counts as a dropped attribute |
| events per span | 128 | the event is dropped, and `SpanData.droppedEventCount` goes up |
| span / event name | 256 chars | truncated |
| attribute key | 128 chars | the attribute is **dropped** (truncating could merge distinct keys) |
| string attribute value | 1,024 chars | truncated |
| URL (after sanitizing) | 1,024 chars | truncated |
| HTTP method | 16 chars | truncated |
| status description | 512 chars | truncated |
| **text per trace** | **65,536 chars** | see below |

---

## Privacy

**Exported traces can contain personal data.** By default, request URLs keep their query strings, which
can hold sensitive data. Every relay on the telemeter receives them. **A relay that
sends traces off the device must scrub what its backend shouldn't see**, or the app should use
`UrlSanitizer.StripQuery`.

---

## Diagnostics

Problems inside tracing itself are recorded on the tracer's telemeter as events described
`"Tracing diagnostic: <kind>"`, with facets `TracingDiagnostic` and `Significance.WARNING`. They never throw into app
code.

| `TracingDiagnostic.kind` | Reported when |
|---|---|
| `listener_threw` | a [realtime listener](#realtime-listeners-for-vendor-adapters) threw. Also carries `Failure`. Both hold a redacted copy of the exception: its class name and stack trace, without its message or causes. |
| `sanitizer_threw` | a custom `UrlSanitizer` threw; the URL was omitted |
| `attribute_dropped` | an attribute value had an unsupported type |
| `limit_exceeded` | a [limit](#limits) dropped or truncated data |
