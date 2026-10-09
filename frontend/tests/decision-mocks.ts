import type { AnswerDecision, DecisionView } from "../src/shared/domain/issues.ts";

// FF-09: live decisions as the role suites mock them. The suites' own mocks.ts
// answer /api/issues/decisions with the list and apply the commands here.

export function decisionOf(over: Partial<DecisionView> & Pick<DecisionView, "requestId" | "deliveryId" | "outletId">): DecisionView {
  return {
    kind: "receive_late", depotCode: "Kandy", serviceDate: "2027-03-01", tripId: "trip-1", vehicleId: "VEH043",
    aboutDeliveryId: null, aboutOutletId: null, aboutUnits: null, receiveAt: new Date(Date.now() + 70 * 60_000).toISOString(),
    arrivalAt: null, deadline: new Date(Date.now() + 15 * 60_000).toISOString(), state: "sent", answer: null, answeredVia: null,
    answeredAt: null, answeredLate: false, reason: null, voiceNoteId: null, askedAt: new Date().toISOString(), rowVersion: 1, askedBySystem: false, ...over,
  };
}

/** Applies issue:AskDecision as the server would, from the stop the dispatcher chose. */
export function askInMock(
  decisions: DecisionView[],
  payload: { kind: DecisionView["kind"]; deliveryId: string; receiveAt?: string; aboutDeliveryId?: string },
  stop: { outletId: string; tripId: string; vehicleId: string },
  about: { outletId: string; units: number } | null,
  /** The page's time: a suite that installs a clock passes it, so the deadline is ahead of it. */
  now: Date = new Date(),
): unknown {
  for (const d of decisions) {
    if (d.deliveryId === payload.deliveryId && d.kind === payload.kind && d.state === "sent") d.state = "withdrawn";
  }
  const requestId = `asked-${decisions.length + 1}`;
  decisions.push(decisionOf({
    requestId, kind: payload.kind, deliveryId: payload.deliveryId, outletId: stop.outletId, tripId: stop.tripId, vehicleId: stop.vehicleId,
    receiveAt: payload.receiveAt ?? null, aboutDeliveryId: payload.aboutDeliveryId ?? null, aboutOutletId: about?.outletId ?? null,
    aboutUnits: about?.units ?? null, askedAt: now.toISOString(), deadline: new Date(now.getTime() + 15 * 60_000).toISOString(),
  }));
  return { requestId };
}

/** Applies issue:AnswerDecision as the server would: one answer, by app or phone. */
export function answerInMock(decisions: DecisionView[], payload: AnswerDecision): unknown {
  const d = decisions.find((x) => x.requestId === payload.requestId);
  if (!d) return {};
  Object.assign(d, {
    state: "answered", answer: payload.answer, answeredVia: payload.byPhone ? "phone" : "app", answeredAt: new Date().toISOString(),
    reason: payload.reason ?? null, voiceNoteId: payload.voiceNoteId ?? null, rowVersion: d.rowVersion + 1,
  });
  return { requestId: d.requestId, answer: d.answer, late: false, rowVersion: d.rowVersion };
}
