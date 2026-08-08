/**
 * Fingertip-contact and signal-quality estimate for the camera pulse.
 *
 * A fingertip pressed over the rear camera makes the frame bright and deeply red
 * (the lens sees blood-lit skin), and holds a steady baseline between heartbeats.
 * We combine three cues into a single 0–100 quality score:
 *
 *   1. brightness   — the red channel should be well-lit (finger + torch),
 *   2. redness      — red should dominate green (skin over the lens),
 *   3. steadiness   — the baseline should not jump frame-to-frame (a still finger).
 *
 * No finger over the lens (a normal scene) is dim, un-red, or jittery, so the score
 * falls and the UI shows friendly guidance instead of a reading.
 *
 * Pure and testable on synthetic arrays — no camera needed.
 */
import { clamp, mapRange, stddev } from './rollingStats';

/** Raw per-frame channel means (0–255), NOT the inverted pulse values. */
export interface FrameStats {
  redMean: number;
  greenMean: number;
}

/**
 * Score fingertip contact quality (0–100) from a window of recent raw frames.
 * A short window (≈1 s of frames) is enough; longer is fine.
 */
export function scoreContact(frames: readonly FrameStats[]): number {
  if (frames.length === 0) return 0;
  const reds = frames.map((f) => f.redMean);
  const meanRed = reds.reduce((s, v) => s + v, 0) / reds.length;
  const meanGreen = frames.reduce((s, f) => s + f.greenMean, 0) / frames.length;

  // 1. Brightness: a fingertip over the torch drives red high; dim scenes score low.
  const brightness = clamp(mapRange(meanRed, 90, 220, 0, 1), 0, 1);

  // 2. Redness: skin makes red dominate green. A normal scene is more balanced.
  const redness = clamp(mapRange(meanRed - meanGreen, 10, 80, 0, 1), 0, 1);

  // 3. Steadiness: relative baseline jitter. A still finger holds a steady DC level.
  const jitter = meanRed > 1 ? stddev(reds) / meanRed : 1;
  const steadiness = clamp(mapRange(jitter, 0.08, 0.005, 0, 1), 0, 1);

  const score = 100 * (0.4 * brightness + 0.35 * redness + 0.25 * steadiness);
  return Math.round(clamp(score, 0, 100));
}

/**
 * Rolling contact-quality estimator for the live pipeline. Feed raw per-frame means;
 * read {@link quality} for the current 0–100 score.
 */
export class ContactQualityEstimator {
  private readonly frames: FrameStats[] = [];
  constructor(private readonly window = 30) {}

  push(redMean: number, greenMean: number): void {
    this.frames.push({ redMean, greenMean });
    if (this.frames.length > this.window) this.frames.shift();
  }

  quality(): number {
    return scoreContact(this.frames);
  }

  reset(): void {
    this.frames.length = 0;
  }
}
