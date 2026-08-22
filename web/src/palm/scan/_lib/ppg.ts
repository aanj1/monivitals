/** Camera-only signal processing. No simulated samples or default pulse rate.
 * POS projection: Wang et al., DOI 10.1109/TBME.2016.2609282, Algorithm 1.
 * Quality thresholds are engineering heuristics, not clinical validation.
 */
export interface CameraSample {
  time: number;
  r: number;
  g: number;
  b: number;
  motion: number;
  clipped: number;
}

export type SignalIssue = "collecting" | "frames" | "light" | "motion" | "weak" | "unstable" | "ready";
export interface Analysis {
  bpm: number | null;
  issue: SignalIssue;
  fps: number;
  seconds: number;
  trace: number[];
}

export const CAPTURE_SECONDS = 30;
const FS = 30;
const MIN_SECONDS = 12;
const MIN_BPM = 45;
const MAX_BPM = 180;
const mean = (x: readonly number[]) => x.reduce((a, b) => a + b, 0) / (x.length || 1);
const deviation = (x: readonly number[]) => { const m = mean(x); return Math.sqrt(mean(x.map(v => (v - m) ** 2))); };
const median = (x: number[]) => [...x].sort((a, b) => a - b)[Math.floor(x.length / 2)] ?? 0;

/** Matches the central square in the 4:3, object-fit:cover video preview. */
export function cameraCrop(width: number, height: number) {
  const size = Math.min(width, height * 4 / 3) * .32;
  return { x: (width - size) / 2, y: (height - size) / 2, size };
}

/** No skin-color cutoff. The person places and confirms the palm crop. */
export function samplePixels(pixels: Uint8ClampedArray, time: number, previous?: Uint8ClampedArray): CameraSample {
  const count = pixels.length / 4;
  let r = 0, g = 0, b = 0, clipped = 0, previousGreen = 0;
  for (let i = 0; i < pixels.length; i += 4) {
    r += pixels[i]; g += pixels[i + 1]; b += pixels[i + 2];
    if (Math.max(pixels[i], pixels[i + 1], pixels[i + 2]) >= 250 || Math.max(pixels[i], pixels[i + 1], pixels[i + 2]) <= 8) clipped++;
    if (previous) previousGreen += previous[i + 1];
  }
  r /= count; g /= count; b /= count; previousGreen /= count;
  let motion = 0;
  if (previous && previousGreen > 0 && g > 0) {
    for (let i = 0; i < pixels.length; i += 4) motion += Math.abs(pixels[i + 1] / g - previous[i + 1] / previousGreen);
    motion = motion / count + Math.abs(g - previousGreen) / previousGreen;
  }
  return { time, r, g, b, motion, clipped: clipped / count };
}

/** Only strictly increasing, finite frame timestamps can contribute. */
export function validSamples(samples: readonly CameraSample[]) {
  const clean: CameraSample[] = [];
  for (const s of samples) {
    if (![s.time, s.r, s.g, s.b, s.motion, s.clipped].every(Number.isFinite)) continue;
    if (s.r < 0 || s.g < 0 || s.b < 0 || s.r > 255 || s.g > 255 || s.b > 255 || s.motion < 0 || s.clipped < 0 || s.clipped > 1) continue;
    if (!clean.length || s.time > clean[clean.length - 1].time) clean.push(s);
  }
  return clean;
}

function resample(samples: CameraSample[]) {
  const first = samples[0].time;
  const count = Math.floor((samples[samples.length - 1].time - first) * FS) + 1;
  const channels: number[][] = [[], [], []];
  const keys = ["r", "g", "b"] as const;
  let j = 1;
  for (let i = 0; i < count; i++) {
    const t = first + i / FS;
    while (j < samples.length - 1 && samples[j].time < t) j++;
    const a = samples[j - 1], b = samples[j];
    const fraction = Math.max(0, Math.min(1, (t - a.time) / (b.time - a.time)));
    keys.forEach((key, channel) => channels[channel].push(a[key] + (b[key] - a[key]) * fraction));
  }
  return channels;
}

function pos(channels: number[][]) {
  const count = channels[0].length, window = Math.round(FS * 1.6);
  const signal = new Array<number>(count).fill(0), weights = new Array<number>(count).fill(0);
  for (let end = window; end <= count; end++) {
    const start = end - window;
    const normalized = channels.map(c => { const w = c.slice(start, end); const m = mean(w); return w.map(v => m > 0 ? v / m : 0); });
    const a = normalized[1].map((g, i) => g - normalized[2][i]);
    const b = normalized[1].map((g, i) => g + normalized[2][i] - 2 * normalized[0][i]);
    const denominator = deviation(b);
    const alpha = denominator > 1e-9 ? deviation(a) / denominator : 0;
    const h = a.map((v, i) => v + alpha * b[i]), center = mean(h);
    for (let i = 0; i < window; i++) { signal[start + i] += h[i] - center; weights[start + i]++; }
  }
  return signal.map((v, i) => weights[i] ? v / weights[i] : 0);
}

/** Remove slow illumination drift and smooth high frequency camera noise. */
function filter(input: number[]) {
  const radius = Math.round(FS * .65);
  const prefix = [0];
  input.forEach(v => prefix.push(prefix[prefix.length - 1] + v));
  const high = input.map((v, i) => {
    const a = Math.max(0, i - radius), b = Math.min(input.length, i + radius + 1);
    return v - (prefix[b] - prefix[a]) / (b - a);
  });
  return high.map((v, i) => ((high[i - 1] ?? v) + 2 * v + (high[i + 1] ?? v)) / 4);
}

function spectrum(signal: number[]) {
  if (signal.length < 2) return { bpm: MIN_BPM, concentration: 0 };
  const center = mean(signal);
  const x = signal.map((v, i) => (v - center) * (.5 - .5 * Math.cos(2 * Math.PI * i / (signal.length - 1))));
  const power: number[] = [];
  for (let bpm = MIN_BPM; bpm <= MAX_BPM; bpm++) {
    const angle = 2 * Math.PI * bpm / (60 * FS), cosine = Math.cos(angle), sine = Math.sin(angle);
    let re = 0, im = 0, c = 1, s = 0;
    for (const v of x) { re += v * c; im += v * s; const nextC = c * cosine - s * sine; s = s * cosine + c * sine; c = nextC; }
    power.push(re * re + im * im);
  }
  const peak = power.indexOf(Math.max(...power));
  const neighborhood = power.reduce((sum, p, i) => sum + (Math.abs(i - peak) <= 5 ? p : 0), 0);
  const total = power.reduce((a, b) => a + b, 0);
  return { bpm: peak + MIN_BPM, concentration: total > 1e-14 ? neighborhood / total : 0 };
}

function autocorrelation(signal: number[], bpm: number) {
  const center = mean(signal), x = signal.map(v => v - center);
  const lag = Math.round(FS * 60 / bpm);
  let dot = 0, a = 0, b = 0;
  for (let i = lag; i < x.length; i++) { dot += x[i] * x[i - lag]; a += x[i] ** 2; b += x[i - lag] ** 2; }
  return a * b > 1e-14 ? dot / Math.sqrt(a * b) : 0;
}

export function analyzeCamera(samples: readonly CameraSample[]): Analysis {
  const clean = validSamples(samples);
  const seconds = clean.length > 1 ? clean[clean.length - 1].time - clean[0].time : 0;
  const fps = seconds > 0 ? (clean.length - 1) / seconds : 0;
  const result: Analysis = { bpm: null, issue: "collecting", fps, seconds, trace: [] };
  if (clean.length < 3) return result;
  const intervals = clean.slice(1).map((s, i) => s.time - clean[i].time);
  // Never interpolate over missing chunks or accept repeated display frames as camera frames.
  const maxInterval = intervals.reduce((m, v) => v > m ? v : m, 0);
  if (seconds >= 2 && (fps < 18 || maxInterval > .3 || median(intervals) > 1 / 18)) return { ...result, issue: "frames" };
  if (mean(clean.map(s => s.g)) < 25 || mean(clean.map(s => s.g)) > 235 || mean(clean.map(s => s.clipped)) > .08) return { ...result, issue: "light" };
  if (clean.filter(s => s.motion > .035).length / clean.length > .12) return { ...result, issue: "motion" };
  if (seconds < 2) return result;
  const channels = resample(clean), projected = pos(channels), filtered = filter(projected);
  // Trim overlap/filter boundaries. The displayed trace therefore has a short delay.
  const signal = filtered.slice(FS, -FS);
  const sigma = deviation(signal);
  result.trace = sigma > .00002 ? signal.slice(-FS * 8).map(v => Math.max(-3, Math.min(3, (v - mean(signal)) / sigma))) : [];
  if (seconds < MIN_SECONDS) return result;
  if (sigma < .00008 || sigma > .04) return { ...result, issue: "weak" };
  const candidate = spectrum(signal);
  const green = channels[1].map(v => v / mean(channels[1]) - 1);
  const reference = spectrum(filter(green).slice(FS, -FS));
  if (candidate.concentration < .48 || reference.concentration < .35 || autocorrelation(signal, candidate.bpm) < .5 || Math.abs(candidate.bpm - reference.bpm) > 6) return { ...result, issue: "weak" };
  // Independent time windows must support the same rate. No numerical result on disagreement.
  if (seconds >= 22) {
    const middle = Math.floor(signal.length / 2);
    const early = spectrum(signal.slice(0, middle)), late = spectrum(signal.slice(middle));
    if (early.concentration < .4 || late.concentration < .4 || Math.abs(early.bpm - late.bpm) > 7 || Math.abs(late.bpm - candidate.bpm) > 7) return { ...result, issue: "unstable" };
  }
  return { ...result, bpm: candidate.bpm, issue: "ready" };
}

export const signalMessages: Record<SignalIssue, string> = {
  collecting: "Collecting color changes. Keep your palm still.",
  frames: "The camera is dropping frames. Close other camera apps and try again.",
  light: "Adjust the light. Use bright, even daylight without glare.",
  motion: "Too much movement. Rest your wrist and keep the camera still.",
  weak: "The pulse signal is too weak. Fill the outline with your palm and use steady daylight.",
  unstable: "The signal changed during capture. Rest your hand and try again.",
  ready: "A repeating signal is visible. Keep your palm still.",
};
