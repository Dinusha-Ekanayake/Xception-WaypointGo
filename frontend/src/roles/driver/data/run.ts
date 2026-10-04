import type { Command } from "@shared/api/commands";
import type { DeliveryOutcome, RunSheetStopView, RunSheetView } from "@shared/domain/types";
export { clock } from "../../../shared/wording/index.ts";

// The driver's run as the phone believes it is: what the server last said, with
// the writes still waiting on this device applied on top.
//
// Pure functions, no browser and no clock: every time is a parameter. The
// transitions mirror execution/domain/DeliveryRecord.java, and the server
// remains the authority: what is shown here as late or on time is the phone's
// reading, and the server's clock decides when the write arrives (R-EXE-10).

/** The depots work on Sri Lanka time, which has no daylight saving. */
const OPERATING_OFFSET = "+05:30";

export type Stop = RunSheetStopView & {
  /** One or more writes for this stop are still only on this device. */
  waiting: boolean;
};

export type WaitingWrite = { command: Command; needsReview: boolean };

export const DeliveryKind = {
  start: "delivery:Start",
  arrive: "delivery:RecordArrival",
  record: "delivery:Record",
  proof: "delivery:CaptureProof",
} as const;

/** A time of day on the service date, as an instant. */
export function at(serviceDate: string, time: string): Date {
  return new Date(`${serviceDate}T${time.length === 5 ? `${time}:00` : time}${OPERATING_OFFSET}`);
}

/** Minutes after the window closed, rounded up; zero inside it (R-EXE-14: against the close, not the plan). */
export function lateMinutes(serviceDate: string, windowClose: string, arrived: Date): number {
  const over = arrived.getTime() - at(serviceDate, windowClose).getTime();
  return over > 0 ? Math.ceil(over / 60_000) : 0;
}

/** Minutes until the window opens, rounded up; zero once it is open (R-EXE-04). */
export function waitMinutes(serviceDate: string, windowOpen: string, arrived: Date): number {
  const early = at(serviceDate, windowOpen).getTime() - arrived.getTime();
  return early > 0 ? Math.ceil(early / 60_000) : 0;
}

function deliveryIdOf(command: Command): string | null {
  const payload = command.payload as { deliveryId?: unknown } | null;
  return typeof payload?.deliveryId === "string" ? payload.deliveryId : null;
}

/**
 * Applies the waiting writes to the server's copy of the run. A write the
 * server refused is not applied: it did not happen, and a person decides what
 * becomes of it. Each applied write moves the stop's version on by one, exactly
 * as the server will, so the next write names the right version.
 */
export function project(sheet: RunSheetView, writes: WaitingWrite[]): Stop[] {
  const stops = new Map<string, Stop>(sheet.stops.map((stop) => [stop.deliveryId, { ...stop, waiting: false }]));
  for (const { command, needsReview } of writes) {
    if (needsReview) continue;
    const id = deliveryIdOf(command);
    const stop = id ? stops.get(id) : undefined;
    if (!stop) continue;
    const when = command.clientRecordedAt;
    const payload = command.payload as Record<string, unknown>;
    switch (command.kind) {
      case DeliveryKind.start:
        stop.startedAt = stop.startedAt ?? when;
        break;
      case DeliveryKind.arrive: {
        const arrived = new Date(when);
        stop.outcome = "ARRIVED";
        stop.arrivedAt = when;
        stop.startedAt = stop.startedAt ?? when;
        stop.waitMinutes = waitMinutes(sheet.serviceDate, stop.windowOpen, arrived);
        stop.lateMinutes = lateMinutes(sheet.serviceDate, stop.windowClose, arrived);
        break;
      }
      case DeliveryKind.record:
        stop.outcome = payload.outcome as DeliveryOutcome;
        stop.completedAt = when;
        stop.deliveredUnits = typeof payload.deliveredUnits === "number" ? payload.deliveredUnits : null;
        break;
      case DeliveryKind.proof:
        stop.proofCaptured = true;
        break;
      default:
        continue;
    }
    stop.rowVersion += 1;
    stop.waiting = true;
  }
  return sheet.stops.map((stop) => stops.get(stop.deliveryId)!);
}

export function isFinished(stop: Pick<Stop, "outcome">): boolean {
  return stop.outcome !== "PENDING" && stop.outcome !== "ARRIVED";
}

/** The stop to work on: the first one, in order, that has no outcome yet. */
export function nextStop(stops: Stop[]): Stop | null {
  return stops.find((stop) => !isFinished(stop)) ?? null;
}

/**
 * Whether this stop may be recorded as delivered. A mall takes goods only
 * inside its window, so a late arrival there can only be recorded as failed
 * (EXE-20). Everywhere else a late arrival is still delivered (R-EXE-05).
 */
export function canDeliver(stop: Pick<Stop, "mallOutlet" | "lateMinutes">): boolean {
  return !(stop.mallOutlet && (stop.lateMinutes ?? 0) > 0);
}

export type RecordDraft = {
  outcome: "DELIVERED" | "PARTIAL" | "FAILED";
  deliveredUnits: number;
  reason: string;
  dispositionNote: string;
};

/**
 * What is still missing before a record can be sent, in the driver's words.
 * The same rules the server enforces, checked here so a refusal is not the
 * first the driver hears of them; the server checks again.
 *
 * @param timingUncertain the phone was offline at arrival, so the server will
 *     not ask why the stop was late
 */
export function missing(stop: Stop, draft: RecordDraft, timingUncertain: boolean): string | null {
  const reason = draft.reason.trim();
  const note = draft.dispositionNote.trim();
  if (draft.outcome === "FAILED") {
    if (!reason) return "Choose why the delivery could not be made.";
    if (!note) return "Say what happened to the goods.";
    return null;
  }
  if (stop.outcome !== "ARRIVED") return "Record your arrival first.";
  if (!canDeliver(stop)) return "The mall's delivery window has closed. Record this stop as not delivered.";
  if (draft.outcome === "PARTIAL") {
    if (!(draft.deliveredUnits >= 1 && draft.deliveredUnits < stop.itemCount)) {
      return `A partial delivery is between 1 and ${stop.itemCount - 1} units.`;
    }
    if (!reason) return "Say why the delivery was partial.";
    if (!note) return "Say what happened to the units not delivered.";
    return null;
  }
  if ((stop.lateMinutes ?? 0) > 0 && !timingUncertain && !reason) return "This stop is late. Say why.";
  return null;
}

export type RunSummary = {
  total: number;
  finished: number;
  delivered: number;
  partial: number;
  failed: number;
  skipped: number;
  /** Finished stops, other than skipped ones, with no proof captured yet. */
  proofOwed: number;
};

/**
 * Units handed over across the finished stops: a full delivery is its order, a
 * partial one the stop total it was recorded with (on the server or still on
 * this phone), a failed or replanned stop none.
 */
export function unitsHandedOver(stops: Stop[]): { handed: number; ordered: number } {
  let handed = 0;
  for (const stop of stops) {
    if (stop.outcome === "DELIVERED") handed += stop.deliveredUnits ?? stop.itemCount;
    else if (stop.outcome === "PARTIAL") handed += stop.deliveredUnits ?? 0;
  }
  return { handed, ordered: stops.reduce((n, stop) => n + stop.itemCount, 0) };
}

export function summarize(stops: Stop[]): RunSummary {
  const count = (outcome: DeliveryOutcome) => stops.filter((stop) => stop.outcome === outcome).length;
  return {
    total: stops.length,
    finished: stops.filter(isFinished).length,
    delivered: count("DELIVERED"),
    partial: count("PARTIAL"),
    failed: count("FAILED"),
    skipped: count("SKIPPED"),
    proofOwed: stops.filter((stop) => (stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL") && !stop.proofCaptured).length,
  };
}

/** Today's date at the depots, for asking for today's run. */
export function operatingDate(now: Date): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo", year: "numeric", month: "2-digit", day: "2-digit" }).format(now);
}


/**
 * The run sheet this phone works from. A driver can be assigned several
 * vehicles for a day, most with no released trip: the one with a stop still to
 * do comes first, then one with any stops, then the first.
 */
export function todaysSheet(sheets: RunSheetView[]): RunSheetView | null {
  return (
    sheets.find((sheet) => sheet.stops.some((stop) => !isFinished(stop))) ??
    sheets.find((sheet) => sheet.stops.length > 0) ??
    sheets[0] ??
    null
  );
}

/** What every stop command answers with (ExecutionMessages.result on the server). */
export type StopAck = { deliveryId: string; rowVersion: number; outcome: DeliveryOutcome };

export function isStopAck(value: unknown): value is StopAck {
  const ack = value as Partial<StopAck> | null;
  return typeof ack?.deliveryId === "string" && typeof ack.rowVersion === "number" && typeof ack.outcome === "string";
}

/**
 * The sheets with a sent command's answer taken in, so the next write names the
 * version the server now holds even when reading the sheet back fails (EXE-29).
 * Never moves a stop backwards.
 */
export function acknowledged(sheets: RunSheetView[], ack: StopAck): RunSheetView[] {
  return sheets.map((sheet) => ({
    ...sheet,
    stops: sheet.stops.map((stop) =>
      stop.deliveryId === ack.deliveryId && ack.rowVersion > stop.rowVersion ? { ...stop, rowVersion: ack.rowVersion, outcome: ack.outcome } : stop,
    ),
  }));
}

/** A day has a run when one of its sheets has stops: a vehicle is listed before its trip is released. */
export const hasRun = (sheets: Array<{ stops: unknown[] }>): boolean => sheets.some((sheet) => sheet.stops.length > 0);

/** How far ahead the driver looks for a released trip when today has none. */
export const RUN_LOOK_AHEAD_DAYS = 7;

/** One day ahead as the phone sees it: a released run, or a published trip still at the dock. */
export type DayProbe = { released: boolean; waiting: { vehicleId: string; departure: string } | null };

/** The driver's next trip: on a released run (the phone switches to it) or waiting for the loader. */
export type NextRun = { date: string; released: boolean; vehicleId: string | null; departure: string | null };

/**
 * The first day after `today`, within a week, with a trip for this driver
 * (issues #114 and the deadline-day UX plan): a released run, which the phone
 * switches to, or a published trip the loader has not released yet, which Home
 * names. Every day is asked at once, so the answer takes one round trip rather
 * than seven, and a day that cannot be read is skipped.
 */
export async function lookAhead(today: string, probe: (date: string) => Promise<DayProbe>): Promise<NextRun | null> {
  const start = new Date(`${today}T00:00:00Z`);
  const days = Array.from({ length: RUN_LOOK_AHEAD_DAYS }, (_, i) => {
    const d = new Date(start);
    d.setUTCDate(d.getUTCDate() + i + 1);
    return d.toISOString().slice(0, 10);
  });
  const answers = await Promise.all(days.map((day) => probe(day).catch(() => null)));
  for (const [i, answer] of answers.entries()) {
    if (!answer) continue;
    if (answer.released) return { date: days[i]!, released: true, vehicleId: null, departure: null };
    if (answer.waiting) return { date: days[i]!, released: false, ...answer.waiting };
  }
  return null;
}

/** The run day this phone last followed, so a reload offline opens the same run. */
const RUN_DAY_KEY = (accountId: string) => `waypoint.driver.runDay.${accountId}`;

export function keptRunDay(accountId: string, today: string): string {
  try {
    const kept = window.localStorage.getItem(RUN_DAY_KEY(accountId));
    return kept && kept >= today ? kept : today;
  } catch {
    return today;
  }
}

export function keepRunDay(accountId: string, date: string): void {
  try {
    window.localStorage.setItem(RUN_DAY_KEY(accountId), date);
  } catch {
    // Storage unavailable: the run is found again online.
  }
}
