import type { Metadata } from 'next';
import PalmApp from '@/palm/PalmApp';

export const metadata: Metadata = {
  title: 'Palm scan, MoniVitals',
  description: 'Take a guided palm-pulse reading using your phone’s rear camera and flash. Processed privately on your device for general wellness.',
};

export default function PalmPage() { return <PalmApp />; }
