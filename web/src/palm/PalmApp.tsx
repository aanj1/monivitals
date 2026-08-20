'use client';

import dynamic from 'next/dynamic';

const MeasureApp = dynamic(() => import('@/measure/App'), { ssr: false });

export default function PalmApp() {
  return <MeasureApp />;
}
