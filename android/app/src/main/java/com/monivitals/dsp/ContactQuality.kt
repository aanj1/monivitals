//
//  ContactQuality.kt
//  MoniVitals (Android)
//
//  Fingertip-contact / signal-quality from the raw camera red-channel level, plus a movement
//  metric from the phone's accelerometer. A finger held over the rear camera with the torch on
//  drives the red channel bright and near-saturated with a low frame-to-frame wobble; an empty
//  camera reads dark or jumpy. Quality is 0..100; movement is 0..255.
//

package com.monivitals.dsp

import kotlin.math.roundToInt

/**
 * Live contact-quality (from the raw red mean, 0..255 per frame) and movement (from
 * accelerometer magnitude). Both are cheap rolling estimates read on the metrics tick.
 */
class ContactQualityEstimator(
    fsPpg: Double,
    motionThresh: Double = DSPConstants.motionThresh,
) {
    private val red = ArrayDeque<Double>()
    private val redCap: Int = maxOf(8, (fsPpg * 1.5).roundToInt())
    private val accel = ArrayDeque<Double>()
    private val accelCap = 32
    private var motionThresh = motionThresh

    fun setMotionThresh(thresh: Double) {
        motionThresh = thresh
    }

    /** Feed one raw red-channel mean (0..255) for this frame. */
    fun pushRed(value: Double) {
        red.addLast(value)
        if (red.size > redCap) red.removeFirst()
    }

    /** Feed one accelerometer magnitude (m/s², gravity removed). */
    fun pushMotion(magnitude: Double) {
        accel.addLast(magnitude)
        if (accel.size > accelCap) accel.removeFirst()
    }

    /** 0..100 contact quality: brightness/saturation level (60%) + frame-to-frame stability (40%). */
    fun contactQuality(): Int {
        if (red.size < 4) return 0
        val m = mean(red.toList())
        // Bright, near-saturated red = finger present. Below the low bound → likely no finger.
        val level = clamp(
            mapRange(m, DSPConstants.contactRedLow, DSPConstants.contactRedHigh, 0.0, 100.0),
            0.0,
            100.0,
        )
        // Stability: low relative variance is good; a jumpy trace (finger sliding) is bad.
        val sd = stddev(red.toList())
        val rel = if (m > 0) sd / m else 1.0
        val stability = 100.0 * (1.0 - clamp(rel * 8.0, 0.0, 1.0))
        return (0.6 * level + 0.4 * stability).roundToInt()
    }

    /** True when a fingertip is confidently over the camera (quality high enough to measure). */
    fun fingerPresent(): Boolean = contactQuality() >= 45

    /** 0 (still) .. 255 movement from recent accelerometer magnitude. */
    fun movement(): Int {
        if (accel.isEmpty()) return 0
        val peak = accel.maxOrNull() ?: 0.0
        return clamp((255.0 * peak / motionThresh), 0.0, 255.0).roundToInt()
    }

    fun reset() {
        red.clear()
        accel.clear()
    }
}

/**
 * Pure classifier used in tests: is a fingertip present for this array of raw red means?
 * A good pulse recording sits bright + stable; a flat/dark recording does not.
 */
fun classifyContact(redMeans: DoubleArray): Boolean {
    if (redMeans.size < 4) return false
    val est = ContactQualityEstimator(30.0)
    for (v in redMeans) est.pushRed(v)
    return est.fingerPresent()
}
