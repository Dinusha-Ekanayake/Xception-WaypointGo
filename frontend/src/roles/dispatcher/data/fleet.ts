"use client";

import { request } from "@shared/api/client";
import { useResource, type Resource } from "@shared/api/useResource";
import type { VehicleView } from "@shared/domain/types";

// The fleet a depot can use on a date, from GET /api/reference/vehicles. The
// server already leaves out workshop and unavailable vehicles for that date,
// so everything here is available; there is no read yet that lists the rest.

const POLL_MS = 60_000;

export function useFleet(depots: string[], date: string): Resource<VehicleView[]> {
  const key = `${depots.join(",")}|${date}`;
  const load =
    depots.length === 0
      ? null
      : async (signal: AbortSignal) => {
          const perDepot = await Promise.all(
            depots.map((depot) =>
              request<VehicleView[]>(
                `/api/reference/vehicles?depot=${encodeURIComponent(depot)}&date=${encodeURIComponent(date)}`,
                { signal },
              ),
            ),
          );
          return perDepot.flat().sort((a, b) => a.vehicleId.localeCompare(b.vehicleId));
        };
  return useResource(load, key, POLL_MS);
}

export type FleetSummary = {
  total: number;
  vans: number;
  nonVans: number;
  refrigerated: number;
  weeklyQuotaL: number;
};

export function summarise(fleet: VehicleView[]): FleetSummary {
  return {
    total: fleet.length,
    vans: fleet.filter((v) => v.van).length,
    nonVans: fleet.filter((v) => !v.van).length,
    refrigerated: fleet.filter((v) => v.refrigerated).length,
    weeklyQuotaL: fleet.reduce((sum, v) => sum + Number(v.weeklyFuelQuotaL), 0),
  };
}

/** The Figma type label: Refrigerated, Refrig. van, Ambient or Van. */
export function typeLabel(vehicle: VehicleView): string {
  if (vehicle.refrigerated) return vehicle.van ? "Refrig. van" : "Refrigerated";
  return vehicle.van ? "Van" : "Ambient";
}

const kg = new Intl.NumberFormat("en-US", { maximumFractionDigits: 0 });
const one = new Intl.NumberFormat("en-US", { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/** "34.0 m³ · 6,500 kg", display only. */
export function capacityLabel(vehicle: VehicleView): string {
  return `${one.format(Number(vehicle.volumeCapM3))} m³ · ${kg.format(Number(vehicle.weightCapKg))} kg`;
}

export function litres(value: number | string): string {
  return `${kg.format(Number(value))} L`;
}
