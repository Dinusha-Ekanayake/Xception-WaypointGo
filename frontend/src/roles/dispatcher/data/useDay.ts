"use client";

import { request } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";
import { useResource, type Resource } from "@shared/api/useResource";
import type { OrderView, PlanView, ReadyTripView, RunSheetView } from "@shared/domain/types";

// The dispatcher's reads for a depot and a day. Each polls while the tab is
// visible and online, so the screen follows the loaders, the drivers and any
// other dispatcher; useResource keeps the last answer and its time when a poll
// fails, and the screen says how old it is.

const POLL_MS = 30_000;
const q = encodeURIComponent;

/** A read that answers 404 for "none yet", which is a state, not a failure. */
async function orNone<T>(path: string, signal: AbortSignal): Promise<T | null> {
  try {
    return await request<T>(path, { signal });
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return null;
    throw failure;
  }
}

/** Every order due at these depots on the day, whatever became of it. */
export function useOrders(depots: string[], date: string): Resource<OrderView[]> {
  const load =
    depots.length === 0
      ? null
      : async (signal: AbortSignal) => {
          const perDepot = await Promise.all(
            depots.map((depot) => request<OrderView[]>(`/api/orders/day?depot=${q(depot)}&date=${q(date)}`, { signal })),
          );
          return perDepot.flat().sort((a, b) => a.orderRef.localeCompare(b.orderRef));
        };
  return useResource(load, `orders|${depots.join(",")}|${date}`, POLL_MS);
}

export type DepotPlans = { depot: string; published: PlanView | null; draft: PlanView | null };

/** The published plan and the open draft of each depot for the day; either may be absent. */
export function usePlans(depots: string[], date: string): Resource<DepotPlans[]> {
  const load =
    depots.length === 0
      ? null
      : (signal: AbortSignal) =>
          Promise.all(
            depots.map(async (depot) => {
              const where = `depot=${q(depot)}&date=${q(date)}`;
              const [published, draft] = await Promise.all([
                orNone<PlanView>(`/api/plans/published?${where}`, signal),
                orNone<PlanView>(`/api/plans/draft?${where}`, signal),
              ]);
              return { depot, published, draft };
            }),
          );
  return useResource(load, `plans|${depots.join(",")}|${date}`, POLL_MS);
}

export type LiveDay = { sheets: RunSheetView[]; dock: ReadyTripView[] };

/** Vehicles on the road from Execution, and the day's trips at the dock from Loading. */
export function useLive(depots: string[], date: string): Resource<LiveDay> {
  const load =
    depots.length === 0
      ? null
      : async (signal: AbortSignal) => {
          const where = (depot: string) => `depot=${q(depot)}&date=${q(date)}`;
          const [sheets, dock] = await Promise.all([
            Promise.all(depots.map((depot) => request<RunSheetView[]>(`/api/execution/run-sheets?${where(depot)}`, { signal }))),
            Promise.all(depots.map((depot) => request<ReadyTripView[]>(`/api/loading/trips?${where(depot)}`, { signal }))),
          ]);
          return { sheets: sheets.flat(), dock: dock.flat() };
        };
  return useResource(load, `live|${depots.join(",")}|${date}`, POLL_MS);
}
