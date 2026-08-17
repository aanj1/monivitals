/** Local-first data model for saved pulse readings. Everything stays on this device. */

/** App settings (persisted in localStorage). */
export interface AppSettings {
  theme: 'dark' | 'light' | 'system';
  /** The one-time welcome + acknowledgment has been accepted. */
  onboarded: boolean;
}

/** A saved fingertip or palm reading (a "clip" in the Review list). */
export interface Session {
  id: string;
  /** Missing on earlier readings, which were all captured at the fingertip. */
  placement?: 'fingertip' | 'palm';
  startedAt: string; // ISO-8601
  endedAt: string | null;
  /** A short note the person can add. */
  notes: string;
  /** Summary vitals at the end of the reading. */
  summary: {
    hrBpm: number | null;
    signalQuality: number;
  };
}

/** One stored block of pulse samples with explicit per-sample timestamps (µs). */
export interface PulseChunk {
  id?: number;
  sessionId: string;
  tStartUs: number;
  sampleRateHz: number;
  /** Per-sample device timestamps (µs). */
  t: Float64Array;
  /** Inverted selected-channel mean per frame (pulse = upward). */
  pulse: Float64Array;
}

/** A per-tick metrics reading captured during a session. */
export interface MetricsRecord {
  id?: number;
  sessionId: string;
  tUs: number;
  hrBpm: number | null;
  signalQuality: number;
}
