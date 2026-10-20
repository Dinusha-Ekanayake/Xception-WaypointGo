import type { OperationStatus } from "../domain/sync.ts";

/**
 * What the device does with a queued write once the server has answered for it.
 *
 * - `sent`: applied; only the server's confirmation lets a write leave the device.
 * - `dropped`: its owner discarded or redid it on the server; nothing is left to
 *   send, and keeping it would block every write queued behind it.
 * - `review`: a conflict or a refusal; resending cannot fix it and dropping it
 *   silently would lose the person's work, so a person decides. A refused batch
 *   of positions is no one's work and is dropped instead.
 * - `wait`: recorded but not decided; the server stopped here to keep the order.
 */
export type OutcomeAction = "sent" | "dropped" | "review" | "wait";

/** The position command (lanes.ts POSITION_KIND); repeated here so this file stays dependency free. */
const POSITION_KIND = "delivery:RecordPositions";

export function outcomeAction(status: OperationStatus, kind?: string): OutcomeAction {
  switch (status) {
    case "APPLIED":
      return "sent";
    case "DISCARDED":
    case "RESOLVED":
      return "dropped";
    case "CONFLICT":
    case "REJECTED":
      // A refused batch of positions is an observation, not a person's work:
      // nothing can be redone and nobody has a decision to make, so it leaves
      // the device (the server counts it, waypoint.execution.positions_rejected).
      return kind === POSITION_KIND ? "dropped" : "review";
    default:
      return "wait";
  }
}
