//
//  RecorderTimingTests.kt
//  MoniVitals (Android)
//
//  JVM tests for Recorder's per-sample timestamp selection: real per-frame camera times when
//  present, synthetic fixed-rate grid fallback when absent (the BLE path). Mirrors the iOS
//  timesUs fallback so pulse.csv t_us matches across platforms.
//

package com.monivitals

import com.monivitals.model.Recorder
import com.monivitals.source.PpgPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderTimingTests {
    private fun packet(timesUs: List<Double>) = PpgPacket(
        seq = 0, tUs = 1_000.0, sampleRateHz = 30,
        green = DoubleArray(4) { 0.0 }, red = DoubleArray(4) { 0.0 },
        timesUs = timesUs,
    )

    // 30 fps → 1_000_000 / 30 µs per sample.
    private val dt = 1_000_000.0 / 30.0

    @Test
    fun realFrameTimesUsedWhenPresent() {
        // Camera path: uneven, real frame PTS (fps drift) — used verbatim, not a synthetic grid.
        val real = listOf(1_000.0, 34_000.0, 70_000.0, 101_000.0)
        val p = packet(real)
        for (i in real.indices) {
            assertEquals(real[i], Recorder.sampleTimeUs(p, i, dt), 0.0)
        }
    }

    @Test
    fun fallsBackToGridWhenTimesUsEmpty() {
        // BLE path: timesUs empty → tUs + i*dt (its 100 Hz-style grid is exact).
        val p = packet(emptyList())
        for (i in 0 until 4) {
            assertEquals(1_000.0 + i * dt, Recorder.sampleTimeUs(p, i, dt), 1e-6)
        }
    }

    @Test
    fun fallsBackForIndicesBeyondTimesUs() {
        // Defensive: a short timesUs uses real times where available, grid past its end.
        val p = packet(listOf(1_000.0, 34_000.0))
        assertEquals(1_000.0, Recorder.sampleTimeUs(p, 0, dt), 0.0)
        assertEquals(34_000.0, Recorder.sampleTimeUs(p, 1, dt), 0.0)
        assertEquals(1_000.0 + 2 * dt, Recorder.sampleTimeUs(p, 2, dt), 1e-6)
        assertEquals(1_000.0 + 3 * dt, Recorder.sampleTimeUs(p, 3, dt), 1e-6)
    }
}
