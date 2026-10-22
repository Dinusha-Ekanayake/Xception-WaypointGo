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
  StopOrderSuggestion,
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
 *
 * Each read rebuilds the draft on the server, so they go two at a time rather
 * than all at once: a peak day defers a dozen orders, and a dozen parallel
 * rebuilds held every database connection and failed the rest. One read that
 * still fails is tried once more and then left out, so one order's panel says
 * it could not be read while every other order keeps its places.
 */
export function usePlacements(planId: string, orderIds: string[]): Resource<Record<string, PlacementView[]>> {
  const key = `placements|${planId}|${orderIds.join(",")}`;
  const load =
    orderIds.length === 0
      ? null
      : async (signal: AbortSignal) => {
          const read = (id: string) => request<PlacementView[]>(`/api/plans/preview/placements?order=${q(id)}`, { signal });
          const out: Record<string, PlacementView[]> = {};
          let failures = 0;
          let firstError: unknown = null;
          await inBatches(orderIds, PLACEMENT_READS_AT_ONCE, async (id) => {
            try {
              out[id] = await read(id).catch((e: unknown) => {
                if (signal.aborted) throw e;
                return read(id);
              });
            } catch (e) {
              if (signal.aborted) throw e;
              failures++;
              firstError ??= e;
            }
          });
          if (failures === orderIds.length) throw firstError;
          return out;
        };
  return useResource(load, key);
}

const PLACEMENT_READS_AT_ONCE = 2;

/** Runs `work` over `items`, at most `limit` at a time, in order of start. */
async function inBatches<T>(items: T[], limit: number, work: (item: T) => Promise<void>): Promise<void> {
  let next = 0;
  const lane = async () => {
    while (next < items.length) {
      const item = items[next++]!;
      await work(item);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, lane));
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

/**
 * The stop order that fits the trip's windows best for the orders the dispatcher
 * has now (R-PLN-43); null skips the read. Asked again whenever the orders change.
 */
export function useStopOrderSuggestion(tripId: string | null, orderIds: string[] | null, planId: string): Resource<StopOrderSuggestion> {
  return useResource(
    tripId === null || orderIds === null || orderIds.length < 2
      ? null
      : (signal) => request<StopOrderSuggestion>(`/api/plans/preview/suggested-sequence?trip=${q(tripId)}&orders=${orderIds.map(q).join(",")}`, { signal }),
    `suggested|${planId}|${tripId ?? ""}|${(orderIds ?? []).join(",")}`,
  );
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
/**
 * A plan's late risk: the stored scoring of a published plan, or (R-ML-48) a
 * draft's risk scored when asked, read again whenever the draft changes
 * ({@code stamp}). The draft's is advice only and kept nowhere.
 */
export function usePredictions(planId: string | null, draft = false, stamp = ""): Resource<PlanPredictionsView | null> {
  return useResource(
    planId === null
      ? null
      : async (signal) => {
          try {
            return await request<PlanPredictionsView>(`/api/ml/plans/${q(planId)}/${draft ? "draft-risk" : "predictions"}`, { signal });
          } catch (failure) {
            if (failure instanceof ApiError && failure.status === 404) return null;
            throw failure;
          }
        },
    `predictions|${planId ?? ""}|${draft ? `draft|${stamp}` : ""}`,
  );
}

/**
 * A trip holding exactly these orders in this order (none removes it), timed
 * and judged on the vehicle's whole day, before plan:EditTrip is sent; null skips the read.
 */
export function useTripEditPreview(tripId: string | null, orderIds: string[] | null, planId: string): Resource<TripPreview> {
  const orders = orderIds && orderIds.length > 0 ? `&orders=${orderIds.map(q).join(",")}` : "";
  return useResource(
    tripId === null || orderIds === null ? null : (signal) => request<TripPreview>(`/api/plans/preview/trip?trip=${q(tripId)}${orders}`, { signal }),
    `trip-edit|${planId}|${tripId ?? ""}|${orderIds?.join(",") ?? ""}`,
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
