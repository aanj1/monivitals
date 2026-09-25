//
//  DSPConstants.kt
//  MoniVitals (Android)
//
//  DSP constants for the camera-pulse pipeline. Fixed values so the algorithms behave the
//  same across runs; the motion-sensitivity value is user-adjustable in Settings.
//

package com.monivitals.dsp

object DSPConstants {
    // Pulse (heart-rate) band-pass, in Hz. ~42–210 bpm.
    const val hrBpLowHz = 0.7
    const val hrBpHighHz = 3.5

    // Respiratory band-pass, in Hz. ~6–30 breaths/min.
    const val respBpLowHz = 0.1
    const val respBpHighHz = 0.5

    // Beat-to-beat refractory (a pulse cannot repeat faster than this). DELIBERATE DIVERGENCE
    // from iOS (DSPConstants.qrsRefractoryMs = 300 ms): Android derives the gap from the max
    // reportable HR (hrMaxBpm = 210 bpm → 60_000 / 210 ≈ 285.7 ms) so the streaming peak picker
    // can follow rates right up to 210 bpm, and it lines up with the 285 ms lower edge of the
    // HRV RR window. iOS's foot detector is tuned around a slightly wider 300 ms gap; the two
    // detectors are different implementations, so the values are intentionally not identical.
    const val beatRefractoryMs = 285.0

    // Heart-rate reporting.
    const val hrMedianN = 5
    const val hrMinBpm = 40.0
    const val hrMaxBpm = 210.0

    // Respiratory-rate reporting.
    const val respMinBpm = 6.0
    const val respMaxBpm = 30.0

    // HRV: reject inter-beat intervals outside this window as ectopic/artifact.
    // Plausible RR window: 40–210 bpm ≈ 285–1500 ms (matches iOS).
    const val rrMinMs = 285.0
    const val rrMaxMs = 1500.0

    // HRV plausibility cap (ms). Healthy adult RMSSD/SDNN on a short fingertip read sits
    // well under 200 ms; anything beyond means beats were mis-detected, so no value is
    // reported (matches iOS HeartRateVariability.maxPlausibleMs).
    const val hrvMaxPlausibleMs = 200.0

    // HRV gates (matches iOS): minimum clean intervals and clean successive pairs before
    // any RMSSD/SDNN is reported.
    const val hrvMinCleanIntervals = 8
    const val hrvMinCleanPairs = 6

    // Minimum contact/signal quality (0..100) before fresh HR / breathing / HRV estimates
    // are pushed. Below this the last good values are held instead of computing on noise
    // (matches iOS DSPConstants.minQualityForVitals).
    const val minQualityForVitals = 40

    // Contact / quality thresholds on the raw red-channel mean (0..255 per-frame level).
    // A fingertip over the torch drives the red channel bright and near-saturated.
    const val contactRedLow = 90.0
    const val contactRedHigh = 245.0

    // Motion: linear-acceleration magnitude (m/s²) mapped to 0..255. User-adjustable.
    const val motionThresh = 6.0
    const val motionWindowMs = 500.0

    /** Microseconds per second — used everywhere we convert time. */
    const val usPerS = 1_000_000.0
}
