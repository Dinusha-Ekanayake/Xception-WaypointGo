"use client";

import { useCallback, useRef, useState } from "react";
import { newCommand, send, type Command } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";

// One command through POST /api/commands. A retry after a network failure or
// an outage resends the same command id, so the server answers from its receipt
// instead of applying the change twice; a rule refusal forgets the command, so
// a corrected one goes out under a fresh id. The dispatcher is online only:
// nothing is queued.

export type Sent<T> = { ok: true; result: T } | { ok: false; error: ApiError | Error };

export function useCommand(): {
  busy: boolean;
  run: <T>(kind: string, payload: unknown, expectedVersion: number | null) => Promise<Sent<T>>;
} {
  const [busy, setBusy] = useState(false);
  const pending = useRef<{ key: string; command: Command } | null>(null);

  const run = useCallback(async <T,>(kind: string, payload: unknown, expectedVersion: number | null): Promise<Sent<T>> => {
    const key = JSON.stringify([kind, payload, expectedVersion]);
    const command = pending.current?.key === key ? pending.current.command : newCommand(kind, payload, expectedVersion);
    pending.current = { key, command };
    setBusy(true);
    try {
      const ack = await send<T>(command);
      pending.current = null;
      return { ok: true, result: ack.result };
    } catch (failure) {
      if (failure instanceof ApiError && !failure.isRetryable) pending.current = null;
      return { ok: false, error: failure instanceof Error ? failure : new Error(String(failure)) };
    } finally {
      setBusy(false);
    }
  }, []);

  return { busy, run };
}
