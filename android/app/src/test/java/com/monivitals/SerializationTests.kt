//
//  SerializationTests.kt
//  MoniVitals (Android)
//
//  Guards the kotlinx.serialization model (used for local storage AND the cloud backup blobs)
//  against runtime surprises: settings, a saved reading (Session), and a metrics row.
//

package com.monivitals

import com.monivitals.model.AppSettings
import com.monivitals.model.MetricsRecord
import com.monivitals.model.Session
import com.monivitals.model.SessionLabel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationTests {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun appSettingsRoundTrips() {
        val s = AppSettings(
            motionThresh = 8.0,
            theme = AppSettings.Theme.DARK,
            consentAccepted = true,
            disclaimerAcknowledged = true,
        )
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), s))
        assertEquals(s, back)
        assertEquals(8.0, back.motionThresh, 0.0)
    }

    @Test
    fun sessionRoundTrips() {
        val session = Session(
            id = "abc", subjectCode = "Me", label = SessionLabel.POST_EXERCISE,
            startedAt = "2026-07-28T00:00:00Z", endedAt = "2026-07-28T00:05:00Z", notes = "n",
        )
        val back = json.decodeFromString(Session.serializer(), json.encodeToString(Session.serializer(), session))
        assertEquals(session, back)
        assertEquals(SessionLabel.POST_EXERCISE, back.label)
    }

    @Test
    fun metricsRecordRoundTrips() {
        val m = MetricsRecord(
            id = 1, sessionId = "abc", tUs = 1_000_000.0,
            hrBpm = 72.0, respRateBpm = 14.0, hrvMs = 42.0, hrvSdnnMs = 55.0,
            contactQuality = 88, movement = 12,
        )
        val back = json.decodeFromString(MetricsRecord.serializer(), json.encodeToString(MetricsRecord.serializer(), m))
        assertEquals(m, back)
    }
}
