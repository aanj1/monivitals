import { useEffect, useRef } from 'react';
import type { WaveBuffer } from '../../live/ringBuffer';
import { resolveCssColor } from '../../util/cssColor';

interface Props {
  buffer: WaveBuffer;
  windowSec: number;
  color: string;
  label: string;
  height?: number;
  /** Fixed y-range; if omitted the trace auto-scales to the visible window. */
  yRange?: [number, number];
  /** Optional event timestamps (µs) drawn as vertical markers (e.g. detected beats). */
  getMarkers?: () => number[];
  markerColor?: string;
  /** Subtle gradient area-fill under the trace (default on). */
  fill?: boolean;
}

/** Parse an rgb()/#hex color to [r,g,b] so we can build translucent gradients. */
function toRgb(color: string): [number, number, number] {
  const c = color.trim();
  const hex = c.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
  if (hex) {
    const h = hex[1];
    const full = h.length === 3 ? h.split('').map((x) => x + x).join('') : h;
    return [parseInt(full.slice(0, 2), 16), parseInt(full.slice(2, 4), 16), parseInt(full.slice(4, 6), 16)];
  }
  const m = c.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i);
  if (m) return [Number(m[1]), Number(m[2]), Number(m[3])];
  return [38, 26, 100]; // fall back to --ecg navy
}

/**
 * Scrolling waveform strip. Reads the ring buffer directly on requestAnimationFrame and
 * draws a downsampled polyline; it never triggers React re-renders per sample (§2.4).
 */
export function WaveformCanvas({
  buffer,
  windowSec,
  color,
  label,
  height = 140,
  yRange,
  getMarkers,
  markerColor = 'rgba(38,26,100,0.25)',
  fill = true,
}: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  // Latest props for the rAF loop to read without re-subscribing every render
  // (the loop runs continuously; re-creating it per render would tear down/rebuild
  // the animation frame ~4×/s and reset it to a stale closure over the props).
  const propsRef = useRef({ windowSec, color, height, yRange, getMarkers, markerColor, fill });
  propsRef.current = { windowSec, color, height, yRange, getMarkers, markerColor, fill };

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    let raf = 0;

    const draw = () => {
      const { windowSec, color, height, yRange, getMarkers, markerColor, fill } = propsRef.current;
      const dpr = window.devicePixelRatio || 1;
      const cssW = canvas.clientWidth || 600;
      const cssH = height;
      if (canvas.width !== Math.round(cssW * dpr) || canvas.height !== Math.round(cssH * dpr)) {
        canvas.width = Math.round(cssW * dpr);
        canvas.height = Math.round(cssH * dpr);
      }
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, cssW, cssH);

      const now = buffer.latestT();
      const windowUs = windowSec * 1_000_000;
      const resolved = resolveCssColor(color);
      const [r, g, b] = toRgb(resolved);

      // Faint vertical time grid (one line per second) for readability.
      const secW = cssW / windowSec;
      if (secW > 12) {
        ctx.strokeStyle = 'rgba(38,26,100,0.045)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        for (let gx = cssW - secW; gx > 0; gx -= secW) {
          ctx.moveTo(Math.round(gx) + 0.5, 0);
          ctx.lineTo(Math.round(gx) + 0.5, cssH);
        }
        ctx.stroke();
      }

      // Midline.
      ctx.strokeStyle = 'rgba(38,26,100,0.08)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(0, Math.round(cssH / 2) + 0.5);
      ctx.lineTo(cssW, Math.round(cssH / 2) + 0.5);
      ctx.stroke();

      if (now == null) {
        raf = requestAnimationFrame(draw);
        return;
      }
      const tFrom = now - windowUs;
      const { t, v } = buffer.window(tFrom);
      if (t.length >= 2) {
        let lo = yRange ? yRange[0] : Infinity;
        let hi = yRange ? yRange[1] : -Infinity;
        if (!yRange) {
          for (const val of v) {
            if (val < lo) lo = val;
            if (val > hi) hi = val;
          }
          if (lo === hi) {
            lo -= 1;
            hi += 1;
          }
          const pad = (hi - lo) * 0.1;
          lo -= pad;
          hi += pad;
        }
        const xOf = (ts: number) => ((ts - tFrom) / windowUs) * cssW;
        const yOf = (val: number) => cssH - ((val - lo) / (hi - lo)) * cssH;

        // Markers behind the trace.
        if (getMarkers) {
          ctx.strokeStyle = resolveCssColor(markerColor);
          ctx.lineWidth = 1;
          for (const m of getMarkers()) {
            if (m < tFrom || m > now) continue;
            const x = xOf(m);
            ctx.beginPath();
            ctx.moveTo(x, 0);
            ctx.lineTo(x, cssH);
            ctx.stroke();
          }
        }

        // Build the polyline once and reuse it for the fill + stroke.
        ctx.beginPath();
        ctx.moveTo(xOf(t[0]), yOf(v[0]));
        for (let i = 1; i < t.length; i++) ctx.lineTo(xOf(t[i]), yOf(v[i]));

        // Subtle gradient area-fill under the trace (drawn behind the stroke).
        if (fill) {
          const xEnd = xOf(t[t.length - 1]);
          const xStart = xOf(t[0]);
          ctx.save();
          ctx.lineTo(xEnd, cssH);
          ctx.lineTo(xStart, cssH);
          ctx.closePath();
          const grad = ctx.createLinearGradient(0, 0, 0, cssH);
          grad.addColorStop(0, `rgba(${r},${g},${b},0.16)`);
          grad.addColorStop(1, `rgba(${r},${g},${b},0)`);
          ctx.fillStyle = grad;
          ctx.fill();
          ctx.restore();
          // Re-trace the line path for the stroke (fill mutated the path).
          ctx.beginPath();
          ctx.moveTo(xOf(t[0]), yOf(v[0]));
          for (let i = 1; i < t.length; i++) ctx.lineTo(xOf(t[i]), yOf(v[i]));
        }

        ctx.strokeStyle = resolved;
        ctx.lineWidth = 1.6;
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        ctx.stroke();

        // Leading-edge glow dot at the live sample.
        const lx = xOf(t[t.length - 1]);
        const ly = yOf(v[v.length - 1]);
        ctx.fillStyle = `rgba(${r},${g},${b},0.18)`;
        ctx.beginPath();
        ctx.arc(lx, ly, 4.5, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = resolved;
        ctx.beginPath();
        ctx.arc(lx, ly, 2, 0, Math.PI * 2);
        ctx.fill();
      }
      raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, [buffer]);

  return (
    <div className="wave-wrap">
      <span className="wave-label">{label}</span>
      <canvas
        ref={canvasRef}
        className="wave"
        style={{ height }}
        role="img"
        aria-label={`${label} — live scrolling waveform, ${windowSec}-second window`}
      />
    </div>
  );
}
