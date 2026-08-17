import { type CSSProperties } from 'react';
import { useAppStore } from '../store/appStore';
import { db } from '../model/db';
import { DisclaimerText } from './Disclaimer';

/** Small tinted icon that leads each settings section header. */
function SectionIcon({ icon, tint }: { icon: string; tint: string }) {
  return (
    <span className="section-icon" aria-hidden="true" style={{ '--tint': tint } as CSSProperties}>
      {icon}
    </span>
  );
}

export function SettingsScreen() {
  const settings = useAppStore((s) => s.settings);
  const update = useAppStore((s) => s.updateSettings);

  return (
    <>
      <div className="card">
        <h2><SectionIcon icon="🎨" tint="var(--navy)" />Appearance</h2>
        <label className="field" style={{ maxWidth: 320 }}>
          Theme
          <select value={settings.theme} onChange={(e) => update({ theme: e.target.value as typeof settings.theme })}>
            <option value="light">Light</option>
            <option value="dark">Dark</option>
            <option value="system">Match my device</option>
          </select>
        </label>
      </div>

      <div className="card">
        <h2><SectionIcon icon="🗄️" tint="var(--warn)" />Your data</h2>
        <p className="small muted">
          Your readings stay on this device, in your browser. Nothing is sent anywhere. Deleting is
          permanent.
        </p>
        <div className="row">
          <button className="btn danger" onClick={async () => {
            if (!confirm('Delete all your saved readings?')) return;
            await Promise.all([db.chunks.clear(), db.metrics.clear(), db.sessions.clear()]);
            alert('All readings deleted.');
          }}>Delete all readings</button>
        </div>
      </div>

      <div className="card">
        <h2><SectionIcon icon="ℹ️" tint="var(--bioz)" />About</h2>
        <p className="small muted">
          MoniVitals reads your pulse from tiny color changes in your palm, using your phone’s rear
          camera. Hold still for the guided 30-second capture. Everything runs on your phone.
        </p>
        <table>
          <tbody>
            <tr><th>Reading</th><td>Palm scan · 30 seconds</td></tr>
            <tr><th>Where it runs</th><td>On your phone — nothing leaves it</td></tr>
          </tbody>
        </table>
        <div style={{ marginTop: 10 }}>
          <DisclaimerText />
        </div>
      </div>
    </>
  );
}
