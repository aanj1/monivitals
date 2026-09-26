//
//  PpgHeartRate.kt
//  MoniVitals (Android)
//
//  Heart rate from the camera pulse signal. Detrend + band-pass ~0.7–3.5 Hz, then adaptive
//  peak detection with a refractory period, with an autocorrelation fallback when peaks are
//  sparse. Reports a rolling median over a few seconds so a single noisy beat can't swing it.
//
//  Streaming and testable on synthetic arrays (no camera needed).
//

package com.monivitals.dsp

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Streaming heart-rate estimator fed one PPG (red-mean) sample at a time with its wall-clock
 * timestamp (µs). Detected beat times are exposed for HRV; [bpm] returns the rolling median.
 */
class PpgHeartRate(fs: Double) {
    private val bp = Biquad.bandpass(DSPConstants.hrBpLowHz, DSPConstants.hrBpHighHz, fs)
    private val refractoryUs = DSPConstants.beatRefractoryMs / 1000.0 * DSPConstants.usPerS
    private val hrBuf = RollingMedian(DSPConstants.hrMedianN)

    // Adaptive amplitude threshold: an EWMA of the recent positive envelope.
    private var envelope = 0.0
    private var lastValue = 0.0
    private var prevValue = 0.0
    private var rising = false
    private var lastPeakUs = Double.NEGATIVE_INFINITY
    private var lastBeatUs: Double? = null
    private var lastSampleUs = Double.NEGATIVE_INFINITY
    private var warmed = 0

    /** Beat (systolic-peak) times in µs, newest last, capped. */
    val beats = mutableListOf<Double>()

    /** Recent band-passed samples (for the autocorrelation fallback). */
    private val recent = ArrayDeque<Double>()
    private val recentCap = (fs * 8).roundToInt().coerceAtLeast(64)
    private val fsHz = fs

    /** Feed one sample; returns the instantaneous bpm when a plausible beat closes, else null. */
    fun process(value: Double, tUs: Double): Double? {
        lastSampleUs = tUs
        val y = bp.process(value)
        recent.addLast(y)
        if (recent.size > recentCap) recent.removeFirst()

        // Track a slow envelope of the positive half so the threshold adapts to perfusion.
        val a = 0.02
        if (y > 0) envelope = (1 - a) * envelope + a * y
        warmed += 1

        var bpm: Double? = null
        // Local-maximum detection on the band-passed trace.
        if (y > prevValue) {
            rising = true
        } else if (y < prevValue && rising) {
            rising = false
            val peakVal = prevValue
            val thresh = 0.4 * envelope
            if (warmed > (fsHz * 0.5) && peakVal > thresh && tUs - lastPeakUs >= refractoryUs) {
                val last = lastBeatUs
                lastPeakUs = tUs
                lastBeatUs = tUs
                pushBeat(tUs)
                if (last != null) {
                    val rrUs = tUs - last
                    val b = 60.0 * DSPConstants.usPerS / rrUs
                    if (b in DSPConstants.hrMinBpm..DSPConstants.hrMaxBpm) {
                        hrBuf.push(b)
                        bpm = b
                    }
                }
            }
        }
        prevValue = lastValue
        lastValue = y
        return bpm
    }

    private fun pushBeat(t: Double) {
        beats.add(t)
        if (beats.size > 64) beats.removeAt(0)
    }

    /**
     * Drop the rolling-median HR history without touching the band-pass filter state — the
     * MetricsEngine calls this when a held HR has gone stale, so a resurfacing pulse re-seeds
     * the median from fresh beats (mirrors iOS PulseHeartRateReporter.reset()).
     */
    fun clearBpmBuffer() {
        hrBuf.clear()
    }

    /**
     * Rolling-median heart rate (bpm). Falls back to autocorrelation of the recent window when
     * fewer than three beats have been detected but a periodic signal is present.
     *
     * Returns null once the newest detected beat is older than [STALE_BEAT_US] (~9 s) relative
     * to the latest sample fed in. The rolling median never expires on its own, so without this
     * a vanished pulse (finger still on the lens, quality still OK) would pin the last HR forever
     * — the MetricsEngine staleness counter could never see a null and blank the reading. Mirrors
     * the iOS engine, which recomputes HR over an 8 s tail window that naturally empties.
     */
    fun bpm(): Double? {
        val last = lastBeatUs
        if (last != null && lastSampleUs - last > STALE_BEAT_US) return null
        if (hrBuf.count >= 3) return hrBuf.value()
        return autocorrelationBpm()
    }

    /** Autocorrelation fallback: find the dominant period in the recent band-passed window. */
    private fun autocorrelationBpm(): Double? {
        val n = recent.size
        if (n < (fsHz * 3).roundToInt()) return null
        val x = recent.toDoubleArray()
        val m = mean(x.toList())
        for (i in x.indices) x[i] -= m
        val minLag = (60.0 / DSPConstants.hrMaxBpm * fsHz).roundToInt().coerceAtLeast(1)
        val maxLag = (60.0 / DSPConstants.hrMinBpm * fsHz).roundToInt().coerceAtMost(n - 1)
        if (maxLag <= minLag) return null
        var bestLag = -1
        var bestVal = 0.0
        for (lag in minLag..maxLag) {
            var s = 0.0
            for (i in lag until n) s += x[i] * x[i - lag]
            if (s > bestVal) {
                bestVal = s
                bestLag = lag
            }
        }
        if (bestLag <= 0) return null
        var zeroLag = 0.0
        for (v in x) zeroLag += v * v
        // Require a reasonably strong periodicity.
        if (zeroLag <= 0 || bestVal / zeroLag < 0.3) return null
        val bpm = 60.0 * fsHz / bestLag
        if (bpm !in DSPConstants.hrMinBpm..DSPConstants.hrMaxBpm) return null
        return bpm
    }

    fun reset() {
        bp.reset()
        hrBuf.clear()
        envelope = 0.0
        lastValue = 0.0
        prevValue = 0.0
        rising = false
        lastPeakUs = Double.NEGATIVE_INFINITY
        lastBeatUs = null
        lastSampleUs = Double.NEGATIVE_INFINITY
        warmed = 0
        beats.clear()
        recent.clear()
    }

    companion object {
        /** No beat within this span of the latest sample → the pulse is stale, so bpm() blanks. */
        private const val STALE_BEAT_US = 9.0 * 1_000_000.0
    }
}

/**
 * Pure, offline heart-rate estimate over a fixed array of PPG samples at [fs] Hz — used in
 * tests and as a one-shot estimate on a captured window. Returns null if no plausible pulse.
 */
fun estimateHeartRate(samples: DoubleArray, fs: Double): Double? {
    if (samples.size < (fs * 3).roundToInt()) return null
    val hr = PpgHeartRate(fs)
    var last: Double? = null
    val dt = DSPConstants.usPerS / fs
    var t = 0.0
    for (v in samples) {
        val b = hr.process(v, t)
        if (b != null) last = b
        t += dt
    }
    return hr.bpm() ?: last
}
