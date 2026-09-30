import type { Command } from "../api/commands.ts";
import type { IsoInstant, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.sync.contract.

export type OperationStatus = "RECEIVED" | "APPLIED" | "CONFLICT" | "REJECTED" | "DISCARDED";

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
export type ResolveOperation = { operationId: Uuid; expectedVersion: number };
