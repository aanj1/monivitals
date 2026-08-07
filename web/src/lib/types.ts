export type Level = "county" | "state";
export type StratumKey =
  | "overall"
  | "male"
  | "female"
  | "white"
  | "black"
  | "hispanic"
  | "aian"
  | "aapi";

export interface WindowMeta {
  id: string; // ASCII slug, used in the data file path
  label: string; // display label (may contain en-dash)
  year: number; // representative year (used on the trend x-axis + slider)
}

export interface MetricMeta {
  id: string;
  label: string;
  short: string;
  unit: string;
  ageNote: string;
  valueType: string;
  higherIsWorse: boolean;
  source: string;
  sourceUrl: string;
  hasStrata: boolean;
  domain: [number, number];
  windows: WindowMeta[];
}

export interface Manifest {
  generatedAt: string;
  source: "cdc" | "sample";
  note?: string;
  metrics: MetricMeta[];
}

// One data file: level -> regionId -> stratum -> value
export type WindowData = Record<Level, Record<string, Partial<Record<StratumKey, number>>>>;

export interface Region {
  kind: Level;
  id: string; // FIPS (5 for county, 2 for state)
  name: string; // e.g. "Autauga, AL" or "Alabama"
}
