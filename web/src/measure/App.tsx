'use client';
import './index.css';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useAppStore } from './store/appStore';
import { OnboardingGate } from './ui/OnboardingGate';
import { ReviewScreen } from './ui/Review';
import { SettingsScreen } from './ui/Settings';
import { PalmScan } from '@/palm/PalmScan';

type Tab = 'palm' | 'clips' | 'settings';
const TABS: { id: Tab; label: string }[] = [
  { id: 'palm', label: 'Palm scan' },
  { id: 'clips', label: 'Review' },
  { id: 'settings', label: 'Settings' },
];

/** Applies the theme setting to <html> so the CSS token overrides take effect. */
function useApplyTheme() {
  const theme = useAppStore((s) => s.settings.theme);
  useEffect(() => {
    const root = document.documentElement;
    const mq = window.matchMedia('(prefers-color-scheme: dark)');
    const apply = () => {
      root.dataset.theme = theme === 'system' ? (mq.matches ? 'dark' : 'light') : theme;
    };
    apply();
    if (theme === 'system') {
      mq.addEventListener('change', apply);
    }
    return () => {
      mq.removeEventListener('change', apply);
      delete root.dataset.theme;
    };
  }, [theme]);
}

export default function App() {
  const onboarded = useAppStore((s) => s.settings.onboarded);
  const [tab, setTab] = useState<Tab>('palm');
  useApplyTheme();

  return (
    <div className="app">
      <TopBar palm={tab === 'palm'} />
      <nav className="tabs" aria-label="Sections">
        {TABS.map((t) => (
          <button key={t.id} aria-current={tab === t.id} onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
      </nav>
      <main className="content">
        {tab === 'palm' && <PalmScan onReview={() => setTab('clips')} />}
        {tab === 'clips' && <ReviewScreen />}
        {tab === 'settings' && <SettingsScreen />}
      </main>
      {!onboarded && <OnboardingGate />}
    </div>
  );
}

function TopBar({ palm }: { palm: boolean }) {
  return (
    <header className="topbar">
      <Link href="/" className="brand-lockup" aria-label="MoniVitals home">
        <img src="/logo.png" alt="MoniVitals" className="brand-logo" />
        <span className="brand">MoniVitals</span>
      </Link>
      <span className="disclaimer-chip">Not a medical device</span>
      <span className="spacer" />
      <span className="status">
        <span className="badge">{palm ? 'Palm scan' : 'Ready'}</span>
      </span>
    </header>
  );
}
