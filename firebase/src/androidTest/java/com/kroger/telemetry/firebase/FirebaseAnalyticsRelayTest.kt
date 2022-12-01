package com.kroger.telemetry.firebase

import androidx.core.os.bundleOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kroger.telemetry.facet.DeveloperMetricsFacet
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class FirebaseAnalyticsRelayTest {

    @Test
    fun given_event_with_firebase_facet_with_data_WHEN_recorded_THEN_data_matches_in_logged_event() =
        runBlocking {
            val fakeName = "event_name"
            val key1 = "key1"
            val key2 = "key2"
            val val1 = "val1"
            val val2 = 2

            val facetWithData = object : DeveloperMetricsFacet {
                override val eventName: String = fakeName
                override val compute: () -> Map<String, Any?> =
                    { mapOf<String, Any?>(key1 to val1, key2 to val2) }
            }

            val bundleToCompare = bundleOf(key1 to val1, key2 to val2)

            val sut = facetWithData.compute.invoke().toBundle()

            assert(sut[key1] == bundleToCompare[key1])
            assert(sut[key2] == bundleToCompare[key2])
        }
}
