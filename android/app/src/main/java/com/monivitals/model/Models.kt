//
//  Models.kt
//  MoniVitals (Android)
//
//  Local-first data model. Subjects are identified only by a non-PII code. All persisted
//  types are @Serializable so they round-trip to disk and to the optional cloud backup.
//

package com.monivitals.model

import com.monivitals.dsp.DSPConstants
import com.monivitals.source.StreamKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class SessionLabel {
    @SerialName("rest") REST,
    @SerialName("seated") SEATED,
    @SerialName("walking") WALKING,
    @SerialName("post-exercise") POST_EXERCISE,
    @SerialName("other") OTHER;

    val rawValue: String
        get() = when (this) {
            REST -> "rest"
            SEATED -> "seated"
            WALKING -> "walking"
            POST_EXERCISE -> "post-exercise"
            OTHER -> "other"
        }

    companion object {
        val allCases = listOf(REST, SEATED, WALKING, POST_EXERCISE, OTHER)
    }
}

@Serializable
enum class AnnotationType {
    @SerialName("motion_start") MOTION_START,
    @SerialName("motion_stop") MOTION_STOP,
    @SerialName("artifact") ARTIFACT,
    @SerialName("marker") MARKER;

    val rawValue: String
        get() = when (this) {
            MOTION_START -> "motion_start"
            MOTION_STOP -> "motion_stop"
            ARTIFACT -> "artifact"
            MARKER -> "marker"
        }
}

/** A person the reading is for, identified only by a non-identifying code. */
@Serializable
data class Subject(
    val code: String, // primary key, non-identifying
    val ageBand: String,
    val sex: String? = null,
    val notes: String = "",
    val createdAt: String, // ISO-8601
) {
    val id: String get() = code
}

/** A saved fingertip reading. Metadata only; the pulse trace stays on this phone. */
@Serializable
data class Session(
    val id: String,
    val subjectCode: String,
    val label: SessionLabel,
    val startedAt: String,
    val endedAt: String? = null,
    val notes: String = "",
)

/**
 * One stored block of pulse samples; timestamps are explicit (wall-clock µs). Per-sample
 * arrays are stored as lists so the class is serializable.
 */
@Serializable
data class StreamChunk(
    val id: Int? = null,
    val sessionId: String,
    val stream: StreamKind,
    val tStartUs: Double,
    val sampleRateHz: Int,
    /** Per-sample timestamps (µs). */
    val t: List<Double>,
    /** Camera pulse channels. */
    val green: List<Double>? = null,
    val red: List<Double>? = null,
)

/** A persisted metrics row. */
@Serializable
data class MetricsRecord(
    val id: Int? = null,
    val sessionId: String,
    val tUs: Double,
    val hrBpm: Double? = null,
    val respRateBpm: Double? = null,
    val hrvMs: Double? = null,
    val hrvSdnnMs: Double? = null,
    val contactQuality: Int,
    val movement: Int,
)

/** A timestamped marker on a reading. */
@Serializable
data class Annotation(
    val id: Int? = null,
    val sessionId: String,
    val tUs: Double,
    val type: AnnotationType,
    val text: String? = null,
)

/** App settings (persisted as JSON). */
@Serializable
data class AppSettings(
    /** Movement sensitivity: acceleration (m/s²) mapped to full-scale movement. */
    val motionThresh: Double = DSPConstants.motionThresh,
    val theme: Theme = Theme.LIGHT,
    val consentAccepted: Boolean = false,
    val disclaimerAcknowledged: Boolean = false,
) {
    @Serializable
    enum class Theme {
        @SerialName("dark") DARK,
        @SerialName("light") LIGHT,
        @SerialName("system") SYSTEM,
    }
}
