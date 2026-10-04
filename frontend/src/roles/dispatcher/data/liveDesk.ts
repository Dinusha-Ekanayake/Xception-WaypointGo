import type { FuelView, IssueView, ReadyTripView, RunSheetStopView, StoreAnswerWaiverReason, VehiclePositionView } from "@shared/domain/types";
import { mapStatus, type MapStatus, type VehicleDay } from "./live.ts";

// The Figma "05 Live" frames (189:20983 to 189:21943) read from what the
// dispatcher already loads: each vehicle's run sheet joined to its loading trip
// (brand, district, trip number, load, release) and its last position. Pure; the
// clock is a parameter and every time is the depot's (Asia/Colombo).

const OFFSET_MS = 330 * 60_000;

/** "16:55" in depot time for an instant. */
export function depotClock(iso: string): string {
  const d = new Date(new Date(iso).getTime() + OFFSET_MS);
  return `${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
}

const hhmm = (time: string): string => time.slice(0, 5);

function windowCloseAt(date: string, stop: RunSheetStopView): number {
  return new Date(`${date}T${hhmm(stop.windowClose)}:00+05:30`).getTime();
}

/** The arrival the screen shows: recorded, else expected, else planned. */
export function etaOf(stop: RunSheetStopView): string {
  const actual = stop.arrivedAt ?? stop.completedAt;
  if (actual) return depotClock(actual);
  if (stop.expectedArrival) return depotClock(stop.expectedArrival);
  return hhmm(stop.plannedArrival);
}

export type Run = {
  day: VehicleDay;
  /** The loading trip the vehicle is on, matched by the stop's trip; null when Loading did not answer. */
  trip: ReadyTripView | null;
  position: VehiclePositionView | null;
  status: MapStatus;
  depot: string | null;
};

/** One row per vehicle on the road, its trip and position joined in. */
export function runsOf(
  days: VehicleDay[],
  trips: ReadyTripView[],
  positions: VehiclePositionView[],
  depotOf: Record<string, string>,
  date: string,
  now: Date,
): Run[] {
  const byTrip = new Map(trips.map((t) => [t.tripId, t]));
  const byVehicle = new Map(positions.map((p) => [p.vehicleId, p]));
  return days.map((day) => {
    const tripId = (day.current ?? day.stops[day.stops.length - 1])?.tripId;
    const trip =
      (tripId ? byTrip.get(tripId) : undefined) ??
      trips.filter((t) => t.vehicleId === day.vehicleId && t.releasedAt !== null).sort((a, b) => b.tripNumber - a.tripNumber)[0] ??
      null;
    const position = byVehicle.get(day.vehicleId) ?? null;
    return { day, trip, position, status: mapStatus(date, day, position, now), depot: depotOf[day.vehicleId] ?? null };
  });
}

/** "Style · Matara", from the trip; empty when Loading did not answer. */
export function routeLabel(run: Run): string {
  return run.trip ? `${run.trip.brandCode} · ${run.trip.districtName}` : "";
}

/** "VEH020 · Style · Matara". */
export function runTitle(run: Run): string {
  const route = routeLabel(run);
  return route ? `${run.day.vehicleId} · ${route}` : run.day.vehicleId;
}

/** Minutes since the last position, when the phone has gone quiet. */
export function silentMinutes(run: Run, now: Date): number | null {
  if (!run.position?.offline) return null;
  return Math.max(0, Math.round((now.getTime() - new Date(run.position.recordedAt).getTime()) / 60_000));
}

export type Tone = "danger" | "warning" | "muted" | "success" | "info";

/** Why a driver moved on before the store answered, as the dispatcher reads it. */
const WAIVED: Record<StoreAnswerWaiverReason, string> = {
  store_absent: "Store manager not available",
  no_signal: "No signal at the stop",
  disagree: "Driver disagrees",
};

export type NeedCard = {
  id: string;
  kind: "window" | "offline" | "failed" | "issue" | "left";
  /** Minutes to act, for the order and the chip; null when there is no clock. */
  minutesLeft: number | null;
  chip: { text: string; tone: Tone };
  meta: string;
  title: string;
  detail: string;
  vehicleId: string | null;
  issueId: string | null;
};

/**
 * Figma "Needs you": what the dispatcher must act on, most urgent first. A stop
 * whose window will close before the vehicle gets there, a vehicle whose phone
 * has gone quiet, a stop not delivered, and an open issue on a delivery.
 */
export function needCards(runs: Run[], issues: IssueView[], date: string, now: Date): NeedCard[] {
  const cards: NeedCard[] = [];
  for (const run of runs) {
    const next = run.day.current;
    const left = next ? Math.round((windowCloseAt(date, next) - now.getTime()) / 60_000) : null;
    const leftChip = (fallback: string): { text: string; tone: Tone } =>
      left === null ? { text: fallback, tone: "muted" } : left < 0 ? { text: "Window closed", tone: "danger" } : { text: `${left} min left`, tone: left <= 15 ? "danger" : "warning" };
    for (const stop of run.day.stops.filter((s) => s.outcome === "FAILED")) {
      cards.push({
        id: `failed-${stop.deliveryId}`,
        kind: "failed",
        minutesLeft: -Infinity,
        chip: { text: "Not delivered", tone: "danger" },
        meta: stop.completedAt ? `recorded ${depotClock(stop.completedAt)}` : "",
        title: `${stop.outletId} · not delivered`,
        detail: [run.day.vehicleId, routeLabel(run), `stop ${stop.sequence}`].filter(Boolean).join(" · "),
        vehicleId: run.day.vehicleId,
        issueId: null,
      });
    }
    // Issue #21: the driver moved on before the store answered, and said why.
    // A handover the store checked is its own evidence; this one is not, yet.
    for (const stop of run.day.stops.filter((s) => s.storeAnswerWaived)) {
      cards.push({
        id: `left-${stop.deliveryId}`,
        kind: "left",
        minutesLeft: 1e8,
        chip: { text: WAIVED[stop.storeAnswerWaived!], tone: stop.storeAnswerWaived === "disagree" ? "warning" : "muted" },
        meta: stop.completedAt ? `handed over ${depotClock(stop.completedAt)}` : "",
        title: `${stop.outletId} · left before the store answered`,
        detail: [run.day.vehicleId, routeLabel(run), `stop ${stop.sequence}`, stop.proofCaptured ? "" : "no photo or signature"]
          .filter(Boolean)
          .join(" · "),
        vehicleId: run.day.vehicleId,
        issueId: null,
      });
    }
    if (run.status === "offline") {
      const quiet = silentMinutes(run, now);
      const stopsLeft = run.day.stops.length - run.day.done;
      cards.push({
        id: `offline-${run.day.vehicleId}`,
        kind: "offline",
        minutesLeft: left,
        chip: leftChip("No signal"),
        meta: quiet !== null ? `no signal ${quiet} min` : "no signal",
        title: `${run.day.vehicleId} · driver offline`,
        detail: [routeLabel(run), `${stopsLeft} ${stopsLeft === 1 ? "stop" : "stops"} left`].filter(Boolean).join(" · "),
        vehicleId: run.day.vehicleId,
        issueId: null,
      });
    } else if (run.status === "at-risk" && next) {
      cards.push({
        id: `window-${next.deliveryId}`,
        kind: "window",
        minutesLeft: left,
        chip: leftChip("At risk"),
        meta: `window ${hhmm(next.windowClose)}`,
        title: `${next.outletId} · may miss its window`,
        detail: [run.day.vehicleId, routeLabel(run), `expected ${etaOf(next)}`].filter(Boolean).join(" · "),
        vehicleId: run.day.vehicleId,
        issueId: null,
      });
    }
  }
  for (const issue of issues) {
    if (issue.status !== "OPEN" && issue.status !== "ASSIGNED") continue;
    const age = Math.max(0, Math.round((now.getTime() - new Date(issue.raisedAt).getTime()) / 60_000));
    const order = issue.subjects.find((s) => s.type === "order")?.id ?? null;
    cards.push({
      id: `issue-${issue.issueId}`,
      kind: "issue",
      minutesLeft: null,
      chip: { text: age < 60 ? `${age} min ago` : `${Math.floor(age / 60)} h ago`, tone: issue.severity === "HIGH" || issue.severity === "CRITICAL" ? "danger" : "muted" },
      meta: `reported ${depotClock(issue.raisedAt)}`,
      title: `${issue.outletId ?? issue.depotCode} · ${issue.description}`,
      detail: [order ? `order ${order.slice(0, 8)}` : null, issue.type.toLowerCase().replace(/_/g, " ")].filter(Boolean).join(" · "),
      vehicleId: null,
      issueId: issue.issueId,
    });
  }
  // Failed stops first, then by minutes to act; an issue comes before a proof owed.
  const rank = (c: NeedCard): number => (c.kind === "failed" ? -1e9 : c.kind === "issue" ? 1e7 : c.minutesLeft ?? 1e9);
  return cards.sort((a, b) => rank(a) - rank(b));
}

/** Share of the run's stops closed, for the trip board and the cards' bars. */
export function progress(run: Run): number {
  return run.day.stops.length ? run.day.done / run.day.stops.length : 0;
}

export type DepotSummary = { depot: string; onTheRoad: number; onTime: number; atRisk: number; offline: number };

/** "Kandy · 4 on the road · 3 on time · 1 offline", per depot. */
export function depotSummaries(runs: Run[]): DepotSummary[] {
  const out = new Map<string, DepotSummary>();
  for (const run of runs) {
    const depot = run.depot ?? "Depot";
    const s = out.get(depot) ?? { depot, onTheRoad: 0, onTime: 0, atRisk: 0, offline: 0 };
    if (run.day.state !== "finished") s.onTheRoad += 1;
    if (run.status === "on-time") s.onTime += 1;
    if (run.status === "at-risk" || run.status === "late") s.atRisk += 1;
    if (run.status === "offline") s.offline += 1;
    out.set(depot, s);
  }
  return [...out.values()];
}

export function summaryText(s: DepotSummary): string {
  return [`${s.onTheRoad} on the road`, s.onTime ? `${s.onTime} on time` : null, s.atRisk ? `${s.atRisk} at risk` : null, s.offline ? `${s.offline} offline` : null]
    .filter(Boolean)
    .join(" · ");
}

/** Stops closed with nothing for the dispatcher to do: delivered in the window, proof taken. */
export function closedOnTheirOwn(runs: Run[]): number {
  return runs
    .flatMap((r) => r.day.stops)
    .filter((s) => (s.outcome === "DELIVERED" || s.outcome === "PARTIAL") && s.proofCaptured && (s.lateMinutes ?? 0) === 0).length;
}

export type ActivityItem = { at: string; text: string; by: "driver" | "system" };

/** Figma "Trip activity", newest first, from what the run sheet and the trip recorded. */
export function activity(run: Run, depotName: string): ActivityItem[] {
  const items: Array<ActivityItem & { t: number }> = [];
  const add = (iso: string | null, text: string, by: ActivityItem["by"]) => {
    if (iso) items.push({ at: depotClock(iso), text, by, t: new Date(iso).getTime() });
  };
  add(run.trip?.releasedAt ?? null, `Left ${depotName}`, "driver");
  for (const stop of run.day.stops) {
    add(stop.arrivedAt, `Arrived at ${stop.outletId}`, "driver");
    const closed = stop.outcome === "FAILED" ? `Not delivered at ${stop.outletId}` : stop.outcome === "PARTIAL" ? `Partly delivered at ${stop.outletId}` : stop.outcome === "DELIVERED" ? `Delivered at ${stop.outletId}` : null;
    if (closed) add(stop.completedAt, closed, "driver");
  }
  if (run.position) add(run.position.recordedAt, run.position.offline ? "Phone went quiet" : "Last position", "system");
  return items.sort((a, b) => b.t - a.t).map(({ at, text, by }) => ({ at, text, by }));
}

export type Limit = { label: string; value: string; share: number };

/** The trip time budget a brand runs under: Fresh 270 min (R-PLN-09), Style and Tech 480 (R-PLN-10). */
export function tripBudget(brand: string | null): number {
  return brand === "Fresh" ? 270 : 480;
}

/** Figma "Limits right now": trip time since release, the week's fuel quota, the load against capacity. */
export function limits(run: Run, fuel: FuelView | null, now: Date): Limit[] {
  const out: Limit[] = [];
  if (run.trip?.releasedAt) {
    const used = Math.max(0, Math.round((now.getTime() - new Date(run.trip.releasedAt).getTime()) / 60_000));
    const budget = tripBudget(run.trip.brandCode);
    out.push({ label: "Trip time", value: `${used} / ${budget} min`, share: Math.min(1, used / budget) });
  }
  if (fuel && Number(fuel.quotaLitres) > 0) {
    const share = Number(fuel.usedLitres) / Number(fuel.quotaLitres);
    out.push({ label: "Fuel quota", value: `${Math.round(share * 100)}% used`, share: Math.min(1, share) });
  }
  if (run.trip && run.trip.volumeCapM3 !== undefined && Number(run.trip.volumeCapM3) > 0) {
    const load = Number(run.trip.volumeM3);
    const cap = Number(run.trip.volumeCapM3);
    out.push({ label: "Load", value: `${round1(load)} / ${round1(cap)} m³`, share: Math.min(1, load / cap) });
  }
  return out;
}

export function round1(n: number): string {
  return (Math.round(n * 10) / 10).toString();
}

export type RunFilter = "all" | "at-risk" | "offline";

/** The runs a depot and status filter keep, with the counts the header's chips show. */
export function filterRuns(runs: Run[], depot: string, filter: RunFilter): { runs: Run[]; inDepot: number; atRisk: number; offline: number } {
  const inDepot = runs.filter((r) => depot === "all" || r.depot === depot);
  const risky = (r: Run): boolean => r.status === "at-risk" || r.status === "late";
  return {
    runs: inDepot.filter((r) => filter === "all" || (filter === "at-risk" ? risky(r) : r.status === "offline")),
    inDepot: inDepot.length,
    atRisk: inDepot.filter(risky).length,
    offline: inDepot.filter((r) => r.status === "offline").length,
  };
}
