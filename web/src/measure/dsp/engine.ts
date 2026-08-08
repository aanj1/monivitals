/**
 * The on-device model that turns a camera pulse trace into a heart rate.
 *
 * It keeps a rolling window of recent pulse samples and, when asked, computes a
 * snapshot: heart rate plus a signal-quality score. Everything runs locally in the
 * browser — no camera is needed to test the maths.
 */
import type { PpgPacket } from '../source/ppgTypes';
import { ContactQualityEstimator } from './contactQuality';
import { HR_MEDIAN_N, QUALITY_THRESH, US_PER_S } from './constants';
import { estimateHeartRate } from './ppgHeartRate';
import { PpgFootDetector } from './ppgFoot';
import { RollingMedian } from './rollingStats';

/** A vitals reading at a moment in time. All values null until enough signal accrues. */
export interface MetricsSnapshot {
  tUs: number;
  /** Heart rate, beats per minute. */
  hrBpm: number | null;
  /** Signal quality, 0–100 (fingertip contact + steadiness). */
  signalQuality: number;
  /** True when the fingertip is well-placed and the signal is trustworthy. */
  contactGood: boolean;
  /** A recent beat was detected (drives the waveform pulse marker). */
  beat: boolean;
}

/** How long a window of pulse samples the model analyzes (seconds). */
const HR_WINDOW_S = 8;

export class MetricsEngine {
  readonly name = 'MoniVitals on-device model v1';

  private fs = 0;
  private readonly redWindow: number[] = [];
  private readonly hrMedian = new RollingMedian(HR_MEDIAN_N);
  private readonly quality = new ContactQualityEstimator();
  private foot?: PpgFootDetector;
  private beatFlag = false;

  /** Recent detected beat times (µs), capped — for the waveform pulse markers. */
  readonly recentBeats: number[] = [];

  private qualityThresh = QUALITY_THRESH;

  setQualityThresh(t: number): void {
    this.qualityThresh = t;
  }

  /** Ingest a batch of camera pulse samples (inverted red = pulse). */
  ingestPpg(p: PpgPacket): void {
    if (!this.foot || this.fs !== p.sampleRateHz) {
      this.foot = new PpgFootDetector(p.sampleRateHz);
      this.fs = p.sampleRateHz;
    }
    const dt = US_PER_S / p.sampleRateHz;
    const maxSamples = Math.ceil(HR_WINDOW_S * p.sampleRateHz);
    for (let i = 0; i < p.red.length; i++) {
      const t = p.tUs + Math.round(i * dt);
      this.redWindow.push(p.red[i]);
      const f = this.foot.process(p.red[i], t);
      if (f != null) {
        this.beatFlag = true;
        pushCapped(this.recentBeats, f);
      }
    }
    while (this.redWindow.length > maxSamples) this.redWindow.shift();
  }

  /** Feed raw per-frame channel means (0–255) for the contact-quality score. */
  pushFrameStats(redMean: number, greenMean: number): void {
    this.quality.push(redMean, greenMean);
  }

  /** Produce a vitals snapshot at the given device timestamp. */
  snapshot(tUs: number): MetricsSnapshot {
    const signalQuality = this.quality.quality();
    const contactGood = signalQuality >= this.qualityThresh;

    let hrBpm: number | null = null;

    if (contactGood && this.fs > 0 && this.redWindow.length > 0) {
      const hrWindow = tailWindow(this.redWindow, Math.ceil(HR_WINDOW_S * this.fs));
      const raw = estimateHeartRate(hrWindow, this.fs);
      if (raw != null) {
        this.hrMedian.push(raw);
        hrBpm = this.hrMedian.count >= 2 ? this.hrMedian.value() : raw;
      }
    }

    const beat = this.beatFlag;
    this.beatFlag = false;
    return {
      tUs,
      hrBpm,
      signalQuality,
      contactGood,
      beat,
    };
  }

  reset(): void {
    this.fs = 0;
    this.redWindow.length = 0;
    this.hrMedian.clear();
    this.quality.reset();
    this.foot = undefined;
    this.beatFlag = false;
    this.recentBeats.length = 0;
  }
}

/** Last `n` elements of an array (a copy). */
function tailWindow(arr: readonly number[], n: number): number[] {
  return arr.length <= n ? arr.slice() : arr.slice(arr.length - n);
}

function pushCapped(arr: number[], v: number, cap = 64): void {
  arr.push(v);
  if (arr.length > cap) arr.shift();
}
