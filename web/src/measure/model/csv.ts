/**
 * CSV export for a saved pulse reading. Pure builders (testable without a DB)
 * plus a DB-reading entry point that produces a downloadable ZIP bundle.
 */
import { readSessionPulse, type SessionPulse } from './repository';
import { db } from './db';
import { zipStore } from './zip';
import type { MetricsRecord, Session } from './types';

export interface BundleData {
  session: Session;
  pulse: SessionPulse;
  metrics: MetricsRecord[];
}

/** RFC-4180 field escaping; null/undefined → empty cell. */
export function csvEscape(v: string | number | null | undefined): string {
  if (v == null) return '';
  const s = String(v);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

function toCsv(header: string[], rows: (string | number | null | undefined)[][]): string {
  const lines = [header.join(',')];
  for (const row of rows) lines.push(row.map(csvEscape).join(','));
  return lines.join('\n') + '\n';
}

export function buildSessionMeta(d: BundleData): string {
  return JSON.stringify(
    {
      schemaVersion: 2,
      reading: {
        id: d.session.id,
        startedAt: d.session.startedAt,
        endedAt: d.session.endedAt,
        notes: d.session.notes,
        placement: d.session.placement ?? 'fingertip',
        summary: d.session.summary,
      },
    },
    null,
    2,
  );
}

export function buildBundleFiles(d: BundleData): Record<string, string> {
  const { pulse } = d;

  const pulseRows: (string | number)[][] = [];
  for (let i = 0; i < pulse.t.length; i++) pulseRows.push([pulse.t[i], pulse.pulse[i]]);

  const metricsRows = d.metrics.map((m) => [
    m.tUs,
    m.hrBpm ?? '',
    m.signalQuality,
  ]);

  return {
    'reading_meta.json': buildSessionMeta(d),
    'pulse.csv': toCsv(['t_us', 'pulse'], pulseRows),
    'metrics.csv': toCsv(['t_us', 'heart_rate_bpm', 'signal_quality'], metricsRows),
  };
}

/** Read a saved reading from the DB and produce a downloadable ZIP bundle. */
export async function exportSession(sessionId: string): Promise<{ blob: Blob; filename: string }> {
  const session = await db.sessions.get(sessionId);
  if (!session) throw new Error(`Reading ${sessionId} not found`);
  const [pulse, metrics] = await Promise.all([
    readSessionPulse(sessionId),
    db.metrics.where('sessionId').equals(sessionId).sortBy('tUs'),
  ]);
  const files = buildBundleFiles({ session, pulse, metrics });
  const enc = new TextEncoder();
  const zip = zipStore(Object.entries(files).map(([name, content]) => ({ name, data: enc.encode(content) })));
  const folder = session.id.slice(0, 8);
  // zipStore returns a Uint8Array view; copy into a fresh ArrayBuffer for the Blob.
  const blob = new Blob([zip.slice()], { type: 'application/zip' });
  return { blob, filename: `monivitals_reading_${folder}.zip` };
}
