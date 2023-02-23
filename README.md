# Telemetry

Telemetry is an event-based unified logging API, meant to streamline analytics collection and
logging for engineering purposes

- [The Problem](#the-problem)
- [Quick Start](#quick-start)
    - [Adding Dependencies](#adding-dependencies)
    - [Setting Up Telemetry](#setting-up-telemetry)
- [Key Components](#key-components)
    - [Overview](#overview)
    - [Events](#events)
    - [Facets](#facets)
    - [Relays](#relays)
    - [Facet Resolvers](#facetresolvers)
    - [Telemeters](#telemeters)
- [Additional Relays](#additional-relays)
    - [Android Relays](#android-relays)
    - [Firebase Analytics Relay](#firebaseanalyticsrelay)
    - [Firebase Crashlytics Relay](#firebasecrashlyticsrelay)

## The Problem

As applications evolve, there is frequently a need to record data that corresponds to user-initiated
events like button clicks as well as events generated through code, such as network call successes
or failures.

Logging events like these is often on an as-needed basis, where similar code is added over time to capture the same event to multiple places, such as Firebase or Logcat. For example, you might see something like this:

```kotlin
exampleButton.setOnClickListener { view ->
    val tag = view.tag.toString()
    Log.d("searchable debug name", "$tag clicked")

    bannerAnalytics.send {
        ButtonClicked(tag)
    }

    firebaseAnalytics.logEvent("button clicked: $tag")
}
```

This can end up being quite a lot of code that's ultimately not really related to the functionality around it.

Telemetry solves this problem by unifying the pipeline for reporting all such events, encouraging better design patterns and code organization.

---

## Quick Start

### Adding dependencies

To add Telemetry to your project, add the follow to your dependencies:

`implementation("com.kroger.telemetry:telemetry:<version>")`

There are additional relays that are published in conjunction with Telemetry. See [here](#additional-relays) for a list that includes their artifact names.

### Setting up Telemetry

Setting up a Telemetry pipeline is easy:

- Define events

```kotlin
sealed class ActivityEvent : Event {
    object Created : ActivityEvent() {
        override val description = "Activity Created"
        override val facets: List<Facet> = listOf(
            Significance.VERBOSE,
            /* more facets for things like
            firebase, analytics, or other services */
        )
    }

    data class ButtonClicked(buttonTag: String) : ActivityEvent() {
        override val description: String = "$buttonTag clicked"
        override val facets: List<Facet> = listOf(
            Significance.VERBOSE,
            /* additional facets */
        )
    }

    data class NetworkError(val throwable: Throwable) : ActivityEvent() {
        listOf(
            Significance.ERROR,
            /* additional facets */
        )
    }
}
```

- Create a `Telemeter`, add `Relay`s and `FacetResolver`s to it, and record `Event`s.

```kotlin
val contextAwareFacetResolver = ContextAwareFacetResolver(context)

val telemeter = Telemeter.build(
  initialRelays = listOf(LogRelay()),
  facetResolvers = mapOf(contextAwareFacetResolver.getType() to contextAwareFacetResolver)
)

telemeter.record(ActivityEvent.Created)
telemeter.record(ActivityEvent.ButtonClicked("tag"))
telemeter.record(NetworkError(IOException()))
```

---

## Key Components

### **Overview**

There are five essential pieces to understand in order to use
Telemetry. Here they are listed for an overview, and then revisited in detail in the following section.

1. **Telemeter**: This is the entry point into the event pipeline. Telemeters handle forwarding events to Relays, and they can be set up as a parent-child tree to allow for scope functionality, whether that scope be an entire module or a block of code.
2. **Relay**: Relays receive and process events. There should be a Relay attached to your Telemeter for each way that data should be processed in your system. For example, these could include a `FirebaseRelay` or a `LogRelay`. Multiple Relays can be attached to a Telemeter, and they will each receive Events recorded by the Telemeter.
3. **Event**: An Event is a basic interface that has a human-readable description and composes different pieces of data through a list of Facets.
4. **Facet**: A Facet is any type of data that should be processed by the pipeline. Defining
   different types of Facets allows Relays to easily process only the data that they care about.
5. **FacetResolver**: Sometimes data requires additional manipulation between the `.record` call and
   the `Relay`. `FacetResolver` acts as this middleware.

---

### **Events**

An `Event` has the following definition:

```kotlin
interface Event {
    val description: String
    val facets: List<Facet>
}
```

`Event`s are user-defined, and are composed of a human-readable description and additional metadata in the form of Facets. Since `Event` is an open interface, users have freedom in determining how they want to define and structure their events. For example, a sealed class could be a good fit:

```kotlin
sealed class MyModuleEvent : Event {
    override val description = "an event from MyModule"
    object LandingPageCreated : MyModuleEvent() {
        override val facets: List<Facet> = listOf(
            Significance.VERBOSE,
            /* analytics, firebase, console, etc. facets */
        )
    }
    data class MyButtonClicked(val tag: String) : MyModuleEvent() {
        override val description = "${super.description} button was clicked with $tag"
        override val facets: List<Facet> = listOf(
            ExampleFacet(data = tag),
            /* additional facets */
        )
    }
}
```

---

### **Facets**

A `Facet` has the following definition:

```kotlin
interface Facet
```

Facets are user-defined, except for a few defaults provided in the library. As pictured above, a `Facet` is simply an empty interface. This intends to accomplish two goals:

1. Data entered into the Telemetry pipeline will conform to a type, which encourages users to create strict definitons of data
2. The definition of that data does not need to conform to a specific shape

Since Facets are stored in a list on an `Event`, it becomes easy for a `Relay` to extract the data they are interested in during processing.

```kotlin
data class MyFacet(val data: MyDto) : Facet

class MyRelay : Relay {
        override suspend fun process(events: Flow<Event>) {
            events.collect { event ->
                event.facets.filterIsInstance<MyFacet>().forEach {
                    // process specific data
            }
        }
    }
}
```

There are two additional `Facet` types that are intended for usage with data that is complex or computationally expensive to generate. They are defined as such:

```kotlin
interface Facet {
    interface Computed<T> : Facet {
        val compute: () -> T
    }
    abstract class Lazy<T> : Computed<T> {
        val value by lazy {
            compute()
        }
    }
}
```

`Facet.Computed` better fits a use-case for a Facet that will only be `compute`d once, or when a type might want to inherit from a different class. If a Facet might be computed by multiple Relays, `Facet.Lazy` is more appropriate. However, this restricts inheriting from a different class.

---

### **Relays**

A `Relay` has the following definition:

```kotlin
interface Relay {
    suspend fun process(event: Event)
}
```

Relays are user-defined, except for some common ones package that are packaged in the library. There should be separate relays defined for each method of handling data required for the pipeline. For example, there should be individual Relays for each of things like Logcat, Dynatrace, Firebase, or Clickstream.

Relays will have events sent to them in the same coroutine scope that is injected into their telemeter. By default this scope will operate on `Dispatchers.Default`, but it is worth ensuring the `process` function switches context to a background thread if it is long-running or does any IO.

Exceptions thrown in Relays will be caught by the Telemeter in which they are processing. The Telemeter will then record an Event describing the failure, using a `Failure` Facet.

In addition to a regular Relay, Telemetry is also packaged with a `TypedRelay`. If a Relay only cares about processing one type of `Facet`, the definition of it can be simplified by using a `TypedRelay`. For example, a `FirebaseRelay` might only care about `FirebaseFacet`s, whereas a `LogRelay` might care about all events.

A `TypedRelay` has the following definition:

```kotlin
interface TypedRelay<T : Facet> : Relay {
    val type: Class<T>
    override suspend fun process(event: Event) {
        event.facets.filterIsInstance(type).forEach { facet ->
            processFacet(facet)
        }
    }

    suspend fun processFacet(facet: T)
}
```

The boilerplate of overriding the type can be avoided by using `Relay.buildTypedRelay`.

```kotlin
class MyTypedRelay : TypedRelay<MyFacet> by Relay.buildTypedRelay({
    //this lambda is the same as processFacet above
})
```

---

### **FacetResolvers**

An `UnresolvedFacet` extends `Facet` to indicate that it should be `resolved` by a `FacetResolver`

```kotlin
interface UnresolvedFacet : Facet
```

```kotlin
interface FacetResolver {
    fun resolve(unresolvedFacet: UnresolvedFacet): List<Facet>
}

```

Here's a real world example, using `ContextAwareFacet` in the case you may want reference a string resource, but without needing to rely on context at that location

```kotlin
class StringResourceFormattedToastFacet(
    @StringRes private val resId: Int,
    private vararg val formatArgs: String,
) : ContextAwareFacet {
    override fun resolve(context: Context): Facet {
        return ToastFacet(context.getString(resId, *formatArgs))
    }
}
```

### **Telemeters**

A `Telemeter` has the following definition:

```kotlin
interface Telemeter {
    fun record(event: Event, withFacets: List<Facet>? = null)
}
```

A Telemeter is the glue that brings together the rest of the telemetry system. Relays and Facet
Resolvers will be attached to it, and then Events that are recorded by the Telemeter will be
processed by any applicable facet resolvers, and then passed to the Relays. Additionally, Telemeters
can be constructed with Facets. These Facets will be attached to each event recorded by a Telemeter.

Under the hood, events are propagated to Relays through the use of
a [SharedFlow](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/)
, configured so that the system is never blocked by slow Relays. This `SharedFlow` configuration can
be changed when constructing a Telemeter, including which `CoroutineScope` the pipeline will run.
This flow can be configured during Telemeter construction by providing a `flowConfig` argument.

The standard Telemeter can be constructed through a factory function defined on the type, for example:

```kotlin
Telemeter.build(
    relays = listOf(LogRelay()),
    facets = listOf(Prefix.App("<your app name>")),
    facetResolvers = mapOf(contextAwareFacetResolver.getType() to contextAwareFacetResolver)
)
```

#### **Child Telemeters and Telemeter scoping**

Telemeters can be chained in parent-child relationships, effectively creating a tree-like structure. The primary usage for this feature is to create scoped Telemeters. For example, Telemeters could be created for modules, viewmodels, network interactors, or any other number of specific scopes.

Attaching `Prefix` facets to a Telemeter is a good way to accomplish this. Here is an example using Dagger:

```kotlin
@Module
object MyModule {
    @Provides
    fun provideMyModuleTelemeter(telemeter: Telemeter): Telemeter =
        telemeter.child(
            listOf(Prefix.Module("My Module"))
        )
}
```

Child telemeters will send Events to their parent. In addition, Relays can be added to children if there is cause for scope-specific data processing. We could add a Logcat logging to the above example using the following:

```kotlin
@Module
object MyModule {
    @Provides
    fun provideMyModuleTelemeter(@AppTelemeter telemeter: Telemeter): Telemeter =
      telemeter.child(
        relays = listOf(LogRelay()),
        facets = listOf(Prefix.Module("My Module"))
      )
}
```

#### **Capturing call-site thread information**

A Telemeter can be configured to capture thread data from the invocation site of a `record` call. This can be a useful debug tool. This can be done by enabling the `shouldPropagateThreadData` property of the `EventFlowConfig` supplied to the Telemeter during construction. This will add a `ThreadData` facet to any `Event`s that are recorded by the Telemeter.

#### Defining new types of Telemeters using delegates

New types of Telemeters can be defined by taking advantage of Kotlin's delegate syntax. This allows for the usage of the standard definition of Telemeters. Here's an example of how this could be used to make scoping Telemeters easier.

```kotlin
// creates a top-level telemeter.
class AppTelemeter : Telemeter by Telemeter.build()

// specifically requires the app telemeter
class ModuleTelemeter(appTelemeter: AppTelemeter) : Telemeter by appTelemeter.child()

// can be created as a child of any telemeter
class NetworkTelemeter(telemeter: Telemeter) by telemeter.child()
```

---

### Additional Relays

The following is a list of additional Relays and their artifact names:

#### **Android Relays**

`"com.kroger.telemetry:android:<version>"`

This artifact contains additional Relays:

- LogRelay for logging Events to Logcat
- ToastRelay for showing Events as Toasts

#### **FirebaseAnalyticsRelay**

`"com.kroger.telemetry:firebase:<version>"`

This Relay will log `DeveloperMetricsFacet`s to Firebase Analytics. See
the [Firebase documentation](https://firebase.google.com/docs/analytics/get-started?platform=android)
for instructions on how to setup Firebase for your project.

#### **FirebaseCrashlyticsRelay**
A Relay that sends data to Firebase Crashlytics to be included in crash reports and non-fatal error reports.

`"com.kroger.telemetry:firebase:<version>"`

This Relay will log any of the following `Facet`s to Firebase Crashlytics
 - CrashlyticsKey
 - Significance (if > DEBUG)
 - Failure

See the [Crashlytics documentation](https://firebase.google.com/docs/crashlytics/get-started?platform=android)for instructions on how to setup Firebase for your project.


---
