/**
 * Heart rate from a camera pulse trace.
 *
 * The trace is detrended and band-passed into the pulse band, then beats are found
 * by peak detection with a refractory gap. Heart rate is the rolling median of the
 * beat-to-beat intervals. If too few clean beats are found, an autocorrelation
 * fallback estimates the dominant pulse period directly from the band-passed trace.
 *
 * Pure and testable on synthetic arrays — no camera needed.
 */
import {
  BEAT_REFRACTORY_MS,
  HR_MAX_BPM,
  HR_MIN_BPM,
  PPG_BP_HIGH_HZ,
  PPG_BP_LOW_HZ,
} from './constants';
import { Biquad } from './filters';
import { median } from './rollingStats';

/** Band-pass a trace into the pulse band. Returns a filtered copy. */
export function bandpassPulse(samples: readonly number[] | Float64Array, fs: number): Float64Array {
  const out = new Float64Array(samples.length);
  // Two cascaded biquads for a steeper roll-off around the narrow pulse band.
  const bp1 = Biquad.bandpass(PPG_BP_LOW_HZ, PPG_BP_HIGH_HZ, fs);
  const bp2 = Biquad.bandpass(PPG_BP_LOW_HZ, PPG_BP_HIGH_HZ, fs);
  for (let i = 0; i < samples.length; i++) {
    out[i] = bp2.process(bp1.process(samples[i]));
  }
  return out;
}

/** Sample indices of pulse peaks in a band-passed trace, with a refractory gap. */
export function detectBeats(filtered: Float64Array, fs: number): number[] {
  const refractory = Math.round((BEAT_REFRACTORY_MS / 1000) * fs);
  // Adaptive threshold: a fraction of the running peak amplitude.
  let peakEnv = 0;
  const peaks: number[] = [];
  let lastPeak = -Infinity;
  for (let i = 1; i < filtered.length - 1; i++) {
    const v = filtered[i];
    peakEnv = Math.max(v, 0.995 * peakEnv);
    const thresh = 0.3 * peakEnv;
    const isLocalMax = v > filtered[i - 1] && v >= filtered[i + 1];
    if (isLocalMax && v > thresh && i - lastPeak >= refractory) {
      peaks.push(i);
      lastPeak = i;
    }
  }
  return peaks;
}

/** Dominant pulse rate (bpm) via autocorrelation of a band-passed trace, or null. */
export function autocorrHeartRate(filtered: Float64Array, fs: number): number | null {
  const n = filtered.length;
  if (n < fs * 2) return null;
  const minLag = Math.floor((60 / HR_MAX_BPM) * fs);
  const maxLag = Math.ceil((60 / HR_MIN_BPM) * fs);
  let bestLag = -1;
  let bestVal = -Infinity;
  for (let lag = minLag; lag <= maxLag && lag < n; lag++) {
    let sum = 0;
    for (let i = 0; i + lag < n; i++) sum += filtered[i] * filtered[i + lag];
    if (sum > bestVal) {
      bestVal = sum;
      bestLag = lag;
    }
  }
  if (bestLag <= 0 || bestVal <= 0) return null;
  const bpm = (60 * fs) / bestLag;
  return bpm >= HR_MIN_BPM && bpm <= HR_MAX_BPM ? bpm : null;
}

/**
 * Estimate heart rate (bpm) from a raw camera pulse trace, or null if the trace is
 * too short or no plausible rate is found.
 */
export function estimateHeartRate(samples: readonly number[] | Float64Array, fs: number): number | null {
  if (samples.length < fs * 2 || fs <= 0) return null;
  const filtered = bandpassPulse(samples, fs);
  const peaks = detectBeats(filtered, fs);

  if (peaks.length >= 4) {
    const bpms: number[] = [];
    for (let i = 1; i < peaks.length; i++) {
      const rrS = (peaks[i] - peaks[i - 1]) / fs;
      const bpm = 60 / rrS;
      if (bpm >= HR_MIN_BPM && bpm <= HR_MAX_BPM) bpms.push(bpm);
    }
    if (bpms.length >= 3) return median(bpms);
  }
  // Fallback: fewer than a few clean beats — read the dominant period directly.
  return autocorrHeartRate(filtered, fs);
}
