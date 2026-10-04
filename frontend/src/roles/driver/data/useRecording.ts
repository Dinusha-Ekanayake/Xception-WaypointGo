import { useCallback, useEffect, useState } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { DepotView } from "@shared/domain/types";
import { num, type LatLon } from "@shared/ui/map/geo";

// The phone-side state recording.ts decides with (R-EXE-23): where the depot is,
// and which trips are already back at it. Both are kept on the
// phone, so a reload with no signal neither forgets the depot nor restarts a
// finished trip's recording.

const DEPOT_KEY = "waypoint.driver.depot.";
const ENDED_KEY = "waypoint.driver.backAtDepot";
const ENDED_KEEP = 20;

function stored<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(key);
    return raw === null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

function store(key: string, value: unknown): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Private mode: the depot is asked for again and a finished trip ends again at the depot.
  }
}

/** The depot's location, from the reference data, or as last read on this phone. */
export function useDepotPoint(depot: string | null): LatLon | null {
  const [kept, setKept] = useState<LatLon | null>(null);
  useEffect(() => setKept(depot ? stored<LatLon | null>(DEPOT_KEY + depot, null) : null), [depot]);
  const read = useResource(
    depot ? (signal: AbortSignal) => request<DepotView>(`/api/reference/depots/${encodeURIComponent(depot)}`, { signal }) : null,
    `driver-depot|${depot ?? ""}`,
    0,
  );
  const lat = num(read.data?.location?.latitude);
  const lon = num(read.data?.location?.longitude);
  useEffect(() => {
    if (depot && lat !== null && lon !== null) {
      store(DEPOT_KEY + depot, { lat, lon });
      setKept({ lat, lon });
    }
  }, [depot, lat, lon]);
  return kept;
}

/** Trips whose recording is over because the vehicle came back within reach of the depot. */
export function useEndedTrips(): [ReadonlySet<string>, (tripId: string) => void] {
  const [ended, setEnded] = useState<ReadonlySet<string>>(() => new Set());
  useEffect(() => setEnded(new Set(stored<string[]>(ENDED_KEY, []))), []);
  const end = useCallback((tripId: string) => {
    setEnded((before) => {
      if (before.has(tripId)) return before;
      const next = [...before, tripId].slice(-ENDED_KEEP);
      store(ENDED_KEY, next);
      return new Set(next);
    });
  }, []);
  return [ended, end];
}

const AT_DEPOT_KEY = (vehicleId: string, date: string) => `waypoint.driver.atDepot.${vehicleId}.${date}`;

/**
 * Whether the driver already said they are at the depot with this vehicle today
 * (R-EXE-24), kept on the phone so a reload neither asks again nor sends twice.
 */
export function useAtDepot(vehicleId: string | null, date: string): [boolean, () => void] {
  const [at, setAt] = useState(false);
  useEffect(() => setAt(vehicleId ? stored<boolean>(AT_DEPOT_KEY(vehicleId, date), false) : false), [vehicleId, date]);
  const mark = useCallback(() => {
    if (!vehicleId) return;
    store(AT_DEPOT_KEY(vehicleId, date), true);
    setAt(true);
  }, [vehicleId, date]);
  return [at, mark];
}
