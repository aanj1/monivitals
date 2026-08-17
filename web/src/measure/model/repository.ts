/** CRUD helpers and pulse-storage over the Dexie database. */
import { db } from './db';
import type { MetricsRecord, PulseChunk, Session } from './types';

// ---- sessions ----
export async function createSession(s: Session): Promise<void> {
  await db.sessions.put(s);
}
export async function endSession(
  id: string,
  endedAt: string,
  summary: Session['summary'],
): Promise<void> {
  await db.sessions.update(id, { endedAt, summary });
}
export const getSession = (id: string) => db.sessions.get(id);
export const listSessions = () => db.sessions.orderBy('startedAt').reverse().toArray();
export async function deleteSession(id: string): Promise<void> {
  await db.transaction('rw', db.chunks, db.metrics, db.sessions, async () => {
    await db.chunks.where('sessionId').equals(id).delete();
    await db.metrics.where('sessionId').equals(id).delete();
    await db.sessions.delete(id);
  });
}

// ---- metrics ----
export async function addMetrics(m: MetricsRecord): Promise<void> {
  await db.metrics.add(m);
}
export const listMetrics = (sessionId: string) =>
  db.metrics.where('sessionId').equals(sessionId).sortBy('tUs');

// ---- reading the pulse trace back ----
export interface SessionPulse {
  t: Float64Array;
  pulse: Float64Array;
}

export async function readSessionPulse(sessionId: string): Promise<SessionPulse> {
  const chunks = await db.chunks.where('sessionId').equals(sessionId).sortBy('tStartUs');
  return {
    t: concatF64(chunks.map((c) => c.t)),
    pulse: concatF64(chunks.map((c) => c.pulse)),
  };
}

/** Store a slice of the pulse trace for a saved reading. */
export async function saveSessionPulse(
  sessionId: string,
  t: readonly number[],
  pulse: readonly number[],
  sampleRateHz: number,
): Promise<void> {
  if (t.length === 0) return;
  const chunk: PulseChunk = {
    sessionId,
    tStartUs: t[0],
    sampleRateHz,
    t: Float64Array.from(t),
    pulse: Float64Array.from(pulse),
  };
  await db.chunks.add(chunk);
}

function concatF64(arrs: Float64Array[]): Float64Array {
  const n = arrs.reduce((s, a) => s + a.length, 0);
  const out = new Float64Array(n);
  let o = 0;
  for (const a of arrs) {
    out.set(a, o);
    o += a.length;
  }
  return out;
}
