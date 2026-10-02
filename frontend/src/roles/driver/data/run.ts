import type { Command } from "@shared/api/commands";
import type { DeliveryOutcome, RunSheetStopView, RunSheetView } from "@shared/domain/types";

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

/** HH:mm at the depots, from an instant or a time of day. */
export function clock(value: string | Date | null | undefined): string {
  if (!value) return "--:--";
  if (typeof value === "string" && /^\d{2}:\d{2}/.test(value)) return value.slice(0, 5);
  const date = typeof value === "string" ? new Date(value) : value;
  return new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Colombo", hour: "2-digit", minute: "2-digit", hour12: false }).format(date);
}
