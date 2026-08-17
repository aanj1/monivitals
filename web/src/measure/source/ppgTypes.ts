/**
 * Types for the camera photoplethysmography (PPG) pipeline.
 *
 * The rear camera, with a fingertip held over the lens, produces one brightness
 * sample per animation frame (the mean of the red channel over a centered region).
 * Those samples flow through the same ring-buffer + engine plumbing the app has
 * always used, so the waveform card and metrics engine keep working.
 */

/** Signals the app can carry. The camera source only ever emits `'ppg'`. */
export type StreamKind = 'ppg';

/**
 * A batch of camera pulse samples with per-batch device timestamp.
 *
 * `red` is the mean red-channel brightness over the region of interest for each
 * frame (already inverted so a pulse reads as an upward deflection). `green` is
 * the matching green-channel mean, kept for signal-quality checks.
 */
export interface PpgPacket {
  kind: 'ppg';
  /** Device timestamp (µs) of the FIRST sample in the batch. */
  tUs: number;
  /** Measured capture frame rate (≈30). */
  sampleRateHz: number;
  /** Inverted red-channel mean per frame (pulse = upward). */
  red: Float64Array;
  /** Green-channel mean per frame (for quality checks). */
  green: Float64Array;
}
