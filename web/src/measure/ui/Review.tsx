import { useEffect, useRef, useState } from 'react';
import { useAppStore } from '../store/appStore';
import {
  deleteSession,
  listMetrics,
  listSessions,
  readSessionPulse,
  type SessionPulse,
} from '../model/repository';
import type { MetricsRecord, Session } from '../model/types';
import { resolveCssColor } from '../util/cssColor';

export function ReviewScreen() {
  const exportZip = useAppStore((s) => s.exportSessionZip);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [selected, setSelected] = useState<Session | null>(null);
  const [pulse, setPulse] = useState<SessionPulse | null>(null);
  const [metrics, setMetrics] = useState<MetricsRecord[]>([]);

  const refresh = () => void listSessions().then(setSessions);
  useEffect(refresh, []);

  const open = async (s: Session) => {
    setSelected(s);
    const [pu, me] = await Promise.all([readSessionPulse(s.id), listMetrics(s.id)]);
    setPulse(pu);
    setMetrics(me);
  };

  const remove = async (s: Session) => {
    if (!confirm(`Delete this reading from ${new Date(s.startedAt).toLocaleString()}? This can’t be undone.`)) return;
    await deleteSession(s.id);
    if (selected?.id === s.id) { setSelected(null); setPulse(null); }
    refresh();
  };

  return (
    <>
      <div className="card">
        <h2>Your readings</h2>
        {sessions.length === 0 ? (
          <div className="empty-state" style={{ padding: '20px 12px' }}>
            <div className="empty-badge" aria-hidden="true">📁</div>
            <p className="muted small" style={{ margin: 0 }}>No readings yet.</p>
            <p className="muted small" style={{ margin: 0 }}>Save a reading from Palm scan and it’ll show up here.</p>
          </div>
        ) : (
          <ul className="session-list">
            {sessions.map((s) => (
              <li key={s.id} className={`session-row ${selected?.id === s.id ? 'active' : ''}`}>
                <div className="session-main">
                  <div className="session-title">
                    <span className="session-subject">{s.summary.hrBpm != null ? `${Math.round(s.summary.hrBpm)} bpm` : '—'}</span>
                    <span className="chip">{s.placement === 'palm' ? 'Palm scan' : 'Fingertip'}</span>
                    {s.notes && <span className="chip">{s.notes}</span>}
                  </div>
                  <div className="small muted">{new Date(s.startedAt).toLocaleString()}</div>
                </div>
                <span className="session-duration" aria-label="Signal quality">✨ {s.summary.signalQuality}/100</span>
                <div className="row session-actions">
                  <button className="btn secondary" onClick={() => void open(s)}>View</button>
                  <button className="btn secondary" onClick={() => void exportZip(s.id)}>Export CSV</button>
                  <button className="btn danger" onClick={() => void remove(s)}>Delete</button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>

      {selected && pulse && (
        <div className="card">
          <div className="row between">
            <h3>{selected.placement === 'palm' ? 'Palm scan' : 'Fingertip'} · {new Date(selected.startedAt).toLocaleString()}</h3>
            <button className="btn" onClick={() => void exportZip(selected.id)}>Export CSV</button>
          </div>
          <div className="tiles" style={{ marginBottom: 12 }}>
            <div className="tile"><div className="label">Heart rate</div><div className="value">{selected.summary.hrBpm != null ? Math.round(selected.summary.hrBpm) : '—'}<span className="unit">bpm</span></div></div>
            <div className="tile"><div className="label">Signal quality</div><div className="value">{selected.summary.signalQuality}<span className="unit">/100</span></div></div>
            <div className="tile"><div className="label">Readings kept</div><div className="value">{metrics.length}</div></div>
          </div>

          <h3>Pulse</h3>
          <StaticPlot t={pulse.t} v={pulse.pulse} color="var(--ppg)" />
        </div>
      )}
    </>
  );
}

/** Static downsampled plot of a saved pulse trace. */
function StaticPlot({ t, v, color }: { t: Float64Array; v: Float64Array; color: string }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const [resizeTick, setResizeTick] = useState(0);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => setResizeTick((n) => n + 1));
    ro.observe(canvas);
    return () => ro.disconnect();
  }, []);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const dpr = window.devicePixelRatio || 1;
    const W = canvas.clientWidth || 600;
    const H = 130;
    canvas.width = W * dpr;
    canvas.height = H * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    if (t.length < 2) { ctx.fillStyle = resolveCssColor('var(--muted)', '#6b6785'); ctx.fillText('no data', 8, H / 2); return; }
    const target = Math.min(W * 2, t.length);
    const stride = Math.max(1, Math.floor(t.length / target));
    let lo = Infinity, hi = -Infinity;
    for (let i = 0; i < v.length; i += stride) { const x = v[i]; if (x < lo) lo = x; if (x > hi) hi = x; }
    if (lo === hi) { lo -= 1; hi += 1; }
    const t0 = t[0], t1 = t[t.length - 1];
    const xOf = (ts: number) => ((ts - t0) / (t1 - t0 || 1)) * W;
    const yOf = (val: number) => H - ((val - lo) / (hi - lo)) * (H - 6) - 3;
    ctx.strokeStyle = resolveCssColor(color); ctx.lineWidth = 1.4; ctx.beginPath();
    let first = true;
    for (let i = 0; i < t.length; i += stride) {
      const x = xOf(t[i]), y = yOf(v[i]);
      if (first) { ctx.moveTo(x, y); first = false; } else ctx.lineTo(x, y);
    }
    ctx.stroke();
  }, [t, v, color, resizeTick]);
  return <canvas ref={ref} className="wave" style={{ height: 130 }} role="img" aria-label="Saved pulse trace" />;
}
