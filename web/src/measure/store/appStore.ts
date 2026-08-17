/** Shared appearance, onboarding, and saved-reading export for the palm app. */
import { create } from 'zustand';
import { exportSession } from '../model/csv';
import type { AppSettings } from '../model/types';
import { downloadBlob } from '../util/download';

const SETTINGS_KEY = 'monivitals.settings';

function loadSettings(): AppSettings {
  const defaults: AppSettings = { theme: 'light', onboarded: false };
  try {
    const raw = localStorage.getItem(SETTINGS_KEY);
    if (!raw) return defaults;
    const parsed = JSON.parse(raw) as Partial<AppSettings> | null;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return defaults;
    return {
      theme: parsed.theme === 'light' || parsed.theme === 'dark' || parsed.theme === 'system' ? parsed.theme : defaults.theme,
      onboarded: parsed.onboarded === true,
    };
  } catch { return defaults; }
}

interface AppState {
  settings: AppSettings;
  updateSettings: (partial: Partial<AppSettings>) => void;
  exportSessionZip: (sessionId: string) => Promise<void>;
}

export const useAppStore = create<AppState>((set, get) => ({
  settings: loadSettings(),
  updateSettings: (partial) => {
    const settings = { ...get().settings, ...partial };
    try { localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings)); }
    catch { /* Keep in-memory settings when browser storage is unavailable. */ }
    set({ settings });
  },
  exportSessionZip: async (sessionId) => {
    const { blob, filename } = await exportSession(sessionId);
    downloadBlob(filename, blob);
  },
}));
