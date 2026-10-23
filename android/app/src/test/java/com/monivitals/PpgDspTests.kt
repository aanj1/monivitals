//
//  PpgDspTests.kt
//  MoniVitals (Android)
//
//  Synthetic-signal tests for the camera-pulse DSP (no camera needed): heart rate on a
//  known-BPM pulse, respiratory rate on an amplitude-modulated pulse, HRV RMSSD on known RR
//  intervals, and the contact/quality classifier on good vs flat input.
//

package com.monivitals

import com.monivitals.dsp.PpgHeartRate
import com.monivitals.dsp.classifyContact
import com.monivitals.dsp.computeHrv
import com.monivitals.dsp.crossingRate
import com.monivitals.dsp.estimateHeartRate
import com.monivitals.dsp.estimateRespiratoryRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Build a realistic-ish pulse: a periodic narrow systolic peak at the given bpm. */
private fun syntheticPulse(
    bpm: Double,
    fs: Double,
    seconds: Double,
    ampMod: Double = 0.0,
    respBpm: Double = 0.0,
): DoubleArray {
    val n = (fs * seconds).toInt()
    val out = DoubleArray(n)
    val periodS = 60.0 / bpm
    for (i in 0 until n) {
        val t = i / fs
        val phase = (t % periodS) / periodS // 0..1 within a beat
        // A skewed pulse: sharp systolic upstroke + a small dicrotic notch.
        val beat = exp(-((phase - 0.15) * (phase - 0.15)) / (2 * 0.02)) +
            0.35 * exp(-((phase - 0.4) * (phase - 0.4)) / (2 * 0.03))
        var amp = 1.0
        if (ampMod > 0.0 && respBpm > 0.0) {
            val respHz = respBpm / 60.0
            amp = 1.0 + ampMod * sin(2 * PI * respHz * t)
        }
        out[i] = beat * amp
    }
    return out
}

class HeartRateTests {
    @Test
    fun recoversKnown72Bpm() {
        val fs = 30.0
        val samples = syntheticPulse(bpm = 72.0, fs = fs, seconds = 15.0)
        val hr = estimateHeartRate(samples, fs)
        assertNotNull("HR should be available", hr)
        assertTrue("HR ${hr} should be within 2 bpm of 72", abs(hr!! - 72.0) <= 2.0)
    }

    @Test
    fun recoversKnown48Bpm() {
        val fs = 30.0
        val samples = syntheticPulse(bpm = 48.0, fs = fs, seconds = 20.0)
        val hr = estimateHeartRate(samples, fs)
        assertNotNull(hr)
        assertTrue("HR ${hr} should be within 2 bpm of 48", abs(hr!! - 48.0) <= 2.0)
    }

    @Test
    fun recoversKnown120Bpm() {
        val fs = 30.0
        val samples = syntheticPulse(bpm = 120.0, fs = fs, seconds = 12.0)
        val hr = estimateHeartRate(samples, fs)
        assertNotNull(hr)
        assertTrue("HR ${hr} should be within 2 bpm of 120", abs(hr!! - 120.0) <= 2.0)
    }
}

class RespiratoryRateTests {
    @Test
    fun recoversAmplitudeModulationBreathingRate() {
        val fs = 30.0
        // 72 bpm pulse whose amplitude is modulated at 15 breaths/min.
        val samples = syntheticPulse(bpm = 72.0, fs = fs, seconds = 40.0, ampMod = 0.5, respBpm = 15.0)
        val rr = estimateRespiratoryRate(samples, fs)
        assertNotNull("Respiratory rate should be available", rr)
        assertTrue("Resp ${rr} should be near 15 br/min", abs(rr!! - 15.0) <= 3.0)
    }

    @Test
    fun crossingRateOnPureSine() {
        val fs = 30.0
        val seconds = 30.0
        val freq = 0.25 // 15 / min
        val n = (fs * seconds).toInt()
        val x = DoubleArray(n) { sin(2 * PI * freq * (it / fs)) }
        val bpm = crossingRate(x, fs)
        assertNotNull(bpm)
        assertTrue("crossingRate ${bpm} should be near 15", abs(bpm!! - 15.0) <= 1.5)
    }

    @Test
    fun flatSignalGivesNoRate() {
        assertNull(crossingRate(DoubleArray(300) { 5.0 }, 30.0))
    }
}

class HrvTests {
    @Test
    fun rmssdOnKnownIntervals() {
        // 9 intervals (≥ 8 clean), successive diffs all ±50 (8 pairs, ≥ 6) → RMSSD = 50.
        val rr = listOf(800.0, 850.0, 800.0, 850.0, 800.0, 850.0, 800.0, 850.0, 800.0)
        val hrv = computeHrv(rr)
        assertNotNull(hrv)
        assertEquals(50.0, hrv!!.rmssdMs, 0.001)
    }

    @Test
    fun sdnnOnKnownIntervals() {
        // 8 intervals, mean 825, deviations ±25 → SDNN = 25.
        val rr = listOf(800.0, 850.0, 800.0, 850.0, 800.0, 850.0, 800.0, 850.0)
        val hrv = computeHrv(rr)
        assertNotNull(hrv)
        assertEquals(25.0, hrv!!.sdnnMs, 0.001)
    }

    @Test
    fun ectopicIntervalsAreRejected() {
        // A giant artifact interval must not blow up RMSSD; its neighbours also lose their
        // pairing (the artifact was between them, so they were never truly adjacent beats).
        val clean = listOf(800.0, 810.0, 805.0, 815.0, 808.0, 812.0, 806.0, 814.0, 809.0)
        val withArtifact = listOf(800.0, 810.0, 5000.0, 805.0, 815.0, 808.0, 812.0, 806.0, 814.0, 809.0)
        val a = computeHrv(clean)!!
        val b = computeHrv(withArtifact)!!
        assertTrue("Artifact-filtered RMSSD should stay small", b.rmssdMs < 50.0)
        assertTrue("Should be close to the clean estimate", abs(a.rmssdMs - b.rmssdMs) < 15.0)
    }

    @Test
    fun tooFewBeatsReturnsNull() {
        assertNull(computeHrv(listOf(800.0, 810.0)))
        // Below the 8-clean-interval floor (would previously report at 3+).
        assertNull(computeHrv(listOf(800.0, 850.0, 800.0, 850.0, 800.0)))
    }

    @Test
    fun implausibleHrvReturnsNull() {
        // Alternating intervals that survive every per-interval filter (median ratio
        // 0.91–1.09, pair diff 250 ≤ 20% of the pair mean) but give RMSSD = 250 ms —
        // beyond the 200 ms physiological cap → no value rather than a junk number.
        val rr = listOf(1250.0, 1500.0, 1250.0, 1500.0, 1250.0, 1500.0, 1250.0, 1500.0, 1250.0, 1500.0)
        assertNull(computeHrv(rr))
    }
}

class ContactQualityTests {
    @Test
    fun brightStableInputReadsAsFingerPresent() {
        // A fingertip over the torch: bright, near-saturated, low wobble.
        val good = DoubleArray(60) { 220.0 + sin(it * 0.3) * 2.0 }
        assertTrue("A bright, stable trace should read as a finger present", classifyContact(good))
    }

    @Test
    fun darkInputReadsAsNoFinger() {
        val dark = DoubleArray(60) { 15.0 }
        assertFalse("A dark trace should read as no finger", classifyContact(dark))
    }

    @Test
    fun jumpyInputReadsAsNoFinger() {
        // Bright-ish but wildly unstable (finger sliding / no contact).
        val jumpy = DoubleArray(60) { if (it % 2 == 0) 240.0 else 60.0 }
        assertFalse("A jumpy trace should not read as a steady finger", classifyContact(jumpy))
    }
}

/**
 * HR staleness blanking (the streaming PpgHeartRate.bpm() must expire): a rolling median never
 * decays on its own, so without a last-beat age check bpm() would pin the old HR forever once
 * the pulse vanishes while the finger stays on the lens. Mirrors the iOS 8 s tail-window that
 * naturally empties.
 */
class HeartRateStalenessTests {
    private val fs = 30.0
    private val dt = 1_000_000.0 / 30.0 // µs per sample

    /** Feed a clean pulse so beats + a rolling median exist. Returns the final sample time (µs). */
    private fun feedPulse(hr: PpgHeartRate, bpm: Double, seconds: Double, startUs: Double): Double {
        val beatPeriod = 60.0 / bpm
        var t = startUs
        val n = (seconds * fs).toInt()
        for (i in 0 until n) {
            val phase = ((t - startUs) / 1_000_000.0) / beatPeriod
            val frac = phase - kotlin.math.floor(phase)
            // Narrow systolic-like peak once per beat.
            val d = frac - 0.5
            val v = exp(-(d * d) / 0.002)
            hr.process(v, t)
            t += dt
        }
        return t
    }

    @Test
    fun bpmAvailableWhilePulsePresent() {
        val hr = PpgHeartRate(fs)
        feedPulse(hr, bpm = 72.0, seconds = 12.0, startUs = 0.0)
        val bpm = hr.bpm()
        assertNotNull("bpm should be reported while a pulse is present", bpm)
        assertTrue("bpm ${bpm} near 72", abs(bpm!! - 72.0) <= 6.0)
    }

    @Test
    fun bpmBlanksAfterPulseVanishes() {
        val hr = PpgHeartRate(fs)
        var t = feedPulse(hr, bpm = 72.0, seconds = 12.0, startUs = 0.0)
        assertNotNull("sanity: bpm present before the gap", hr.bpm())
        // Now feed a flat (beat-free) stretch longer than the ~9 s staleness window.
        val quietN = (11.0 * fs).toInt()
        for (i in 0 until quietN) {
            hr.process(0.0, t)
            t += dt
        }
        assertNull("bpm must blank once the newest beat is older than the staleness window", hr.bpm())
    }
}
