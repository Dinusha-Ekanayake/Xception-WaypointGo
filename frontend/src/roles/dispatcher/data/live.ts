import type { ReadyTripView, RunSheetStopView, RunSheetView } from "@shared/domain/types";

// A vehicle's day on the road, from Execution's run sheet. Pure; the clock is a
// parameter. Lateness is the server's, measured against the window close
// (R-EXE-14); for a stop not yet reached the screen compares the window with
// the time now and says "running late", which is a reading, not a record.

const ZONE_OFFSET = "+05:30";

export type RoadState = "not-started" | "driving" | "at-stop" | "finished";
export type Risk = "ok" | "late" | "failed";

export type VehicleDay = {
  vehicleId: string;
  stops: RunSheetStopView[];
  done: number;
  failed: number;
  /** Stops recorded late, and stops not reached whose window has already closed. */
  late: number;
  state: RoadState;
  /** The stop being driven to or served; null once the run is finished. */
  current: RunSheetStopView | null;
  risk: Risk;
};

function finished(stop: RunSheetStopView): boolean {
  return stop.outcome !== "PENDING" && stop.outcome !== "ARRIVED";
}

function closes(serviceDate: string, stop: RunSheetStopView): number {
  return new Date(`${serviceDate}T${stop.windowClose.length === 5 ? `${stop.windowClose}:00` : stop.windowClose}${ZONE_OFFSET}`).getTime();
}

/** Recorded late by the server, or not yet reached with its window already closed. */
export function isLate(serviceDate: string, stop: RunSheetStopView, now: Date): boolean {
  if ((stop.lateMinutes ?? 0) > 0) return true;
  return stop.outcome === "PENDING" && now.getTime() > closes(serviceDate, stop);
}

export function vehicleDay(sheet: RunSheetView, now: Date): VehicleDay {
  const stops = sheet.stops;
  const current = stops.find((stop) => !finished(stop)) ?? null;
  const failed = stops.filter((stop) => stop.outcome === "FAILED").length;
  const late = stops.filter((stop) => stop.outcome !== "SKIPPED" && isLate(sheet.serviceDate, stop, now)).length;
  const started = stops.some((stop) => stop.startedAt !== null || stop.arrivedAt !== null || finished(stop));
  const state: RoadState = !current ? "finished" : current.outcome === "ARRIVED" ? "at-stop" : started ? "driving" : "not-started";
  return {
    vehicleId: sheet.vehicleId,
    stops,
    done: stops.filter(finished).length,
    failed,
    late,
    state,
    current,
    risk: failed > 0 ? "failed" : late > 0 ? "late" : "ok",
  };
}

const RISK_ORDER: Record<Risk, number> = { failed: 0, late: 1, ok: 2 };

/** Most urgent first: failed, then late, then by how much of the run is left. */
export function byUrgency(days: VehicleDay[]): VehicleDay[] {
  return [...days].sort((a, b) => {
    const risk = RISK_ORDER[a.risk] - RISK_ORDER[b.risk];
    if (risk !== 0) return risk;
    const finishedLast = Number(a.state === "finished") - Number(b.state === "finished");
    return finishedLast !== 0 ? finishedLast : a.vehicleId.localeCompare(b.vehicleId);
  });
}

export type Attention = {
  vehicleId: string;
  stop: RunSheetStopView;
  kind: "failed" | "late" | "proof-owed";
};

/** What needs the dispatcher: a stop not delivered, a stop late, a delivery with no proof yet. */
export function attention(sheets: RunSheetView[], now: Date): Attention[] {
  const found: Attention[] = [];
  for (const sheet of sheets) {
    for (const stop of sheet.stops) {
      if (stop.outcome === "FAILED") found.push({ vehicleId: sheet.vehicleId, stop, kind: "failed" });
      else if (stop.outcome !== "SKIPPED" && isLate(sheet.serviceDate, stop, now)) found.push({ vehicleId: sheet.vehicleId, stop, kind: "late" });
      else if ((stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL") && !stop.proofCaptured) found.push({ vehicleId: sheet.vehicleId, stop, kind: "proof-owed" });
    }
  }
  const order = { failed: 0, late: 1, "proof-owed": 2 } as const;
  return found.sort((a, b) => order[a.kind] - order[b.kind] || a.vehicleId.localeCompare(b.vehicleId) || a.stop.sequence - b.stop.sequence);
}

export type LiveTotals = { onTheRoad: number; finished: number; atDock: number; stopsDone: number; stops: number };

/**
 * @param dock the day's trips from Loading; one with no release is still at the dock
 */
export function totals(days: VehicleDay[], dock: ReadyTripView[]): LiveTotals {
  return {
    onTheRoad: days.filter((day) => day.state !== "finished").length,
    finished: days.filter((day) => day.state === "finished").length,
    atDock: dock.filter((trip) => trip.releasedAt === null).length,
    stopsDone: days.reduce((sum, day) => sum + day.done, 0),
    stops: days.reduce((sum, day) => sum + day.stops.length, 0),
  };
}

export type Punctuality = { served: number; onTime: number };

/**
 * Stops delivered in whole or part, and how many of those inside their window,
 * as the server recorded them (R-EXE-14). A stop not reached yet counts in
 * neither: on time is a fact about an arrival, not a forecast.
 */
export function punctuality(sheets: RunSheetView[]): Punctuality {
  const served = sheets.flatMap((sheet) => sheet.stops).filter((stop) => stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL");
  return { served: served.length, onTime: served.filter((stop) => (stop.lateMinutes ?? 0) === 0).length };
}

export type MapStatus = "on-time" | "at-risk" | "late" | "returning" | "offline";

/**
 * The colour of a vehicle on the live map (issue #161). Offline wins, because
 * the dispatcher must not read a stale point as live. A finished run is
 * returning: there is no record of reaching the depot. Late is recorded (a stop
 * late or not delivered); at risk is a reading of the stop ahead, whose window
 * has closed or whose expected arrival is past it.
 */
export function mapStatus(serviceDate: string, day: VehicleDay, position: { offline: boolean } | null, now: Date): MapStatus {
  if (position?.offline) return "offline";
  if (day.state === "finished") return "returning";
  if (day.failed > 0 || day.stops.some((stop) => (stop.lateMinutes ?? 0) > 0)) return "late";
  const ahead = day.current;
  if (ahead && ahead.outcome === "PENDING") {
    const close = closes(serviceDate, ahead);
    const expected = ahead.expectedArrival ? new Date(ahead.expectedArrival).getTime() : null;
    if (now.getTime() > close || (expected !== null && expected > close)) return "at-risk";
  }
  return "on-time";
}
