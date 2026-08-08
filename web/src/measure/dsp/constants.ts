/**
 * DSP constants for the camera pulse pipeline. Mirrors the iOS on-device model.
 * `QUALITY_THRESH` is user-adjustable in Settings; the rest are fixed.
 */

/** Pulse band-pass, roughly 42–210 bpm. */
export const PPG_BP_LOW_HZ = 0.7;
export const PPG_BP_HIGH_HZ = 3.5;

/** Minimum gap between detected beats (refractory). */
export const BEAT_REFRACTORY_MS = 300;

/** Plausible heart-rate range for accepting a beat interval. */
export const HR_MEDIAN_N = 5;
export const HR_MIN_BPM = 40;
export const HR_MAX_BPM = 210;

/** Signal quality (0–100): below this the pulse is treated as unreliable. */
export const QUALITY_THRESH = 45;

/** Microseconds per second — used everywhere we convert device time. */
export const US_PER_S = 1_000_000;
