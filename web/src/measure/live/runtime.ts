/**
 * Non-reactive live runtime: the pulse waveform ring buffer and the on-device model.
 * These are mutated at full frame rate by the store's frame handlers and read by the
 * waveform canvas directly, keeping high-rate data out of React state.
 */
import { MetricsEngine } from '../dsp/engine';
import { WaveBuffer } from './ringBuffer';

export const buffers = {
  /** The live camera pulse trace (inverted red = pulse). */
  pulse: new WaveBuffer(8192),
};

export const engine = new MetricsEngine();

export function resetRuntime(): void {
  for (const b of Object.values(buffers)) b.clear();
  engine.reset();
}
