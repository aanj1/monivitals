//
//  MetricsEngine.kt
//  MoniVitals (Android)
//
//  Ties the pulse detectors together into a live metrics engine. Ingests camera pulse
//  samples (and phone motion) and produces a metrics snapshot: heart rate, respiratory rate,
//  HRV, movement, and signal quality. All algorithms run on-device.
//

package com.monivitals.dsp

import com.monivitals.source.PpgPacket
import kotlin.math.abs
import kotlin.math.max

/** App-computed metrics at a point in time. */
data class MetricsSnapshot(
    val tUs: Double,
    /** beats/min, null until a pulse is confidently found. */
    val hrBpm: Double?,
    /** breaths/min, null until a breath rhythm is resolvable. */
    val respRateBpm: Double?,
    /** HRV RMSSD in ms, null until enough clean beats. */
    val hrvMs: Double?,
    /** HRV SDNN in ms, null until enough clean beats. */
    val hrvSdnnMs: Double?,
    /** 0..100 fingertip signal quality. */
    val contactQuality: Int,
    /** 0..255 phone movement. */
    val movement: Int,
)

/**
 * On-device inference surface: turns a timestamp into a [MetricsSnapshot]. The MoniVitals DSP
 * metrics engine implements this so the app treats it like a model.
 */
interface VitalsModel {
    val name: String
    fun infer(tUs: Double): MetricsSnapshot
}

class MetricsEngine : VitalsModel {
    override val name: String = "MoniVitals on-device model v1"

    override fun infer(tUs: Double): MetricsSnapshot = snapshot(tUs)

    private var hr: PpgHeartRate? = null
    private var resp: RespiratoryRate? = null
    private val hrv = HeartRateVariability()
    private var contact: ContactQualityEstimator? = null
    private var ppgFs = 0
    private var motionThresh: Double? = null

    // Contact-dip grace + staleness (ported from iOS MetricsEngine): a momentary contact
    // loss holds the last values instead of instantly blanking; only a sustained loss
    // (~2 s) resets the detectors, and held values are blanked once they go stale
    // (~10 s without a fresh clean compute) so a bad early number can't stay pinned.
    private var noContactTicks = 0
    private val contactGraceTicks = 8 // ~2 s at the 0.25 s metrics tick
    private val staleLimitTicks = 40 // ~10 s at the 0.25 s metrics tick
    private var lastHr: Double? = null
    private var lastResp: Double? = null
    private var lastRmssd: Double? = null
    private var lastSdnn: Double? = null
    private var hrStaleTicks = 0
    private var respStaleTicks = 0
    private var hrvStaleTicks = 0

    /**
     * Whether the most recent snapshot saw contact + clean-enough signal. Beats detected
     * outside that state still mark the waveform, but are NOT fed to HRV — beats from a
     * no-contact/noisy stretch would contaminate the 60 s HRV window and the first HRV
     * result after recovery (mirrors iOS MetricsEngine.beatsCleanForHrv).
     */
    private var beatsCleanForHrv = false

    /** Recent detected beat times (µs), capped — for waveform markers. */
    var recentBeats = mutableListOf<Double>()
        private set

    fun setMotionThresh(thresh: Double) {
        motionThresh = thresh
        contact?.setMotionThresh(thresh)
    }

    /** Feed a block of camera pulse samples. */
    fun ingestPpg(p: PpgPacket) {
        // The camera's EWMA fps estimate flip-flops around integers (29↔30) once rounded;
        // recreating the detectors on every wobble wiped all filter state mid-measurement.
        // Only rebuild when the rate genuinely moved (> 1.5 Hz from the current detectors).
        if (hr == null || abs(p.sampleRateHz - ppgFs).toDouble() > 1.5) {
            hr = PpgHeartRate(p.sampleRateHz.toDouble())
            resp = RespiratoryRate(p.sampleRateHz.toDouble())
            contact = ContactQualityEstimator(p.sampleRateHz.toDouble(), motionThresh ?: DSPConstants.motionThresh)
            ppgFs = p.sampleRateHz
        }
        val dt = DSPConstants.usPerS / p.sampleRateHz
        for (i in p.red.indices) {
            val t = p.tUs + (i * dt)
            // Pulse detection runs on the (already inverted) red channel; contact quality reads
            // the raw brightness. The red channel is inverted upstream so a beat is an upstroke;
            // undo it for the brightness/contact read.
            // KNOWN DIVERGENCE from iOS: the iOS pipeline feeds its beat detector the GREEN
            // channel (better pulsatile SNR in transmissive fingertip PPG), while Android uses
            // RED. Kept deliberately for now — switching channels changes detector tuning and
            // is out of scope for this parity pass.
            val invertedRed = p.red[i]
            hr?.process(invertedRed, t)
            resp?.process(invertedRed)
            contact?.pushRed(255.0 - invertedRed)
            // Fold newly detected beats into the waveform; only clean-state beats feed HRV so
            // dips/noisy stretches can't contaminate the 60 s window (see beatsCleanForHrv).
            val beats = hr?.beats
            if (beats != null) {
                val newest = beats.lastOrNull()
                if (newest != null && (recentBeats.lastOrNull() ?: Double.NEGATIVE_INFINITY) < newest) {
                    if (beatsCleanForHrv) hrv.addBeat(newest)
                    pushCapped(recentBeats, newest)
                }
            }
        }
    }

    /** Feed one accelerometer magnitude sample. */
    fun ingestMotion(magnitude: Double) {
        contact?.pushMotion(magnitude)
    }

    /**
     * Produce a metrics snapshot at the given timestamp. Called on the ~0.25 s metrics
     * tick. Gating (ported from iOS MetricsEngine.snapshot):
     *   - fresh HR / breathing / HRV only when the finger is present AND smoothed quality
     *     is at least [DSPConstants.minQualityForVitals]; otherwise the last good values
     *     are held rather than computing on noise
     *   - a momentary contact dip (< ~2 s) holds the last values instead of blanking; the
     *     miss counter DECAYS on good ticks rather than zeroing, so a lone good tick amid
     *     real flicker can't hold a stale reading forever
     *   - sustained contact loss (> ~2 s) resets the HR buffers, HRV beat window and resp
     *     buffers so re-contact can't show stale/contaminated values
     *   - held values are blanked after ~10 s without a fresh clean compute
     */
    fun snapshot(tUs: Double): MetricsSnapshot {
        val quality = contact?.contactQuality() ?: 0
        val present = contact?.fingerPresent() ?: false
        val cleanEnough = quality >= DSPConstants.minQualityForVitals
        // Only beats seen during solid contact + clean signal feed HRV (gated in ingestPpg).
        beatsCleanForHrv = present && cleanEnough

        if (present) {
            // Decay the miss counter rather than zeroing it (see kdoc above).
            noContactTicks = max(0, noContactTicks - 1)
            if (noContactTicks == 0 && cleanEnough) {
                // Solid contact + clean signal → compute and push fresh estimates.
                val freshHr = hr?.bpm()
                if (freshHr != null) {
                    lastHr = freshHr
                    hrStaleTicks = 0
                } else {
                    hrStaleTicks += 1
                }
                val freshResp = resp?.bpm()
                if (freshResp != null) {
                    lastResp = freshResp
                    respStaleTicks = 0
                } else {
                    respStaleTicks += 1
                }
                val freshHrv = hrv.result()
                if (freshHrv != null) {
                    lastRmssd = freshHrv.rmssdMs
                    lastSdnn = freshHrv.sdnnMs
                    hrvStaleTicks = 0
                } else {
                    hrvStaleTicks += 1
                }
            } else {
                // Recovering from a dip, or the signal is too noisy for a fresh estimate —
                // hold the last values.
                hrStaleTicks += 1
                respStaleTicks += 1
                hrvStaleTicks += 1
            }
        } else {
            noContactTicks += 1
            if (noContactTicks >= contactGraceTicks) {
                // Sustained loss — clear everything so no stale reading lingers, and zero
                // the counter so returning contact recovers promptly.
                sustainedLossReset()
            } else {
                // Brief dip — hold the last readings so numbers don't flicker in and out.
                // No-contact ticks aren't staleness (iOS holds without incrementing here):
                // the grace window already bounds how long a dip can hold a value.
            }
        }

        // Staleness blanking: a held value must not outlive its usefulness. Clearing the HR
        // median (not the detector) mirrors iOS hrReporter.reset(), so a pulse that reappears
        // re-seeds the number from fresh beats instead of resurrecting the stale median.
        if (hrStaleTicks >= staleLimitTicks) {
            lastHr = null
            hr?.clearBpmBuffer()
        }
        if (respStaleTicks >= staleLimitTicks) lastResp = null
        if (hrvStaleTicks >= staleLimitTicks) {
            lastRmssd = null
            lastSdnn = null
        }

        return MetricsSnapshot(
            tUs = tUs,
            hrBpm = lastHr,
            respRateBpm = lastResp,
            hrvMs = lastRmssd,
            hrvSdnnMs = lastSdnn,
            contactQuality = quality,
            movement = contact?.movement() ?: 0,
        )
    }

    /**
     * After sustained finger-lift (~2 s), reset the beat/breath state so re-contact starts
     * clean — mirrors the iOS sustained-loss reset. The contact estimator itself is kept
     * (it must keep watching for the finger's return).
     */
    private fun sustainedLossReset() {
        hr?.reset()
        resp?.reset()
        hrv.reset()
        lastHr = null
        lastResp = null
        lastRmssd = null
        lastSdnn = null
        hrStaleTicks = 0
        respStaleTicks = 0
        hrvStaleTicks = 0
        noContactTicks = 0
    }

    fun reset() {
        hr = null
        resp = null
        contact = null
        hrv.reset()
        ppgFs = 0
        recentBeats.clear()
        noContactTicks = 0
        lastHr = null
        lastResp = null
        lastRmssd = null
        lastSdnn = null
        hrStaleTicks = 0
        respStaleTicks = 0
        hrvStaleTicks = 0
    }

    private fun pushCapped(arr: MutableList<Double>, v: Double, cap: Int = 64) {
        arr.add(v)
        if (arr.size > cap) arr.removeAt(0)
    }
}
