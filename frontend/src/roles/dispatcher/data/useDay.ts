"use client";

import { request, requestAll } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";
import { useResource, type Resource } from "@shared/api/useResource";
import { addDays } from "@shared/wording";
import type { DepotView, VehiclePositionView, DeferralView, FuelView, IssueHistoryView, IssueView, OrderView, PlanView, ReadyTripView, RunSheetView } from "@shared/domain/types";

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

/** Orders a plan for their day would still take: confirmed, or deferred and back in the queue. */
export function waitingToPlan(orders: OrderView[]): number {
  return orders.filter((order) => order.status === "CONFIRMED" || order.status === "DEFERRED").length;
}

/** How far ahead the Plan screen looks for the next day with orders waiting. */
export const LOOK_AHEAD_DAYS = 7;

/**
 * The first day after `date`, within a week, with orders waiting to be planned
 * (issue #114). A dispatcher usually plans the next delivery day while the
 * screen opens on today, so an empty today points there instead of to an
 * empty plan. Read only while `enabled`, one day at a time, stopping at the
 * first hit.
 */
export function useNextOrderDay(depots: string[], date: string, enabled: boolean): Resource<{ date: string; waiting: number } | null> {
  const load =
    !enabled || depots.length === 0
      ? null
      : async (signal: AbortSignal) => {
          for (let ahead = 1; ahead <= LOOK_AHEAD_DAYS; ahead++) {
            const day = addDays(date, ahead);
            const perDepot = await Promise.all(
              depots.map((depot) => request<OrderView[]>(`/api/orders/day?depot=${q(depot)}&date=${q(day)}`, { signal })),
            );
            const waiting = waitingToPlan(perDepot.flat());
            if (waiting > 0) return { date: day, waiting };
          }
          return null;
        };
  return useResource(load, `next-order-day|${depots.join(",")}|${date}|${enabled}`);
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

export type LiveDay = {
  sheets: RunSheetView[];
  dock: ReadyTripView[];
  /** The depot each vehicle's run sheet was read under, for the map's depot filter. */
  depotOf: Record<string, string>;
};

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
          const depotOf: Record<string, string> = {};
          sheets.forEach((list, i) => list.forEach((sheet) => (depotOf[sheet.vehicleId] = depots[i]!)));
          return { sheets: sheets.flat(), dock: dock.flat(), depotOf };
        };
  return useResource(load, `live|${depots.join(",")}|${date}`, POLL_MS);
}

/** Every open and assigned issue at these depots, most severe first per depot. */
export function useIssues(depots: string[]): Resource<IssueView[]> {
  const load =
    depots.length === 0
      ? null
      : async (signal: AbortSignal) =>
          (await Promise.all(depots.map((depot) => requestAll<IssueView>(`/api/issues?depot=${q(depot)}`, { signal })))).flat();
  return useResource(load, `issues|${depots.join(",")}`, POLL_MS);
}

export type IssueRecord = { issue: IssueView; history: IssueHistoryView[] };

/** One issue and every decision on it, read by id so a resolved issue stays on screen. */
export function useIssue(issueId: string | null): Resource<IssueRecord> {
  const load =
    issueId === null
      ? null
      : async (signal: AbortSignal) => {
          const path = `/api/issues/${q(issueId)}`;
          const [issue, history] = await Promise.all([
            request<IssueView>(path, { signal }),
            request<IssueHistoryView[]>(`${path}/history`, { signal }),
          ]);
          return { issue, history };
        };
  return useResource(load, `issue|${issueId ?? ""}`, POLL_MS);
}

/** Outlets the day's published plans left out, each with how often it has been skipped (R-PLN-20). */
export function useDeferrals(depots: string[], date: string): Resource<DeferralView[]> {
  const load =
    depots.length === 0
      ? null
      : async (signal: AbortSignal) =>
          (await Promise.all(depots.map((depot) => request<DeferralView[]>(`/api/plans/deferrals?depot=${q(depot)}&date=${q(date)}`, { signal }))))
            .flat()
            .sort((a, b) => b.skipCount - a.skipCount || a.outletId.localeCompare(b.outletId));
  return useResource(load, `deferrals|${depots.join(",")}|${date}`, POLL_MS);
}

/** A vehicle's planned fuel for the week holding the date, from published plans only (D-K). */
export function useFuel(vehicleId: string, date: string): Resource<FuelView> {
  return useResource(
    (signal) => request<FuelView>(`/api/plans/fuel?vehicle=${q(vehicleId)}&date=${q(date)}`, { signal }),
    `fuel|${vehicleId}|${date}`,
  );
}

/** Each vehicle's last good fix in these depots, every 15 seconds while visible (issue #161, D5). */
export function usePositions(depots: string[], date: string): Resource<VehiclePositionView[]> {
  const load =
    depots.length === 0
      ? null
      : async (signal: AbortSignal) =>
          (await Promise.all(depots.map((depot) => request<VehiclePositionView[]>(`/api/execution/positions?depot=${q(depot)}&date=${q(date)}`, { signal })))).flat();
  return useResource(load, `positions|${depots.join(",")}|${date}`, 15_000);
}

/** The depots and their locations, read once. */
export function useDepots(depots: string[]): Resource<DepotView[]> {
  const load =
    depots.length === 0 ? null : async (signal: AbortSignal) => Promise.all(depots.map((depot) => request<DepotView>(`/api/reference/depots/${q(depot)}`, { signal })));
  return useResource(load, `depots|${depots.join(",")}`);
}
