//
//  RespiratoryRate.kt
//  MoniVitals (Android)
//
//  Respiratory rate from the slow modulation of the camera pulse. Breathing gently changes
//  blood flow, which shows up as (a) a low-frequency baseline wander and (b) a rise and fall in
//  the size of each pulse beat. We derive a "respiration" signal from both, resample it to a
//  low rate, and count its cycles.
//
//  Streaming and testable on synthetic arrays (no camera needed).
//

package com.monivitals.dsp

import kotlin.math.roundToInt

class RespiratoryRate(private val fs: Double) {
    // Beat detector (pulse band) to build the per-beat amplitude envelope.
    private val pulseBp = Biquad.bandpass(DSPConstants.hrBpLowHz, DSPConstants.hrBpHighHz, fs)
    // Baseline-wander band-pass on the raw signal (breathing also moves the DC baseline).
    private val baselineBp = Biquad.bandpass(DSPConstants.respBpLowHz, DSPConstants.respBpHighHz, fs)

    private var prev = 0.0
    private var rising = false
    private var lastPeakUs = Double.NEGATIVE_INFINITY
    private var envelopeEwma = 0.0
    private val refractoryUs = DSPConstants.beatRefractoryMs / 1000.0 * DSPConstants.usPerS
    private var tUs = 0.0
    private val dtUs = DSPConstants.usPerS / fs

    // Respiration signal: the beat-amplitude envelope sampled once per beat, plus a running
    // baseline-band trace. Both cover ~40 s so a slow (6/min) breath is resolvable.
    private val ampEnvelope = ArrayDeque<Pair<Double, Double>>() // (tUs, amplitude)
    private val baselineTrace = ArrayDeque<Double>()
    private val baselineCap = (fs * 40).roundToInt().coerceAtLeast(64)
    private val envelopeCap = 120
    private val rrBuf = RollingMedian(5)

    /** Feed one raw PPG sample. */
    fun process(value: Double) {
        val pulse = pulseBp.process(value)
        val base = baselineBp.process(value)
        baselineTrace.addLast(base)
        if (baselineTrace.size > baselineCap) baselineTrace.removeFirst()

        // Track a slow envelope of the positive pulse half so the peak threshold adapts.
        if (pulse > 0) envelopeEwma = 0.98 * envelopeEwma + 0.02 * pulse

        // Local-maximum beat detection.
        if (pulse > prev) {
            rising = true
        } else if (pulse < prev && rising) {
            rising = false
            val peakVal = prev
            if (peakVal > 0.4 * envelopeEwma && tUs - lastPeakUs >= refractoryUs) {
                lastPeakUs = tUs
                ampEnvelope.addLast(tUs to peakVal)
                if (ampEnvelope.size > envelopeCap) ampEnvelope.removeFirst()
            }
        }
        prev = pulse
        tUs += dtUs
    }

    /** Breaths per minute, or null until enough signal is present. */
    fun bpm(): Double? {
        val amp = respFromEnvelope()
        val baseline = respFromBaseline()
        // Prefer the amplitude-envelope estimate (robust to lighting drift); fall back to
        // baseline wander.
        val est = amp ?: baseline ?: return null
        if (est !in DSPConstants.respMinBpm..DSPConstants.respMaxBpm) return null
        rrBuf.push(est)
        return rrBuf.value()
    }

    /** Cycles of the beat-amplitude envelope → breaths/min. */
    private fun respFromEnvelope(): Double? {
        if (ampEnvelope.size < 8) return null
        val ts = ampEnvelope.map { it.first }
        val amps = ampEnvelope.map { it.second }
        // Resample the (unevenly spaced) envelope onto a uniform low-rate grid, then count
        // its zero-crossings around the mean.
        val startT = ts.first()
        val endT = ts.last()
        val spanS = (endT - startT) / DSPConstants.usPerS
        if (spanS < 12.0) return null // need ≥ ~2 breaths at 10/min
        val gridHz = 4.0
        val nGrid = (spanS * gridHz).toInt().coerceAtLeast(8)
        val grid = DoubleArray(nGrid)
        for (i in 0 until nGrid) {
            val t = startT + i / gridHz * DSPConstants.usPerS
            grid[i] = interp(ts, amps, t)
        }
        return crossingRate(grid, gridHz)
    }

    /** Cycles of the baseline-band trace → breaths/min. */
    private fun respFromBaseline(): Double? {
        if (baselineTrace.size < (fs * 12).roundToInt()) return null
        return crossingRate(baselineTrace.toDoubleArray(), fs)
    }

    fun reset() {
        pulseBp.reset()
        baselineBp.reset()
        prev = 0.0
        rising = false
        lastPeakUs = Double.NEGATIVE_INFINITY
        envelopeEwma = 0.0
        tUs = 0.0
        ampEnvelope.clear()
        baselineTrace.clear()
        rrBuf.clear()
    }
}

/** Linear interpolation of (t, v) at time [t]; clamps to the endpoints. */
private fun interp(ts: List<Double>, vs: List<Double>, t: Double): Double {
    if (t <= ts.first()) return vs.first()
    if (t >= ts.last()) return vs.last()
    var lo = 0
    var hi = ts.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) / 2
        if (ts[mid] <= t) lo = mid else hi = mid
    }
    val span = ts[hi] - ts[lo]
    if (span <= 0) return vs[lo]
    val frac = (t - ts[lo]) / span
    return vs[lo] + frac * (vs[hi] - vs[lo])
}

/**
 * Count positive-going zero crossings of a mean-removed trace and convert to breaths/min.
 * Returns null when the trace is essentially flat (no breathing signal).
 */
fun crossingRate(samples: DoubleArray, fs: Double): Double? {
    val n = samples.size
    if (n < 4) return null
    val m = mean(samples.toList())
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    for (v in samples) { if (v < lo) lo = v; if (v > hi) hi = v }
    if (hi - lo <= 1e-9) return null

    var crossings = 0
    var firstCrossIdx = -1
    var lastCrossIdx = -1
    for (i in 1 until n) {
        val prev = samples[i - 1] - m
        val cur = samples[i] - m
        if (prev <= 0.0 && cur > 0.0) {
            crossings += 1
            if (firstCrossIdx < 0) firstCrossIdx = i
            lastCrossIdx = i
        }
    }
    if (crossings < 2 || firstCrossIdx < 0 || lastCrossIdx <= firstCrossIdx) return null
    val cycles = (crossings - 1).toDouble()
    val elapsedS = (lastCrossIdx - firstCrossIdx) / fs
    if (elapsedS <= 0) return null
    return cycles / elapsedS * 60.0
}

/** Pure, offline respiratory-rate estimate over a fixed array of PPG samples at [fs] Hz. */
fun estimateRespiratoryRate(samples: DoubleArray, fs: Double): Double? {
    val rr = RespiratoryRate(fs)
    for (v in samples) rr.process(v)
    return rr.bpm()
}
