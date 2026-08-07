"use client";

import { memo, useEffect, useMemo, useRef, useState } from "react";
import type { PointerEvent } from "react";
import { colorFor, formatValue, MAP_HEIGHT, MAP_WIDTH } from "@/lib/atlas";
import type { MapRegion } from "@/lib/atlas";
import type { MetricMeta, StratumKey, WindowData } from "@/lib/types";
import styles from "./atlas.module.css";

const INITIAL = { x: 0, y: 0, width: MAP_WIDTH, height: MAP_HEIGHT };
type View = typeof INITIAL;

function bounded(view: View): View {
  return { ...view, x: Math.max(-80, Math.min(MAP_WIDTH + 80 - view.width, view.x)), y: Math.max(-80, Math.min(MAP_HEIGHT + 80 - view.height, view.y)) };
}

export default memo(function FlatMap({ regions, states, data, metric, stratum, selected, focus, onSelect, label }: {
  regions: MapRegion[];
  states: MapRegion[];
  data: WindowData | undefined;
  metric: MetricMeta;
  stratum: StratumKey;
  selected: MapRegion[];
  focus: MapRegion | null;
  onSelect: (region: MapRegion) => void;
  label: string;
}) {
  const [view, setView] = useState(INITIAL);
  const [hovered, setHovered] = useState<MapRegion | null>(null);
  const drag = useRef<{ x: number; y: number; view: View; moved: boolean; id: number } | null>(null);
  const svg = useRef<SVGSVGElement>(null);

  useEffect(() => {
    if (!focus) { setView(INITIAL); return; }
    const [[x0, y0], [x1, y1]] = focus.bounds;
    const width = Math.min(MAP_WIDTH, Math.max(130, (x1 - x0) * 1.5, (y1 - y0) * 1.5 * MAP_WIDTH / MAP_HEIGHT));
    const height = width * MAP_HEIGHT / MAP_WIDTH;
    setView(bounded({ x: (x0 + x1 - width) / 2, y: (y0 + y1 - height) / 2, width, height }));
    setHovered(null);
  }, [focus]);

  useEffect(() => { setHovered(null); }, [regions]);

  function zoom(factor: number) {
    setView((previous) => {
      const width = Math.max(90, Math.min(MAP_WIDTH, previous.width * factor));
      const height = width * MAP_HEIGHT / MAP_WIDTH;
      return bounded({ x: previous.x + (previous.width - width) / 2, y: previous.y + (previous.height - height) / 2, width, height });
    });
  }

  function pointerMove(event: PointerEvent<SVGSVGElement>) {
    if (event.buttons === 0) { drag.current = null; return; }
    const start = drag.current;
    if (!start || start.id !== event.pointerId || !svg.current) return;
    const dx = event.clientX - start.x;
    const dy = event.clientY - start.y;
    if (Math.hypot(dx, dy) < 5 && !start.moved) return;
    start.moved = true;
    // Capture only after dragging: a tap must still reach the county path.
    if (!svg.current.hasPointerCapture(event.pointerId)) svg.current.setPointerCapture(event.pointerId);
    const ratio = start.view.width / svg.current.getBoundingClientRect().width;
    setView(bounded({ ...start.view, x: start.view.x - dx * ratio, y: start.view.y - dy * ratio }));
    setHovered(null);
  }

  // Panning and hover updates must not reconcile thousands of county paths.
  const shapes = useMemo(() => <>
    {regions.map((region) => {
      const regionValue = data?.[region.kind]?.[region.id]?.[stratum];
      return <path key={region.id} d={region.path} data-region={region.id}
        className={styles.region} fill={colorFor(regionValue, metric.domain)} stroke="#111723" strokeWidth={region.kind === "county" ? 0.35 : 0.8} vectorEffect="non-scaling-stroke"
        onPointerEnter={() => { if (!drag.current?.moved) setHovered(region); }}
        onPointerMove={() => { if (!drag.current?.moved) setHovered(region); }}
        onClick={() => { if (!drag.current?.moved) onSelect(region); drag.current = null; }}>
        <title>{region.name}: {formatValue(regionValue)}{regionValue !== undefined ? ` ${metric.unit}` : ""}</title>
      </path>;
    })}
    {regions[0]?.kind === "county" && states.map((state) => <path key={state.id} d={state.path} fill="none" stroke="#101724" strokeWidth={0.9} vectorEffect="non-scaling-stroke" pointerEvents="none" />)}
    {selected.filter((region) => region.kind === regions[0]?.kind).map((region) => <path key={region.id} d={region.path} fill="none" stroke="#0f1222" strokeWidth={2.3} vectorEffect="non-scaling-stroke" pointerEvents="none" />)}
  </>, [regions, states, data, metric, stratum, selected, onSelect]);

  const value = hovered ? data?.[hovered.kind]?.[hovered.id]?.[stratum] : undefined;
  return (
    <section className={styles.mapPanel} aria-label={`${label} map`}>
      <div className={styles.mapPanelHeader}><span>{label}</span><span className={styles.mapMode}>2D · {regions[0]?.kind === "county" ? "Counties" : "States"}</span></div>
      <div className={styles.mapSurface}>
        <svg ref={svg} className={styles.mapSvg} viewBox={`${view.x} ${view.y} ${view.width} ${view.height}`}
          role="group" aria-label={`${metric.label}, ${label}. Use search to select a region by keyboard.`}
          tabIndex={0}
          onKeyDown={(event) => {
            const step = view.width * 0.08;
            if (event.key === "+" || event.key === "=") zoom(0.7);
            else if (event.key === "-") zoom(1 / 0.7);
            else if (event.key === "Home") setView(INITIAL);
            else if (["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) {
              setView((v) => bounded({ ...v, x: v.x + (event.key === "ArrowRight" ? step : event.key === "ArrowLeft" ? -step : 0), y: v.y + (event.key === "ArrowDown" ? step : event.key === "ArrowUp" ? -step : 0) }));
            } else return;
            event.preventDefault();
          }}
          onPointerDown={(event) => {
            if (!event.isPrimary || event.button !== 0) return;
            drag.current = { x: event.clientX, y: event.clientY, view, moved: false, id: event.pointerId };
          }}
          onPointerMove={pointerMove}
          onPointerUp={(event) => {
            if (svg.current?.hasPointerCapture(event.pointerId)) svg.current.releasePointerCapture(event.pointerId);
            // Keep moved until click dispatch, which follows pointerup.
            requestAnimationFrame(() => { drag.current = null; });
          }}
          onPointerCancel={() => { drag.current = null; }}
          onPointerLeave={() => setHovered(null)}>
          <title>{metric.label}, {label}</title>
          {shapes}
        </svg>
        <div className={styles.mapTools} aria-label={`${label} map controls`}>
          <button type="button" onClick={() => zoom(0.7)} aria-label={`Zoom in ${label}`} disabled={view.width <= 90}>+</button>
          <button type="button" onClick={() => zoom(1 / 0.7)} aria-label={`Zoom out ${label}`} disabled={view.width >= MAP_WIDTH}>−</button>
          <button type="button" onClick={() => { setView(INITIAL); setHovered(null); }} aria-label={`Reset ${label} map view`} title="Reset view">⌂</button>
        </div>
        {hovered && <div className={styles.tooltip} role="status"><strong>{hovered.name}</strong><span>{formatValue(value)} {value != null ? metric.unit : ""}</span></div>}
        {!data && <div className={styles.mapLoading} role="status">Loading values…</div>}
      </div>
    </section>
  );
});
