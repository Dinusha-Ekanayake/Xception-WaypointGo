"use client";

import { useCallback, useState } from "react";
import { newCommand, type CommandAck } from "@shared/api/commands";
import { ApiError, friendlyError } from "@shared/api/problem";
import type { StoreGateway } from "./gateway.ts";

// Every store write. The store manager is on the resilient tier: offline, or
// when a send fails on the network, the command is kept on this device and
// sent when the connection returns, so a dropped connection never loses an
// order. A rule rejection (short stock, a stale version, a closed order) is
// returned to the screen, never queued.

export type Outcome =
  | { ok: true; queued: false; ack: CommandAck }
  | { ok: true; queued: true }
  | { ok: false; error: ApiError | Error };

export function useCommands(gateway: StoreGateway, online: boolean, onQueued: () => void, onSent: () => void) {
  const [busy, setBusy] = useState(false);

  const run = useCallback(
    async (kind: string, payload: unknown, expectedVersion: number | null): Promise<Outcome> => {
      const command = newCommand(kind, payload, expectedVersion);
      const keep = async (): Promise<Outcome> => {
        const kept = await gateway.queue(command);
        if (!kept.durable) return { ok: false, error: new Error(`Not saved on this device: ${kept.reason ?? "storage unavailable"}`) };
        onQueued();
        return { ok: true, queued: true };
      };
      if (!online) return keep();
      setBusy(true);
      try {
        const ack = await gateway.send(command);
        onSent();
        return { ok: true, queued: false, ack };
      } catch (failure) {
        if (!(failure instanceof ApiError) || failure.isRetryable) return keep();
        return { ok: false, error: failure };
      } finally {
        setBusy(false);
      }
    },
    [gateway, online, onQueued, onSent],
  );

  return { run, busy };
}

export function conflictMessage(error: ApiError | Error): string {
  return error instanceof ApiError && error.isVersionConflict ? "This order changed since you opened it. Review it and try again." : friendlyError(error);
}
