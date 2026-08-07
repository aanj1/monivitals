import type { StratumKey } from "./types";

// 2-digit state FIPS -> { abbr, name }. Mirror of scripts/lib/meta.mjs.
export const STATES: Record<string, { abbr: string; name: string }> = {
  "01": { abbr: "AL", name: "Alabama" },
  "02": { abbr: "AK", name: "Alaska" },
  "04": { abbr: "AZ", name: "Arizona" },
  "05": { abbr: "AR", name: "Arkansas" },
  "06": { abbr: "CA", name: "California" },
  "08": { abbr: "CO", name: "Colorado" },
  "09": { abbr: "CT", name: "Connecticut" },
  "10": { abbr: "DE", name: "Delaware" },
  "11": { abbr: "DC", name: "District of Columbia" },
  "12": { abbr: "FL", name: "Florida" },
  "13": { abbr: "GA", name: "Georgia" },
  "15": { abbr: "HI", name: "Hawaii" },
  "16": { abbr: "ID", name: "Idaho" },
  "17": { abbr: "IL", name: "Illinois" },
  "18": { abbr: "IN", name: "Indiana" },
  "19": { abbr: "IA", name: "Iowa" },
  "20": { abbr: "KS", name: "Kansas" },
  "21": { abbr: "KY", name: "Kentucky" },
  "22": { abbr: "LA", name: "Louisiana" },
  "23": { abbr: "ME", name: "Maine" },
  "24": { abbr: "MD", name: "Maryland" },
  "25": { abbr: "MA", name: "Massachusetts" },
  "26": { abbr: "MI", name: "Michigan" },
  "27": { abbr: "MN", name: "Minnesota" },
  "28": { abbr: "MS", name: "Mississippi" },
  "29": { abbr: "MO", name: "Missouri" },
  "30": { abbr: "MT", name: "Montana" },
  "31": { abbr: "NE", name: "Nebraska" },
  "32": { abbr: "NV", name: "Nevada" },
  "33": { abbr: "NH", name: "New Hampshire" },
  "34": { abbr: "NJ", name: "New Jersey" },
  "35": { abbr: "NM", name: "New Mexico" },
  "36": { abbr: "NY", name: "New York" },
  "37": { abbr: "NC", name: "North Carolina" },
  "38": { abbr: "ND", name: "North Dakota" },
  "39": { abbr: "OH", name: "Ohio" },
  "40": { abbr: "OK", name: "Oklahoma" },
  "41": { abbr: "OR", name: "Oregon" },
  "42": { abbr: "PA", name: "Pennsylvania" },
  "44": { abbr: "RI", name: "Rhode Island" },
  "45": { abbr: "SC", name: "South Carolina" },
  "46": { abbr: "SD", name: "South Dakota" },
  "47": { abbr: "TN", name: "Tennessee" },
  "48": { abbr: "TX", name: "Texas" },
  "49": { abbr: "UT", name: "Utah" },
  "50": { abbr: "VT", name: "Vermont" },
  "51": { abbr: "VA", name: "Virginia" },
  "53": { abbr: "WA", name: "Washington" },
  "54": { abbr: "WV", name: "West Virginia" },
  "55": { abbr: "WI", name: "Wisconsin" },
  "56": { abbr: "WY", name: "Wyoming" },
  "60": { abbr: "AS", name: "American Samoa" },
  "66": { abbr: "GU", name: "Guam" },
  "69": { abbr: "MP", name: "N. Mariana Is." },
  "72": { abbr: "PR", name: "Puerto Rico" },
  "78": { abbr: "VI", name: "U.S. Virgin Is." },
};

export interface StratumDef {
  key: StratumKey;
  label: string;
  group: "Overall" | "Sex" | "Race / Ethnicity";
}

export const STRATA: StratumDef[] = [
  { key: "overall", label: "Everyone", group: "Overall" },
  { key: "male", label: "Male", group: "Sex" },
  { key: "female", label: "Female", group: "Sex" },
  { key: "white", label: "White (NH)", group: "Race / Ethnicity" },
  { key: "black", label: "Black (NH)", group: "Race / Ethnicity" },
  { key: "hispanic", label: "Hispanic", group: "Race / Ethnicity" },
  { key: "aian", label: "AI / AN", group: "Race / Ethnicity" },
  { key: "aapi", label: "Asian / PI", group: "Race / Ethnicity" },
];

export const STRATUM_LABEL: Record<StratumKey, string> = Object.fromEntries(
  STRATA.map((s) => [s.key, s.label])
) as Record<StratumKey, string>;

export function stateAbbr(stateFips: string): string {
  return STATES[stateFips]?.abbr ?? "";
}
export function stateName(stateFips: string): string {
  return STATES[stateFips]?.name ?? stateFips;
}
