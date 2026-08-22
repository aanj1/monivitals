import { CAPTURE_SECONDS, type Analysis, type CameraSample } from './ppg';

/**
 * A 0-100 signal-quality score for the saved reading.
 *
 * The analyzer itself reports a categorical `issue` rather than a number, but
 * MoniVitals' stored `Session.summary.signalQuality` and `MetricsRecord` are
 * numeric and the Review screen sorts on them. This derives a score from the
 * same quantities the analyzer already gates on — frame rate, motion and
 * clipping — so the number never disagrees with the verdict shown on screen.
 *
 * A capture that produced no usable estimate scores 0: Review should not rank
 * an unusable reading above a weak but real one.
 */
export function captureQuality(analysis: Analysis | null, samples: readonly CameraSample[]): number {
  if (!analysis || analysis.bpm === null || samples.length === 0) return 0;

  // Frame rate: 18 fps is the analyzer's floor, 30 and above is full marks.
  const fps = Math.max(0, Math.min(1, (analysis.fps - 18) / 12));

  // Motion: the analyzer rejects a capture once more than 12% of frames exceed
  // the 0.035 motion threshold, so score against that same fraction.
  const moving = samples.filter((sample) => sample.motion > 0.035).length / samples.length;
  const steady = Math.max(0, Math.min(1, 1 - moving / 0.12));

  // Clipping: 8% is the analyzer's ceiling.
  const clipped = samples.reduce((sum, sample) => sum + sample.clipped, 0) / samples.length;
  const exposure = Math.max(0, Math.min(1, 1 - clipped / 0.08));

  // Duration: a short capture is less trustworthy than a full one.
  const duration = Math.max(0, Math.min(1, analysis.seconds / CAPTURE_SECONDS));

  const score = 0.3 * fps + 0.3 * steady + 0.2 * exposure + 0.2 * duration;
  return Math.round(score * 100);
}
