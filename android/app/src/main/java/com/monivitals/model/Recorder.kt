//
//  Recorder.kt
//  MoniVitals (Android)
//
//  Buffers live pulse samples and flushes them to the store in ~2 s chunks. One instance per
//  active reading. Robust to interruptions (flush on stop).
//

package com.monivitals.model

import com.monivitals.source.PpgPacket
import com.monivitals.source.StreamKind
import com.monivitals.dsp.DSPConstants
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class Recorder(val sessionId: String, private val store: Store = Store.shared) {
    val counts: MutableMap<StreamKind, Int> = mutableMapOf(StreamKind.PPG to 0)

    // Chunk file writes grow over a reading, so they run on a dedicated background thread —
    // never on the caller (main) thread. Buffers are swapped before the write is submitted.
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "monivitals-recorder-io") }

    private var tStartUs: Double? = null
    private var rate = 0
    private val tBuf = mutableListOf<Double>()
    private val greenBuf = mutableListOf<Double>()
    private val redBuf = mutableListOf<Double>()
    private val flushSamples = 240

    fun ingestPpg(p: PpgPacket) {
        if (tStartUs == null) tStartUs = p.tUs
        rate = p.sampleRateHz
        val dt = DSPConstants.usPerS / p.sampleRateHz
        for (i in p.green.indices) {
            tBuf.add(sampleTimeUs(p, i, dt))
            greenBuf.add(p.green[i])
            redBuf.add(p.red[i])
        }
        counts[StreamKind.PPG] = (counts[StreamKind.PPG] ?: 0) + p.green.size
        if (redBuf.size >= flushSamples) flush()
    }

    /** Persist buffered samples as a StreamChunk. */
    fun flush() {
        val tStart = tStartUs
        if (redBuf.isEmpty() || tStart == null) return
        val chunk = StreamChunk(
            id = null, sessionId = sessionId, stream = StreamKind.PPG,
            tStartUs = tStart, sampleRateHz = rate,
            t = tBuf.toList(), green = greenBuf.toList(), red = redBuf.toList(),
        )
        tBuf.clear(); greenBuf.clear(); redBuf.clear()
        tStartUs = null
        io.execute { store.addChunk(chunk) }
    }

    /** Flush remaining buffers and block until all queued writes are persisted. */
    fun finish() {
        flush()
        io.shutdown()
        try {
            io.awaitTermination(3, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        // The store buffers chunk/metrics appends in memory (~10 s cadence); a stopped
        // reading must be fully on disk before the session is marked ended.
        store.flushPending()
    }

    companion object {
        /**
         * Timestamp (µs) for sample [i] of packet [p]. Prefers the real per-frame capture time
         * carried on the camera path; falls back to the synthetic fixed-rate grid (`tUs + i*dt`)
         * when `timesUs` is absent — the BLE path leaves it empty and its 100 Hz grid is exact.
         * Pure so the fallback is unit-testable. Mirrors iOS Store's per-sample timing.
         */
        fun sampleTimeUs(p: PpgPacket, i: Int, dt: Double): Double =
            if (i < p.timesUs.size) p.timesUs[i] else p.tUs + i * dt
    }
}
