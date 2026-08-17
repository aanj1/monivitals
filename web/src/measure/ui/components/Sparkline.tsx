import { useId } from 'react';

interface Props {
  /** Recent values, oldest first (e.g. the store's rolling HR history). */
  values: number[];
  /** Logical drawing size; the SVG scales to its container width. */
  width?: number;
  height?: number;
  /** Stroke color (any CSS color, including var(--…)). */
  color?: string;
  /** Accessible description; the sparkline is decorative-plus (summarized for AT). */
  ariaLabel?: string;
}

/**
 * Tiny inline trend line (SVG) for the dashboard hero vital. Renders a smooth-ish
 * polyline with a soft gradient fill and a dot on the latest value. SVG (not canvas)
 * so it stays crisp at any DPR, needs no rAF loop, and renders under jsdom in tests.
 */
export function Sparkline({ values, width = 240, height = 44, color = 'var(--accent)', ariaLabel }: Props) {
  const gradId = useId();

  if (values.length < 2) {
    // Not enough data yet: a quiet baseline placeholder keeps the layout stable.
    return (
      <svg
        className="sparkline"
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={ariaLabel ?? 'Heart-rate trend — collecting data'}
      >
        <line x1={0} y1={height / 2} x2={width} y2={height / 2} stroke="currentColor" strokeOpacity={0.15} strokeWidth={1.5} strokeDasharray="3 4" />
      </svg>
    );
  }

  let lo = Infinity;
  let hi = -Infinity;
  for (const v of values) {
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
  if (hi - lo < 4) {
    // Keep a minimum vertical span so a steady HR doesn't render as jitter.
    const mid = (hi + lo) / 2;
    lo = mid - 2;
    hi = mid + 2;
  }
  const padY = 4;
  const xOf = (i: number) => (i / (values.length - 1)) * width;
  const yOf = (v: number) => height - padY - ((v - lo) / (hi - lo)) * (height - padY * 2);

  const pts = values.map((v, i) => `${xOf(i).toFixed(1)},${yOf(v).toFixed(1)}`);
  const lastX = xOf(values.length - 1);
  const lastY = yOf(values[values.length - 1]);
  const areaPath = `M ${pts.join(' L ')} L ${width},${height} L 0,${height} Z`;

  return (
    <svg
      className="sparkline"
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="none"
      role="img"
      aria-label={ariaLabel ?? `Heart-rate trend over the last ${values.length} readings`}
      style={{ color }}
    >
      <defs>
        <linearGradient id={gradId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="currentColor" stopOpacity={0.22} />
          <stop offset="100%" stopColor="currentColor" stopOpacity={0} />
        </linearGradient>
      </defs>
      <path d={areaPath} fill={`url(#${gradId})`} stroke="none" />
      <polyline
        points={pts.join(' ')}
        fill="none"
        stroke="currentColor"
        strokeWidth={2}
        strokeLinejoin="round"
        strokeLinecap="round"
        vectorEffect="non-scaling-stroke"
      />
      <circle cx={lastX} cy={lastY} r={3} fill="currentColor" />
    </svg>
  );
}
