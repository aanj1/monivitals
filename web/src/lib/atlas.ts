import { geoAlbersUsa, geoPath } from "d3-geo";
import { feature } from "topojson-client";
import type { GeometryCollection, Topology } from "topojson-specification";
import { STATES } from "./meta";
import type { Level, Manifest, MetricMeta, Region, WindowData } from "./types";

export const MAP_WIDTH = 1000;
export const MAP_HEIGHT = 640;
export const MAP_COLORS = ["#fce7ef", "#f9b6cd", "#ee749d", "#d53c73", "#a71d50", "#681334"];

export interface MapRegion extends Region {
  path: string;
  bounds: [[number, number], [number, number]];
  stateId: string;
  searchText: string;
}

export interface Atlas {
  manifest: Manifest;
  regions: Record<Level, MapRegion[]>;
}

export async function fetchJSON<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error("The map data could not be loaded. Please try again.");
  return response.json() as Promise<T>;
}

/** Project once, then reuse the flat SVG paths for every metric and year. */
export async function loadAtlas(): Promise<Atlas> {
  const [manifest, topology] = await Promise.all([
    fetchJSON<Manifest>("/data/manifest.json"),
    fetchJSON<Topology<{ counties: GeometryCollection<{ name: string }>; states: GeometryCollection<{ name: string }> }>>("/geo/counties-10m.json"),
  ]);
  const states = feature(topology, topology.objects.states);
  const counties = feature(topology, topology.objects.counties);
  const projection = geoAlbersUsa().fitExtent([[25, 25], [975, 605]], states);
  const draw = geoPath(projection);
  const regions: Atlas["regions"] = { county: [], state: [] };

  for (const level of ["county", "state"] as const) {
    for (const item of (level === "state" ? states : counties).features) {
      const id = String(item.id).padStart(level === "state" ? 2 : 5, "0");
      const stateId = id.slice(0, 2);
      const state = STATES[stateId];
      const path = draw(item);
      // Albers USA includes the 50 states and DC, with Alaska and Hawaii insets.
      if (!path || !state || Number(stateId) > 56) continue;
      const name = level === "state" ? state.name : `${item.properties?.name ?? id}, ${state.abbr}`;
      regions[level].push({
        kind: level, id, name, path, stateId, bounds: draw.bounds(item),
        searchText: `${name} ${state.name} ${state.abbr} ${id}`.toLowerCase(),
      });
    }
    regions[level].sort((a, b) => a.name.localeCompare(b.name));
  }
  return { manifest, regions };
}

/** Only successful requests are cached, so Retry can recover from a failed fetch. */
const windows = new Map<string, Promise<WindowData>>();
export function loadWindow(metric: string, period: string): Promise<WindowData> {
  const key = `${metric}/${period}`;
  const existing = windows.get(key);
  if (existing) return existing;
  const request = fetchJSON<WindowData>(`/data/${encodeURIComponent(metric)}/${encodeURIComponent(period)}.json`)
    .catch((error) => { windows.delete(key); throw error; });
  windows.set(key, request);
  return request;
}

export async function loadMetric(metric: MetricMeta): Promise<Record<string, WindowData>> {
  return Object.fromEntries(await Promise.all(metric.windows.map(async (period) =>
    [period.id, await loadWindow(metric.id, period.id)] as const,
  )));
}

export function colorFor(value: number | undefined, domain: [number, number]): string {
  if (value === undefined || !Number.isFinite(value)) return "#293141";
  const fraction = (value - domain[0]) / (domain[1] - domain[0] || 1);
  return MAP_COLORS[Math.max(0, Math.min(MAP_COLORS.length - 1, Math.floor(fraction * MAP_COLORS.length)))];
}

export function formatValue(value: number | undefined): string {
  return value === undefined || !Number.isFinite(value) ? "No data" : value.toLocaleString("en-US", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
}
