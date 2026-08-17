/** IndexedDB storage via Dexie. Everything stays local on this device. */
import Dexie, { type Table } from 'dexie';
import type { MetricsRecord, PulseChunk, Session } from './types';

export class MoniVitalsDB extends Dexie {
  sessions!: Table<Session, string>;
  chunks!: Table<PulseChunk, number>;
  metrics!: Table<MetricsRecord, number>;

  constructor() {
    super('monivitals');
    // v2 replaces the old multi-signal schema with a single pulse-trace store.
    this.version(2).stores({
      sessions: 'id, startedAt',
      chunks: '++id, sessionId',
      metrics: '++id, sessionId',
      // Drop tables from the previous device-era schema.
      subjects: null,
      annotations: null,
      calibrations: null,
    });
  }
}

export const db = new MoniVitalsDB();
