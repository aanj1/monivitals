//
//  HeartRateVariability.kt
//  MoniVitals (Android)
//
//  Heart-rate variability from inter-beat (RR) intervals: RMSSD (root mean square of
//  successive differences) and SDNN (standard deviation of intervals). Ported to match
//  the iOS DSP (ios/MoniVitals/DSP/HeartRateVariability.swift) exactly:
//    - plausible RR window 285–1500 ms (40–210 bpm)
//    - median-ratio band 0.8–1.25 for the cleaned interval set (SDNN)
//    - successive pairs differenced only when both members are plausible AND truly
//      adjacent in the original series, with the standard 20% ectopic/artifact rejection
//    - at least 8 clean intervals and 6 clean successive pairs before reporting
//    - 200 ms plausibility cap on both RMSSD and SDNN (beyond that beats were
//      mis-detected, so null is reported instead)
//
//  Pure + testable on known RR-interval arrays (no camera needed).
//

package com.monivitals.dsp

import kotlin.math.abs
import kotlin.math.sqrt

/** RMSSD + SDNN (both in ms) for a set of RR intervals, or null when too few clean beats. */
data class HrvResult(val rmssdMs: Double, val sdnnMs: Double)

/**
 * Compute HRV from a list of RR intervals (ms). Returns null until at least
 * [DSPConstants.hrvMinCleanIntervals] clean intervals and [DSPConstants.hrvMinCleanPairs]
 * clean successive pairs exist, or when the result is outside the plausible range.
 *
 * Pass the RAW interval series (implausible values included) — adjacency is judged on the
 * input as given, so pre-filtering would manufacture differences between beats that were
 * never adjacent.
 */
fun computeHrv(rrIntervalsMs: List<Double>): HrvResult? {
    val rr = cleanedIntervals(rrIntervalsMs)
    if (rr.size < DSPConstants.hrvMinCleanIntervals) return null
    val diffs = successiveDiffs(rrIntervalsMs)
    if (diffs.size < DSPConstants.hrvMinCleanPairs) return null

    var sq = 0.0
    for (d in diffs) sq += d * d
    val rmssd = sqrt(sq / diffs.size)
    val sdnn = stddev(rr)

    // Physiological ceiling for a short-window camera HRV read: anything beyond means the
    // beats were mis-detected, so report nothing rather than a junk number.
    if (rmssd > DSPConstants.hrvMaxPlausibleMs || sdnn > DSPConstants.hrvMaxPlausibleMs) return null
    return HrvResult(rmssdMs = rmssd, sdnnMs = sdnn)
}

/**
 * Successive differences over originally-adjacent plausible pairs only. A missed beat
 * doubles an interval and a false beat halves one; either inflates RMSSD enormously, so a
 * pair is differenced only when BOTH members are plausible (finite, within
 * [DSPConstants.rrMinMs]..[DSPConstants.rrMaxMs]) and truly adjacent in the original
 * series, and pairs differing by more than 20% are dropped (the standard ectopic/artifact
 * criterion). Mirrors the iOS nan-placeholder adjacency semantics.
 */
internal fun successiveDiffs(rrMs: List<Double>): List<Double> {
    if (rrMs.size < 2) return emptyList()
    val out = ArrayList<Double>(rrMs.size - 1)
    for (i in 1 until rrMs.size) {
        val a = rrMs[i - 1]
        val b = rrMs[i]
        if (!a.isFinite() || !b.isFinite()) continue
        if (a < DSPConstants.rrMinMs || a > DSPConstants.rrMaxMs) continue
        if (b < DSPConstants.rrMinMs || b > DSPConstants.rrMaxMs) continue
        val d = b - a
        if (abs(d) <= 0.2 * ((a + b) / 2)) out.add(d)
    }
    return out
}

/**
 * Plausible RR intervals with ectopic / missed-beat outliers removed. A single false beat
 * can dominate the spread, so keep only intervals close to the median (ratio 0.8–1.25) —
 * unless the window is still too small to judge.
 */
internal fun cleanedIntervals(rrMs: List<Double>): List<Double> {
    val plausible = rrMs.filter { it.isFinite() && it >= DSPConstants.rrMinMs && it <= DSPConstants.rrMaxMs }
    if (plausible.size < 5) return plausible
    val med = median(plausible)
    if (!(med > 0)) return plausible
    return plausible.filter { interval ->
        val ratio = interval / med
        ratio >= 0.8 && ratio <= 1.25
    }
}

/**
 * Convert a series of beat timestamps (µs) into RR intervals (ms). Every consecutive gap
 * is kept — including implausible ones — so downstream plausibility checks see true
 * adjacency (a pair is only differenced when both members are plausible AND the beats
 * were truly adjacent).
 */
internal fun rrIntervalsMsFromBeats(beatsUs: List<Double>): List<Double> {
    if (beatsUs.size < 2) return emptyList()
    val out = ArrayList<Double>(beatsUs.size - 1)
    for (i in 1 until beatsUs.size) out.add((beatsUs[i] - beatsUs[i - 1]) / 1000.0)
    return out
}

/**
 * Streaming HRV: fed beat timestamps (µs), keeps roughly the last ~60 s of beats (matching
 * the iOS HeartRateVariabilityReporter) and reports RMSSD/SDNN over that window.
 */
class HeartRateVariability {
    private val beats = ArrayDeque<Double>()
    private val windowUs = 60.0 * DSPConstants.usPerS

    /** Register a beat time (µs). */
    fun addBeat(tUs: Double) {
        beats.addLast(tUs)
        val cutoff = tUs - windowUs
        while (beats.isNotEmpty() && beats.first() < cutoff) beats.removeFirst()
    }

    fun result(): HrvResult? = computeHrv(rrIntervalsMsFromBeats(beats.toList()))

    fun rmssdMs(): Double? = result()?.rmssdMs

    fun reset() {
        beats.clear()
    }
}
