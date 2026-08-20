'use client';

import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { CameraScanner, initialView } from './scan/_lib/camera';
import { cropToFramePercent, pointToFramePercent } from './scan/_lib/hand';
import { CAPTURE_SECONDS, type CameraSample } from './scan/_lib/ppg';
import { captureQuality } from './scan/_lib/quality';
import { db } from '@/measure/model/db';
import type { Session } from '@/measure/model/types';
import './scan/scan.css';

/** MediaPipe Hand Landmarker connection graph — wrist, four fingers, thumb.
 * Drawn as a live skeleton over the detected palm so tracking reads as active. */
const HAND_CONNECTIONS: readonly [number, number][] = [
  [0, 1], [1, 2], [2, 3], [3, 4],
  [0, 5], [5, 6], [6, 7], [7, 8],
  [5, 9], [9, 10], [10, 11], [11, 12],
  [9, 13], [13, 14], [14, 15], [15, 16],
  [13, 17], [17, 18], [18, 19], [19, 20],
  [0, 17],
];

/** A canonical open-hand landmark set (viewBox units) for the idle preview, so
 * the tracking skeleton is visible before the camera opens. */
const DEMO_PALM: readonly [number, number][] = [
  [50, 90], [37, 81], [29, 71], [23, 62], [19, 54],
  [43, 55], [41, 43], [40, 34], [39, 26],
  [50, 53], [50, 40], [50, 30], [50, 21],
  [58, 55], [60, 43], [61, 34], [62, 27],
  [65, 59], [69, 49], [71, 42], [73, 36],
];

export function PalmScan({ onReview }: { onReview: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const scanner = useRef<CameraScanner | null>(null);
  const [view, setView] = useState(initialView);
  const [camera, setCamera] = useState('user');
  const [palmConfirmed, setPalmConfirmed] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');
  const saveLock = useRef(false);
  const result = useRef<HTMLElement>(null);
  const previousPhase = useRef(view.phase);

  useEffect(() => {
    if (!video.current) return;
    const engine = new CameraScanner(video.current, setView);
    scanner.current = engine;
    return () => { engine.dispose(); scanner.current = null; };
  }, []);

  useEffect(() => {
    if (view.phase === 'complete' && previousPhase.current !== 'complete') result.current?.focus({ preventScroll: true });
    previousPhase.current = view.phase;
  }, [view.phase]);

  const active = view.phase === 'preview' || view.phase === 'capturing';
  const busy = view.phase === 'requesting' || view.phase === 'capturing';
  const complete = view.phase === 'complete';
  const estimate = complete ? view.analysis?.bpm ?? null : null;
  const seconds = Math.min(CAPTURE_SECONDS, Math.floor(view.seconds));
  const trace = view.analysis?.trace ?? [];
  const tracePath = trace.map((v, i) => `${i ? 'L' : 'M'}${((i / Math.max(1, trace.length - 1)) * 700).toFixed(1)},${(60 - v * 17).toFixed(1)}`).join(' ');
  const canCapture = view.phase === 'preview' && palmConfirmed && view.fps >= 18 && view.lighting === 'Good' && view.movement === 'Steady';
  const palmLocked = active && view.tracking === 'on' && view.palm != null;
  const frame = view.crop && view.videoSize ? cropToFramePercent(view.crop, view.videoSize.width, view.videoSize.height, view.mirrored) : null;
  const cropStyle: CSSProperties | undefined = active && frame
    ? { left: `${frame.left}%`, top: `${frame.top}%`, width: `${frame.width}%`, height: `${frame.height}%`, transform: 'none' }
    : undefined;
  const toMesh = (point: { x: number; y: number }) => view.videoSize ? pointToFramePercent(point, view.videoSize.width, view.videoSize.height, view.mirrored) : point;

  function startCamera() {
    setPalmConfirmed(false);
    setSaved(false);
    setSaveError('');
    void scanner.current?.open(camera);
  }

  async function save() {
    const capture = scanner.current?.exportCapture();
    const analysis = view.analysis;
    if (!capture || !analysis || analysis.bpm === null || saveLock.current || saved) return;
    const samples = capture.samples as CameraSample[];
    if (samples.length < 2) return;

    saveLock.current = true;
    setSaving(true);
    setSaveError('');

    const endedAt = capture.capturedAt ?? new Date().toISOString();
    const session: Session = {
      id: crypto.randomUUID(),
      startedAt: new Date(new Date(endedAt).getTime() - analysis.seconds * 1000).toISOString(),
      endedAt,
      placement: 'palm',
      notes: '',
      summary: { hrBpm: Math.round(analysis.bpm), signalQuality: captureQuality(analysis, samples) },
    };

    const t0 = samples[0].time;
    const timestamps = Float64Array.from(samples, (sample) => Math.round((sample.time - t0) * 1_000_000));
    // Inverted green: more blood absorbs more green, so a pulse reads upward —
    // the same convention the fingertip path stores.
    const values = Float64Array.from(samples, (sample) => 255 - sample.g);
    const span = samples[samples.length - 1].time - t0;

    try {
      await db.transaction('rw', db.sessions, db.chunks, db.metrics, async () => {
        await db.sessions.add(session);
        await db.chunks.add({
          sessionId: session.id,
          tStartUs: 0,
          sampleRateHz: span > 0 ? (samples.length - 1) / span : 0,
          t: timestamps,
          pulse: values,
        });
        await db.metrics.add({
          sessionId: session.id,
          tUs: timestamps[timestamps.length - 1],
          hrBpm: session.summary.hrBpm,
          signalQuality: session.summary.signalQuality,
        });
      });
      setSaved(true);
    } catch {
      setSaveError('This browser couldn’t save your reading. Free some storage or allow site storage, then try again.');
    } finally {
      saveLock.current = false;
      setSaving(false);
    }
  }

  return <div className="scan-page">
    <div className="scan-intro">
      <h1>{complete ? estimate != null ? 'Your pulse estimate' : 'Let’s try that again' : view.phase === 'capturing' ? 'Keep your palm still' : 'A pulse reading, from your palm'}</h1>
      <p>Open palm facing the camera · 30 seconds of ordinary video · Processed on your device</p>
    </div>

    <div className={`scan-workspace ${active ? 'scan-active' : ''}`}>
      <div className="scan-feed-column">
        <div className={`scan-camera-frame ${active ? 'is-live' : ''}`} data-phase={view.phase}>
          <video ref={video} autoPlay muted playsInline className={view.mirrored ? 'is-mirrored' : ''} aria-label="Live camera preview of your palm" />
          {!active && <div className="scan-camera-empty">
            <svg className="scan-palm-mesh scan-demo-mesh" viewBox="0 0 100 100" preserveAspectRatio="xMidYMid meet" aria-hidden="true">
              <polygon points={[0, 5, 9, 13, 17].map((i) => DEMO_PALM[i].join(',')).join(' ')} />
              {HAND_CONNECTIONS.map(([a, b], i) => <line key={i} className="scan-bone" x1={DEMO_PALM[a][0]} y1={DEMO_PALM[a][1]} x2={DEMO_PALM[b][0]} y2={DEMO_PALM[b][1]} style={{ animationDelay: `${i * 0.05}s` }} />)}
              {DEMO_PALM.map(([x, y], i) => <circle key={i} className="scan-node" cx={x} cy={y} r=".8" style={{ animationDelay: `${(i % 7) * 0.11}s` }} />)}
            </svg>
            <span className="scan-mono">{complete ? 'CAPTURE COMPLETE / CAMERA OFF' : view.phase === 'requesting' ? 'WAITING FOR CAMERA PERMISSION' : 'YOUR PALM GOES HERE'}</span>
          </div>}
          <div className="scan-feed-top">
            <span className={`scan-feed-state ${active ? 'on' : ''}`}><i />{view.phase === 'capturing' ? 'CAPTURING' : active ? 'LIVE PREVIEW' : 'CAMERA OFF'}</span>
            <span>LOCAL PROCESSING</span>
          </div>
          {palmLocked && view.palm && <svg className="scan-palm-mesh" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true">
            {(() => { const a = [0, 5, 9, 13, 17].map((i) => toMesh(view.palm!.landmarks[i])); const cx = a.reduce((s, p) => s + p.x, 0) / a.length; const cy = a.reduce((s, p) => s + p.y, 0) / a.length; return <circle key="lock" className="scan-lock-ring" cx={cx.toFixed(2)} cy={cy.toFixed(2)} r="2" />; })()}
            <polygon points={[0, 5, 9, 13, 17].map((i) => { const p = toMesh(view.palm!.landmarks[i]); return `${p.x.toFixed(2)},${p.y.toFixed(2)}`; }).join(' ')} />
            {HAND_CONNECTIONS.map(([a, b], i) => { const la = view.palm!.landmarks[a], lb = view.palm!.landmarks[b]; if (!la || !lb) return null; const p = toMesh(la), q = toMesh(lb); return <line key={i} className="scan-bone" x1={p.x.toFixed(2)} y1={p.y.toFixed(2)} x2={q.x.toFixed(2)} y2={q.y.toFixed(2)} />; })}
            {view.palm.landmarks.map((point, i) => { const p = toMesh(point); return <circle key={i} className="scan-node" cx={p.x.toFixed(2)} cy={p.y.toFixed(2)} r=".8" style={{ animationDelay: `${(i % 7) * 0.11}s` }} />; })}
          </svg>}
          <div className="scan-crop" data-lock={palmLocked ? '' : undefined} style={cropStyle} aria-hidden="true"><i /><i /><i /><i />
            {(palmLocked || view.phase === 'capturing') && <em className="scan-sweep" />}
            {active && <span>{palmLocked ? 'PALM LOCKED' : 'PALM CROP'}</span>}
          </div>
          {active && <p className="scan-feed-hint">{view.tracking === 'on' ? 'The square follows your palm on its own — fill it with your bare palm' : 'Keep only your palm inside the square'}</p>}
        </div>

        <div className="scan-wave-panel">
          <div className="scan-wave-heading"><span className="scan-mono">{complete ? 'CAPTURED SIGNAL' : 'LIVE COLOR SIGNAL'}</span><span className="scan-mono">{trace.length ? 'NORMALIZED / LAST 8 S' : 'AWAITING CAPTURE'}</span></div>
          <svg viewBox="0 0 700 120" preserveAspectRatio="none" role="img" aria-label={trace.length ? 'Filtered color changes extracted from camera frames. This trace alone does not confirm a pulse.' : 'No captured signal yet'}>
            <path className="scan-grid" d="M0 30H700M0 60H700M0 90H700M100 0V120M200 0V120M300 0V120M400 0V120M500 0V120M600 0V120" />
            {tracePath && <path className="scan-live-trace" d={tracePath} />}
          </svg>
          <p>{trace.length ? 'From your camera, with a short processing delay. Movement and light can also create waves.' : 'The trace appears only after camera frames have been captured.'}</p>
        </div>
      </div>

      <section className="card scan-control-panel" aria-labelledby="scan-control-title" tabIndex={-1} ref={result}>
        <h2 id="scan-control-title" className="scan-control-title">{complete ? 'Your capture' : active ? 'Find the signal' : 'Set up your scan'}</h2>

        {complete ? <div className="scan-result" data-result={estimate != null ? 'estimate' : 'insufficient'}>
          <span className="scan-mono">{estimate != null ? 'ESTIMATED PULSE RATE' : 'NO ESTIMATE AVAILABLE'}</span>
          <div className="scan-result-number">{estimate != null ? <>{estimate}<span>BPM</span></> : <span className="scan-no-reading">Insufficient signal</span>}</div>
          <p>Experimental estimation. Not clinically validated. Do not use this result for medical decisions.</p>
        </div> : <ol className="scan-instructions">
          <li><span>01</span>Use bright, steady daylight. Avoid glare and changing light.</li>
          <li><span>02</span>Rest your wrist. Fill the square with the center of your bare palm.</li>
          <li><span>03</span>Keep your hand and camera still. Leave this tab open during capture.</li>
        </ol>}

        <div className="scan-status" role="status" aria-live="polite" aria-atomic="true"><i className={view.phase === 'error' || view.analysis?.issue === 'weak' ? 'attention' : ''} /><p>{view.message}</p></div>

        {!complete && <>
          <label className="scan-camera-label" htmlFor="scan-camera-select">CAMERA</label>
          <select id="scan-camera-select" value={camera} disabled={busy} onChange={(event) => {
            const next = event.target.value;
            setCamera(next);
            setPalmConfirmed(false);
            if (active) void scanner.current?.open(next);
          }}>
            <option value="user">Front / default camera</option>
            <option value="environment">Back camera</option>
            {view.cameras.map((device) => <option key={device.id} value={device.id}>{device.label}</option>)}
          </select>
        </>}

        {view.phase === 'preview' && <label className="scan-confirm"><input type="checkbox" checked={palmConfirmed} onChange={(event) => setPalmConfirmed(event.target.checked)} /><span>My bare palm fills the outlined square.</span></label>}

        <div className="scan-actions">
          {view.phase === 'preview' ? <button className="btn" disabled={!canCapture} onClick={() => scanner.current?.begin()}>Capture 30 seconds</button>
            : view.phase === 'capturing' ? <div className="scan-capture-progress"><div><span>CAPTURING</span><span>{String(seconds).padStart(2, '0')} / {CAPTURE_SECONDS} S</span></div><progress value={view.seconds} max={CAPTURE_SECONDS} aria-label="Capture progress" /></div>
            : view.phase === 'requesting' ? <button className="btn" disabled>Waiting for camera…</button>
            : <button className="btn" onClick={startCamera}>{complete ? 'Try another scan' : view.phase === 'error' ? 'Try camera again' : 'Enable camera'}</button>}

          {(active || view.phase === 'requesting') && <button className="btn secondary" onClick={() => scanner.current?.stop()}>{view.phase === 'capturing' ? 'Cancel capture' : 'Cancel and turn camera off'}</button>}

          {complete && estimate != null && <button className="btn" disabled={saving || saved} onClick={() => void save()}>{saving ? 'Saving…' : saved ? 'Saved to Review' : 'Save reading'}</button>}
          {saved && <button className="btn secondary" onClick={onReview}>View readings</button>}
        </div>

        {saveError && <p className="small" role="alert">{saveError}</p>}
        {saved && <p className="small" role="status">Saved on this device. Find it under Palm scan in Review.</p>}

        <div className="scan-quality" aria-label="Capture conditions">
          <div><span>LIGHT</span><strong>{view.lighting}</strong></div>
          <div><span>MOVEMENT</span><strong>{view.movement}</strong></div>
          <div><span>CAMERA</span><strong>{view.fps > 0 ? `${Math.round(view.fps)} fps` : 'Waiting'}</strong></div>
          <div><span>TRACKING</span><strong>{view.tracking === 'on' ? 'On device' : view.tracking === 'loading' ? 'Loading' : 'Off'}</strong></div>
        </div>

        <p className="scan-privacy">Your video stays on this device. Camera off after capture. Only the pulse trace and estimate are kept when you tap Save reading.</p>
        <p className="small muted">For general wellness only. Not a medical device.</p>
      </section>
    </div>
    <noscript><p className="scan-status">JavaScript is required to access the camera and process frames in your browser.</p></noscript>
  </div>;
}
