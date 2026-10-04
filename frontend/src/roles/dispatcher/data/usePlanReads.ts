"use client";

import { request } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";
import { useResource, type Resource } from "@shared/api/useResource";
import type {
  CalendarAnswer,
  ComparisonView,
  InterchangePreview,
  PlanPredictionsView,
  PlacementView,
  SnapshotDetailView,
  SnapshotView,
  StopOrderProposal,
  TripPreview,
} from "@shared/domain/types";
import { addDays } from "@shared/wording";

// The Plan screen's reads beyond the plan itself: saved plans, a comparison, the
// places an order could take, and the previews a swap or a new stop order asks
// for before the dispatcher commits. None polls: each answers for what the
// screen is showing now, and a command's answer reads them again.

const q = encodeURIComponent;

/** The saved plans of a depot and day, newest first. */
export function useSnapshots(depot: string, date: string): Resource<SnapshotView[]> {
  return useResource((signal) => request<SnapshotView[]>(`/api/plans/snapshots?depot=${q(depot)}&date=${q(date)}`, { signal }), `snapshots|${depot}|${date}`);
}

/** One saved plan with the plan itself; null skips the read. */
export function useSnapshot(snapshotId: string | null): Resource<SnapshotDetailView> {
  return useResource(
    snapshotId === null ? null : (signal) => request<SnapshotDetailView>(`/api/plans/snapshots/${q(snapshotId)}`, { signal }),
    `snapshot|${snapshotId ?? ""}`,
  );
}

/** Two plans of one depot and day side by side; each id is a saved plan or a plan version. */
export function useComparison(a: string | null, b: string | null): Resource<ComparisonView> {
  return useResource(
    a === null || b === null ? null : (signal) => request<ComparisonView>(`/api/plans/compare?a=${q(a)}&b=${q(b)}`, { signal }),
    `compare|${a ?? ""}|${b ?? ""}`,
  );
}

/**
 * Every place each deferred order could take, one read each, as the order's
 * panel and its row's suggestion both need them. Keyed by plan version, so an
 * edit that makes a new draft reads them again.
 */
export function usePlacements(planId: string, orderIds: string[]): Resource<Record<string, PlacementView[]>> {
  const key = `placements|${planId}|${orderIds.join(",")}`;
  const load =
    orderIds.length === 0
      ? null
      : async (signal: AbortSignal) => {
          const entries = await Promise.all(
            orderIds.map(async (id) => [id, await request<PlacementView[]>(`/api/plans/preview/placements?order=${q(id)}`, { signal })] as const),
          );
          return Object.fromEntries(entries);
        };
  return useResource(load, key);
}

/**
 * The trip a swap would leave, and every rule's verdict; null skips the read.
 * With `sequence`, the trip's stops in that order after the swap.
 */
export function useSwapPreview(
  outOrderId: string | null,
  inOrderId: string | null,
  planId: string,
  sequence: string[] | null = null,
): Resource<TripPreview> {
  const orders = sequence && sequence.length > 0 ? `&orders=${sequence.map(q).join(",")}` : "";
  return useResource(
    outOrderId === null || inOrderId === null
      ? null
      : (signal) => request<TripPreview>(`/api/plans/preview/swap?out=${q(outOrderId)}&in=${q(inOrderId)}${orders}`, { signal }),
    `swap|${planId}|${outOrderId ?? ""}|${inOrderId ?? ""}|${orders}`,
  );
}

/**
 * Placeholder for the swap window's "AI order": a stop order proposed for the
 * trip after the swap. No module serves one yet, so this always answers "not
 * available" and the window says so. To wire it, read the proposal here (for
 * example a `GET /api/plans/preview/proposal?out=&in=` served by Intelligence)
 * and return it; the window already offers it, applies it as the dispatcher's
 * stop order and lets the server judge it like any other.
 */
export function useStopOrderProposal(
  outOrderId: string | null,
  inOrderId: string | null,
): { available: false } | { available: true; proposal: Resource<StopOrderProposal> } {
  void outOrderId;
  void inOrderId;
  return { available: false };
}

/** A trip with its stops in the order asked, timed and checked; null skips the read. */
export function useSequencePreview(tripId: string | null, orderIds: string[] | null, planId: string): Resource<TripPreview> {
  return useResource(
    tripId === null || orderIds === null
      ? null
      : (signal) => request<TripPreview>(`/api/plans/preview/sequence?trip=${q(tripId)}&orders=${orderIds.map(q).join(",")}`, { signal }),
    `sequence|${planId}|${tripId ?? ""}|${(orderIds ?? []).join(",")}`,
  );
}

/** The first operating day after a service day: when a deferred order is next offered a vehicle. */
export function useNextDelivery(serviceDate: string): Resource<string> {
  return useResource(
    async (signal) => (await request<CalendarAnswer>(`/api/reference/calendar/${q(addDays(serviceDate, 1))}`, { signal })).nextOperatingDay,
    `next-delivery|${serviceDate}`,
  );
}

/**
 * How a published plan's stops were scored for lateness. A draft is never
 * scored, and a plan the scorer has not reached answers 404: both read as "not
 * scored", which the screen says, so the read is skipped for a draft and an
 * answer of none is not an error.
 */
export function usePredictions(planId: string | null): Resource<PlanPredictionsView | null> {
  return useResource(
    planId === null
      ? null
      : async (signal) => {
          try {
            return await request<PlanPredictionsView>(`/api/ml/plans/${q(planId)}/predictions`, { signal });
          } catch (failure) {
            if (failure instanceof ApiError && failure.status === 404) return null;
            throw failure;
          }
        },
    `predictions|${planId ?? ""}`,
  );
}

/** What moving a whole trip to another vehicle would do, judged before plan:Replan is sent; null skips the read. */
export function useInterchange(planId: string, tripId: string, vehicleId: string | null): Resource<InterchangePreview> {
  return useResource(
    vehicleId === null
      ? null
      : (signal) => request<InterchangePreview>(`/api/plans/preview/interchange?trip=${q(tripId)}&vehicle=${q(vehicleId)}`, { signal }),
    `interchange|${planId}|${tripId}|${vehicleId ?? ""}`,
  );
}
