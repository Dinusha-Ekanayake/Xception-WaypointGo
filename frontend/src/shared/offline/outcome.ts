import type { OperationStatus } from "../domain/sync.ts";

/**
 * What the device does with a queued write once the server has answered for it.
 *
 * - `sent`: applied; only the server's confirmation lets a write leave the device.
 * - `dropped`: its owner discarded or redid it on the server; nothing is left to
 *   send, and keeping it would block every write queued behind it.
 * - `review`: a conflict or a refusal; resending cannot fix it and dropping it
 *   silently would lose the person's work, so a person decides.
 * - `wait`: recorded but not decided; the server stopped here to keep the order.
 */
export type OutcomeAction = "sent" | "dropped" | "review" | "wait";

export function outcomeAction(status: OperationStatus): OutcomeAction {
  switch (status) {
    case "APPLIED":
      return "sent";
    case "DISCARDED":
    case "RESOLVED":
      return "dropped";
    case "CONFLICT":
    case "REJECTED":
      return "review";
    default:
      return "wait";
  }
}
