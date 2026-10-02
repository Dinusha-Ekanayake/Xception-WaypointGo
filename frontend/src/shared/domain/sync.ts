import type { Command } from "../api/commands.ts";
import type { IsoInstant, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.sync.contract.

/** RESOLVED: redone on the current version by its owner; the redo is another operation. */
export type OperationStatus = "RECEIVED" | "APPLIED" | "CONFLICT" | "REJECTED" | "DISCARDED" | "RESOLVED";

export type OperationView = {
  /** The command id, so a replay is recognised. */
  operationId: Uuid;
  deviceId: Uuid;
  /** Per device, so operations apply in the order they were recorded. */
  sequence: number;
  kind: string;
  status: OperationStatus;
  problemCode: string | null;
  baseRowVersion: number | null;
  currentRowVersion: number | null;
  receivedAt: IsoInstant;
  appliedAt: IsoInstant | null;
};

export const SyncCommandKind = {
  submit: "sync:Submit",
  acknowledge: "sync:Acknowledge",
  discard: "sync:Discard",
  resolve: "sync:Resolve",
} as const;

export type SubmittedOperation = { sequence: number; command: Command };

/** The body of POST /api/sync. */
export type SubmitBatch = { deviceId: Uuid; operations: SubmittedOperation[] };
export type AcknowledgeOperation = { operationId: Uuid };
export type DiscardOperation = { operationId: Uuid; reason: string };
/** Names the redo the device queued ahead of it, on the current version. */
export type ResolveOperation = { operationId: Uuid; replacedBy: Uuid };

/**
 * One line per operation the server reached, in sequence order. An operation
 * missing from `results` was not reached and stays on the device. `RECEIVED`
 * means recorded but not decided: send it again later.
 */
export type OperationOutcome = {
  operationId: Uuid;
  sequence: number;
  status: OperationStatus;
  problemCode: string | null;
  detail: string | null;
  replayed: boolean;
  /** The operation's version after this answer, which a discard or resolve names. */
  rowVersion: number | null;
};

/** The answer to POST /api/sync. */
export type SyncAck = { results: OperationOutcome[] };

/** GET /api/sync?since=, keyset paginated. */
export type OperationPage = { operations: OperationView[]; nextCursor: string | null };
