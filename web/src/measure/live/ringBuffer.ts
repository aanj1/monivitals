/**
 * Fixed-capacity ring buffer of (deviceTimeUs, value) points for live waveform
 * rendering. Mutated at full sample rate by the packet handlers and read by the
 * canvas on requestAnimationFrame — never via React state, so the UI is not
 * re-rendered per sample (§2.4).
 */
export class WaveBuffer {
  private ts: Float64Array;
  private vs: Float32Array;
  private head = 0;
  private filled = 0;

  constructor(public readonly capacity: number) {
    this.ts = new Float64Array(capacity);
    this.vs = new Float32Array(capacity);
  }

  push(t: number, v: number): void {
    this.ts[this.head] = t;
    this.vs[this.head] = v;
    this.head = (this.head + 1) % this.capacity;
    if (this.filled < this.capacity) this.filled++;
  }

  get length(): number {
    return this.filled;
  }

  latestT(): number | null {
    if (this.filled === 0) return null;
    return this.ts[(this.head - 1 + this.capacity) % this.capacity];
  }

  /**
   * Points with timestamp ≥ tFrom, in chronological order.
   *
   * Timestamps are pushed monotonically (the sources emit strictly increasing
   * device time), so we walk backward from the newest sample and stop at the first
   * one older than `tFrom` instead of scanning the whole buffer every frame — the
   * canvas calls this ~60×/s per waveform, and the visible window is a small slice
   * of a multi-thousand-point buffer.
   */
  window(tFrom: number): { t: number[]; v: number[] } {
    const cap = this.capacity;
    // Count how many trailing samples fall inside [tFrom, now].
    let inWindow = 0;
    for (let i = 0; i < this.filled; i++) {
      const idx = (this.head - 1 - i + cap * 2) % cap;
      if (this.ts[idx] < tFrom) break;
      inWindow++;
    }
    const t = new Array<number>(inWindow);
    const v = new Array<number>(inWindow);
    const start = this.head - inWindow + cap * 2;
    for (let i = 0; i < inWindow; i++) {
      const idx = (start + i) % cap;
      t[i] = this.ts[idx];
      v[i] = this.vs[idx];
    }
    return { t, v };
  }

  clear(): void {
    this.head = 0;
    this.filled = 0;
  }
}
