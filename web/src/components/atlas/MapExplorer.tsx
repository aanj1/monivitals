"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import { formatValue, loadAtlas, loadMetric, MAP_COLORS } from "@/lib/atlas";
import type { Atlas, MapRegion } from "@/lib/atlas";
import { STRATA, STRATUM_LABEL } from "@/lib/meta";
import type { Level, StratumKey, WindowData } from "@/lib/types";
import FlatMap from "./FlatMap";
import styles from "./atlas.module.css";

export default function MapExplorer({ embedded = false }: { embedded?: boolean }) {
  const [atlas, setAtlas] = useState<Atlas | null>(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let alive = true;
    setError("");
    loadAtlas().then((result) => { if (alive) setAtlas(result); })
      .catch(() => { if (alive) setError("We couldn’t load the map. Check your connection and try again."); });
    return () => { alive = false; };
  }, [attempt]);

  return (
    <div className={styles.atlas} style={embedded ? { minHeight: "auto", background: "transparent" } : undefined}>
      {!embedded && (
        <header className={styles.header}>
          <Link href="/" className={styles.brand}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/logo-192.png" width={32} height={32} alt="" />
            <span>MoniVitals</span>
          </Link>
          <nav aria-label="Main navigation">
            <Link href="/">Home</Link>
            <Link href="/map" aria-current="page">Map</Link>
            <Link href="/palm" className={styles.openApp}>Open app <span aria-hidden>↗</span></Link>
          </nav>
        </header>
      )}
      <main className={styles.main} style={embedded ? { padding: 0, maxWidth: "none" } : undefined}>
        {!embedded && (
          <div className={styles.intro}>
            <div><h1>The Nation&rsquo;s Vitals</h1><p>Cardiovascular indicators across the United States, one place at a time.</p></div>
          </div>
        )}
        {!atlas ? <div className={styles.initialState} role={error ? "alert" : "status"}>
          <p>{error || "Loading the US map…"}</p>
          {error && <button onClick={() => setAttempt((n) => n + 1)}>Try again</button>}
        </div> : <Explorer atlas={atlas} embedded={embedded} />}
      </main>
      {!embedded && (
        <footer className={styles.footer}><span>MoniVitals · Explore with perspective.</span><div><Link href="/support">Support</Link><Link href="/privacy">Privacy</Link></div></footer>
      )}
    </div>
  );
}

function Explorer({ atlas, embedded = false }: { atlas: Atlas; embedded?: boolean }) {
  const { manifest, regions } = atlas;
  const [metricId, setMetricId] = useState(manifest.metrics[0].id);
  const metric = manifest.metrics.find((item) => item.id === metricId)!;
  const [periodId, setPeriodId] = useState(metric.windows[metric.windows.length - 1].id);
  const [compareId, setCompareId] = useState(metric.windows[Math.max(0, metric.windows.length - 2)].id);
  const [compare, setCompare] = useState(false);
  const [level, setLevel] = useState<Level>("county");
  const [stratum, setStratum] = useState<StratumKey>("overall");
  const [query, setQuery] = useState("");
  const [selected, setSelected] = useState<MapRegion[]>([]);
  const [focus, setFocus] = useState<MapRegion | null>(null);
  const [loaded, setLoaded] = useState<{ metricId: string; data: Record<string, WindowData> } | null>(null);
  const [dataError, setDataError] = useState("");
  const [retry, setRetry] = useState(0);
  const [selectionNotice, setSelectionNotice] = useState("");
  const all = loaded?.metricId === metric.id ? loaded.data : undefined;
  const activeStratum = metric.hasStrata ? stratum : "overall";
  const period = metric.windows.find((item) => item.id === periodId)!;
  const comparePeriod = metric.windows.find((item) => item.id === compareId)!;
  const data = all?.[period.id];
  const isSample = manifest.source === "sample";

  useEffect(() => {
    let alive = true;
    setDataError("");
    loadMetric(metric).then((result) => { if (alive) setLoaded({ metricId: metric.id, data: result }); })
      .catch(() => { if (alive) setDataError("The values couldn’t be loaded. Please try again."); });
    return () => { alive = false; };
  }, [metric, retry]);

  const results = useMemo(() => {
    const terms = query.trim().toLowerCase().split(/\s+/).filter(Boolean);
    if (!terms.length) return [];
    return [...regions.state, ...regions.county].filter((region) => terms.every((term) => region.searchText.includes(term))).slice(0, 6);
  }, [query, regions]);

  const selectRegion = useCallback((region: MapRegion) => {
    setSelectionNotice("");
    setSelected((previous) => {
      if (previous.some((item) => item.id === region.id)) return previous;
      return [...previous.slice(-2), region];
    });
  }, []);

  function searchSelect(region: MapRegion) {
    selectRegion(region);
    setLevel(region.kind);
    setFocus(region);
    setQuery("");
    setSelectionNotice(`Selected ${region.name}. Region details are below the map.`);
  }

  function changeMetric(id: string) {
    const next = manifest.metrics.find((item) => item.id === id)!;
    setMetricId(id);
    setPeriodId(next.windows[next.windows.length - 1].id);
    setCompareId(next.windows[Math.max(0, next.windows.length - 2)].id);
    setStratum("overall");
    setDataError("");
  }

  return <>
    <div className={styles.explorer} style={embedded ? { border: "none", borderRadius: 0 } : undefined}>
      <aside className={styles.sidebar} aria-label="Map filters">
        <div className={styles.filterBlock}>
          <label htmlFor="region-search">Find a place</label>
          <input id="region-search" type="search" placeholder="County, state, or FIPS code" value={query} autoComplete="off"
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => { if (event.key === "Enter" && results[0]) { event.preventDefault(); searchSelect(results[0]); } if (event.key === "Escape") setQuery(""); }} aria-describedby="search-help" />
          <p id="search-help" className={styles.hint}>Try “Los Angeles” or “Texas”.</p>
          {query.trim() && <div className={styles.searchResults} aria-label="Matching regions">
            {results.length ? results.map((region) => <button key={region.id} onClick={() => searchSelect(region)}><span>{region.name}</span><small>{region.kind === "county" ? "County" : "State"} · {region.id}</small></button>) : <p role="status">No matching places. Try a state or county name.</p>}
          </div>}
          <span className={styles.srOnly} role="status">{selectionNotice}</span>
        </div>
        <div className={styles.filterBlock}>
          <label htmlFor="atlas-metric">Health indicator</label>
          <select id="atlas-metric" value={metric.id} onChange={(event) => changeMetric(event.target.value)}>{manifest.metrics.map((item) => <option value={item.id} key={item.id}>{item.label}</option>)}</select>
          <p className={styles.hint}>{metric.ageNote}</p>
        </div>
        <div className={styles.filterBlock}>
          <span className={styles.fieldLabel}>Geography</span>
          <div className={styles.segmented} role="group" aria-label="Geography">
            {(["county", "state"] as const).map((item) => <button key={item} aria-pressed={level === item} onClick={() => { setLevel(item); setFocus(null); }}>{item === "county" ? "Counties" : "States"}</button>)}
          </div>
        </div>
        <div className={styles.filterBlock}>
          <label htmlFor="atlas-population">Population</label>
          <select id="atlas-population" value={activeStratum} disabled={!metric.hasStrata} onChange={(event) => setStratum(event.target.value as StratumKey)}>{STRATA.map((item) => <option key={item.key} value={item.key}>{item.label}</option>)}</select>
          {!metric.hasStrata && <p className={styles.hint}>This indicator is available for everyone combined.</p>}
        </div>
        <div className={styles.filterBlock}>
          <label htmlFor="atlas-period">Time period</label>
          <select id="atlas-period" value={periodId} onChange={(event) => setPeriodId(event.target.value)}>{metric.windows.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select>
          <label className={styles.checkLabel}><input type="checkbox" checked={compare} onChange={(event) => setCompare(event.target.checked)} />Compare two periods</label>
          {compare && <><label htmlFor="atlas-compare">Compare with</label><select id="atlas-compare" value={compareId} onChange={(event) => setCompareId(event.target.value)}>{metric.windows.map((item) => <option key={item.id} value={item.id}>{item.label}</option>)}</select></>}
        </div>
        <div className={styles.sidebarNote}><span aria-hidden>↖</span><p>Choose up to 3 places on the map to explore their values over time. A fourth replaces the oldest.</p></div>
      </aside>
      <div className={styles.mapColumn}>
        <div className={styles.mapHeading}><div><h2>{metric.short}</h2><p>{STRATUM_LABEL[activeStratum]} · {metric.unit}{isSample ? " · Sample values" : ""}</p></div><span className={styles.periodPill}>{period.label}</span></div>
        {dataError ? <div className={styles.dataError} role="alert"><p>{dataError}</p><button onClick={() => { setDataError(""); setRetry((n) => n + 1); }}>Retry loading values</button></div> : <div className={compare ? styles.comparison : styles.singleMap}>
          <FlatMap regions={regions[level]} states={regions.state} data={data} metric={metric} stratum={activeStratum} selected={selected} focus={focus} onSelect={selectRegion} label={period.label} />
          {compare && <FlatMap regions={regions[level]} states={regions.state} data={all?.[compareId]} metric={metric} stratum={activeStratum} selected={selected} focus={focus} onSelect={selectRegion} label={comparePeriod.label} />}
        </div>}
        <div className={styles.mapBottom}>
          <div className={styles.legend} aria-label={`Color scale: ${metric.domain[0]} to ${metric.domain[1]} ${metric.unit}`}>
            <div className={styles.colorScale}>{MAP_COLORS.map((color) => <span key={color} style={{ backgroundColor: color }} />)}</div>
            <div className={styles.legendLabels}><span>≤ {formatValue(metric.domain[0])}</span><span>≥ {formatValue(metric.domain[1])}</span></div>
            <p>{metric.unit} · darker means higher</p>
          </div>
          <span className={styles.noData}><i />No data</span>
          <p className={styles.mapHint}>Drag to pan · Use + / − to zoom<br />Alaska &amp; Hawaii shown as insets. Territories omitted.</p>
        </div>
        <div className={styles.timeline}>
          <label htmlFor="atlas-timeline">Explore over time <strong>{period.label}</strong></label>
          <input id="atlas-timeline" type="range" min={0} max={metric.windows.length - 1} step={1} value={metric.windows.findIndex((item) => item.id === periodId)} aria-valuetext={period.label} onChange={(event) => setPeriodId(metric.windows[Number(event.target.value)].id)} />
          <div><span>{metric.windows[0].label}</span><span>{metric.windows[metric.windows.length - 1].label}</span></div>
        </div>
      </div>
    </div>
    {(!embedded || selected.length > 0) && <section className={styles.details} aria-labelledby="region-details" style={embedded ? { padding: "24px 20px" } : undefined}>
      <div className={styles.detailsHeader}><div><h2 id="region-details">Your selected places</h2></div>{selected.length > 0 && <button onClick={() => { setSelected([]); setFocus(null); }}>Clear selection</button>}</div>
      {selected.length === 0 ? <p className={styles.emptyDetails}>Select a county or state on the map, or find a place using search. Its values and history will appear here.</p> : <>
        <div className={styles.regionCards}>{selected.map((region) => <article key={region.id} className={styles.regionCard}>
          <div><span className={styles.regionKind}>{region.kind} · {region.id}</span><button aria-label={`Remove ${region.name}`} onClick={() => setSelected((items) => items.filter((item) => item.id !== region.id))}>×</button></div>
          <button className={styles.regionName} onClick={() => { setLevel(region.kind); setFocus({ ...region }); }}>{region.name} <span aria-hidden>↗</span></button>
          <p className={styles.regionValue}>{all ? formatValue(data?.[region.kind]?.[region.id]?.[activeStratum]) : dataError ? "Unavailable" : "Loading…"}</p>
          <p className={styles.hint}>{metric.unit} · {period.label}{isSample ? " · Sample" : ""}</p>
          {compare && <p className={styles.compareValue}>{comparePeriod.label}: <strong>{all ? formatValue(all[compareId]?.[region.kind]?.[region.id]?.[activeStratum]) : "No data"}</strong></p>}
        </article>)}</div>
        {all && <div className={styles.tableScroll} tabIndex={0} role="region" aria-label="Region history table"><table><caption>{metric.label} over time · {STRATUM_LABEL[activeStratum]} · {metric.unit}{isSample ? " · Sample values" : ""}</caption><thead><tr><th scope="col">Period</th>{selected.map((region) => <th scope="col" key={region.id}>{region.name}</th>)}</tr></thead><tbody>{metric.windows.map((item) => <tr key={item.id} className={item.id === periodId ? styles.activeRow : undefined}><th scope="row">{item.label}</th>{selected.map((region) => <td key={region.id}>{formatValue(all[item.id]?.[region.kind]?.[region.id]?.[activeStratum])}</td>)}</tr>)}</tbody></table></div>}
      </>}
    </section>}
    <div className={styles.sourceNote}><p><strong>About the data</strong>{isSample ? "Cardiovascular indicators from the original atlas snapshot." : `Source: ${metric.source}. Snapshot: ${manifest.generatedAt.slice(0, 10)}.`}</p><a href={metric.sourceUrl} target="_blank" rel="noreferrer">{isSample ? "Explore the CDC source" : "View source"} ↗</a></div>
  </>;
}
