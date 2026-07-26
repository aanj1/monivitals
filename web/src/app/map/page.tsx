import type { Metadata } from "next";
import MapExplorer from "@/components/atlas/MapExplorer";

export const metadata: Metadata = {
  title: "Cardiovascular health map, MoniVitals",
  description: "Explore a 2D atlas of US cardiovascular health indicators by county, state, population, and year.",
};

export default function MapPage() {
  return <MapExplorer />;
}
