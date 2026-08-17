import { useAppStore } from '../store/appStore';
import { DisclaimerText } from './Disclaimer';

/**
 * A warm, one-time welcome. It explains the palm reading in plain words.
 * Tapping "Get started" is the acknowledgment — there's no gating checkbox to get
 * stuck on. Camera access is requested later, right when you start a reading.
 */
export function OnboardingGate() {
  const updateSettings = useAppStore((s) => s.updateSettings);

  return (
    <div className="overlay">
      <div className="modal">
        <h2>Welcome to MoniVitals</h2>
        <p className="small" style={{ color: 'var(--muted)', marginTop: 0 }}>
          Take an approximate pulse reading with your palm and your phone’s rear camera and flash.
        </p>

        <div className="card">
          <h3>How a reading works</h3>
          <p className="small" style={{ margin: 0 }}>
            Rest the rear camera and flash flat against the center of your palm. Keep gentle contact
            and hold still for the guided 30-second capture. The camera reads tiny color changes
            with each heartbeat. Everything is processed on your phone.
          </p>
        </div>

        <div className="card">
                    <DisclaimerText />
        </div>

        <div style={{ marginTop: 16 }}>
          <button
            className="btn"
            style={{ width: '100%' }}
            onClick={() => updateSettings({ onboarded: true })}
          >
            Get started
          </button>
          <p className="small" style={{ color: 'var(--muted)', textAlign: 'center', marginTop: 8 }}>
            For wellbeing and curiosity — not a substitute for medical care.
          </p>
        </div>
      </div>
    </div>
  );
}
