//
//  Signals.kt
//  MoniVitals (Android)
//
//  Typed representations of the live camera + motion samples the capture layer publishes,
//  plus the small set of stream kinds the app now tracks. This replaces the old multi-stream
//  BLE packet types: the app measures a single pulse signal from the rear camera (fingertip
//  photoplethysmography) alongside phone motion.
//
//  Mirrors the iOS PpgPacket / motion plumbing.
//

package com.monivitals.source

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The signals the app tracks. Just the camera pulse now (+ motion where useful). */
@Serializable
enum class StreamKind {
    @SerialName("ppg") PPG,
    @SerialName("motion") MOTION;

    companion object {
        val allCases: List<StreamKind> = listOf(PPG, MOTION)
    }
}

/**
 * One block of pulse samples from the rear camera. Each sample is the average brightness of
 * the red (and green) channel over a centered region of the frame — a photoplethysmography
 * (blood-flow) signal. Values are inverted upstream so a heartbeat reads as an upward
 * deflection. `tUs` is a wall-clock microsecond timestamp of the first sample.
 */
data class PpgPacket(
    val seq: Int,
    var tUs: Double,
    /** Measured frame rate (~30). */
    val sampleRateHz: Int,
    /** Green-channel mean per frame. */
    val green: DoubleArray,
    /** Red-channel mean per frame (the primary pulse channel). */
    val red: DoubleArray,
    /**
     * Real per-sample timestamps (µs), one per green/red sample. The camera bridge fills this with
     * each frame's true capture time so fps drift / drops don't scale the exported rate; empty on
     * the BLE path (there `tUs` + `sampleRateHz` reconstruct times). Mirrors iOS PpgPacket.timesUs.
     */
    val timesUs: List<Double> = emptyList(),
)

/** One phone-motion sample: linear-acceleration magnitude, wall-clock timestamped. */
data class MotionSample(
    val tUs: Double,
    /** Acceleration magnitude minus gravity (m/s²). */
    val magnitude: Double,
)
