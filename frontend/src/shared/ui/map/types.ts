import type { ReactNode } from "react";
import type { LatLon } from "./geo.ts";

/** The five states the Figma legend names (On time · At risk · Late · Returning · Offline). */
export type MapStatus = "on-time" | "at-risk" | "late" | "returning" | "offline";

export type MapMarker = LatLon & {
  id: string;
  /** `start`: where the chosen vehicle's trip began, the first point of its run path. */
  kind: "vehicle" | "depot" | "stop" | "store" | "cluster" | "start";
  label: string;
  /** Read by screen readers and shown as the tooltip; the map is never the only way to a vehicle. */
  ariaLabel: string;
  status?: MapStatus;
  heading?: number | null;
  /** A stop's number, "01". */
  badge?: string;
  done?: boolean;
  /** Low accuracy or last-seen: drawn faded. A depot outside the depot filter: drawn grey. */
  faded?: boolean;
  sub?: string;
  count?: number;
  selectable?: boolean;
};

export type MapLine = { id: string; points: LatLon[]; style: "driven" | "planned"; faded?: boolean };

export type LiveMapProps = {
  markers: MapMarker[];
  lines?: MapLine[];
  selectedId?: string | null;
  onSelect?: (id: string | null) => void;
  onHover?: (id: string | null) => void;
  /** Points the view fits once; refitted only when the set changes. */
  fit?: LatLon[];
  /** Turns a group of nearby vehicles into one cluster marker. */
  clusterVehicles?: (group: MapMarker[]) => MapMarker;
  overlay?: ReactNode;
  legend?: ReactNode;
  className?: string;
  /** Shown where no tile is drawn. */
  background?: string;
};
