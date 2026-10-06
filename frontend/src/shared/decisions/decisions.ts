import type { DecisionView } from "../domain/issues.ts";
import type { RunSheetStopView } from "../domain/execution.ts";
import { clock } from "../wording/time.ts";
import { units } from "../wording/terms.ts";

// Live decisions (roadmap FF-09, R-ISS-12 to R-ISS-14): the dispatcher asks one
// store one question on a live trip and the store taps Yes or No. The pure half,
// shared by both screens: the words, every name and number from the record; the
// times a store may be asked about; which question is still open.

/** English text is the key; `{name}` marks a value, so each role translates it. */
export type Words = { text: string; vars: Record<string, string> };

/** The question as the store reads it. */
export function question(d: Pick<DecisionView, "kind" | "receiveAt" | "aboutOutletId" | "aboutUnits">): Words {
  if (d.kind === "receive_late") {
    return { text: "Can someone receive the delivery at about {time}?", vars: { time: clock(d.receiveAt) } };
  }
  return {
    text: "Goods for {outlet} ({units}) are in front of yours. Can the crew unload them at your dock to reach your goods, and reload them?",
    vars: { outlet: d.aboutOutletId ?? "", units: units(d.aboutUnits ?? 0) },
  };
}

/** The questions the store still has to answer: sent or past the deadline, with no answer; most urgent first. */
export function toAnswer(decisions: DecisionView[]): DecisionView[] {
  return decisions
    .filter((d) => d.answer === null && (d.state === "sent" || d.state === "expired"))
    .sort((a, b) => a.deadline.localeCompare(b.deadline));
}

/** The latest question about a stop, of a kind: what the dispatcher's card shows. */
export function latestFor(decisions: DecisionView[], deliveryId: string, kind: DecisionView["kind"]): DecisionView | null {
  return (
    decisions
      .filter((d) => d.deliveryId === deliveryId && d.kind === kind)
      .sort((a, b) => b.askedAt.localeCompare(a.askedAt))[0] ?? null
  );
}

/** How the question stands, for the dispatcher. */
export function standing(d: DecisionView, now: Date = new Date()): { text: string; tone: "waiting" | "yes" | "no" | "call" | "neutral" } {
  if (d.state === "withdrawn") return { text: "Asked again", tone: "neutral" };
  if (d.answer !== null) {
    const how = d.answeredVia === "phone" ? " by phone" : "";
    const late = d.answeredLate ? " · after the deadline" : "";
    return d.answer === "yes"
      ? { text: `${d.outletId} said Yes${how} · ${clock(d.answeredAt)}${late}`, tone: "yes" }
      : { text: `${d.outletId} said No${how} · ${clock(d.answeredAt)}${late}`, tone: "no" };
  }
  if (d.state === "expired" || new Date(d.deadline).getTime() < now.getTime()) {
    return { text: `No answer from ${d.outletId} by ${clock(d.deadline)}: call the store`, tone: "call" };
  }
  return { text: `Asked ${d.outletId} · answer by ${clock(d.deadline)}`, tone: "waiting" };
}

const FIVE_MINUTES = 5 * 60_000;
const LATEST_MS = 4 * 60 * 60_000;

/**
 * The times a store may be asked to receive at: the expected arrival rounded up
 * to five minutes, and 15 and 30 minutes after it, inside the next four hours
 * (R-ISS-12). With no expected arrival, from now.
 */
export function receiveTimes(expectedArrival: string | null, now: Date): Date[] {
  const from = Math.max(expectedArrival ? new Date(expectedArrival).getTime() : now.getTime(), now.getTime() + FIVE_MINUTES);
  const first = Math.ceil(from / FIVE_MINUTES) * FIVE_MINUTES;
  return [0, 15, 30]
    .map((m) => new Date(first + m * 60_000))
    .filter((t) => t.getTime() <= now.getTime() + LATEST_MS);
}

/**
 * The store to ask for help at its dock when a stop's goods stay on the vehicle:
 * the next stop of the same trip still to be done (loaded last stop first,
 * R-LOD-08). Null when there is none.
 */
export function dockHelper(stops: RunSheetStopView[], blocked: RunSheetStopView): RunSheetStopView | null {
  return (
    stops
      .filter((s) => s.tripId === blocked.tripId && s.sequence > blocked.sequence && (s.outcome === "PENDING" || s.outcome === "ARRIVED"))
      .sort((a, b) => a.sequence - b.sequence)[0] ?? null
  );
}
