import { request } from "./client.ts";

// Every mutation is idempotent and versioned: a client-generated command id plus
// the version the client believed it was changing. A replay returns the original
// result; a stale version is rejected rather than merged.

export type Command<TPayload = unknown> = {
  commandId: string;
  kind: string;
  expectedVersion: number | null;
  payload: TPayload;
  /** When the device recorded it. Server time decides; this is kept for forensics. */
  clientRecordedAt: string;
};

export function newCommand<TPayload>(
  kind: string,
  payload: TPayload,
  expectedVersion: number | null = null,
): Command<TPayload> {
  return {
    commandId: globalThis.crypto?.randomUUID?.() ?? `cmd-${Date.now()}-${Math.random()}`,
    kind,
    expectedVersion,
    payload,
    clientRecordedAt: new Date().toISOString(),
  };
}

/**
 * What the server answers with. `replayed` is true when a stored receipt answered
 * instead of the handler running again, so a queued write that was already
 * accepted before the connection dropped reads as "done" rather than "done just
 * now" and never as a second execution.
 */
export type CommandAck<TResult = unknown> = {
  commandId: string;
  kind: string;
  replayed: boolean;
  result: TResult;
};

export async function send<TResult>(command: Command): Promise<CommandAck<TResult>> {
  return request<CommandAck<TResult>>("/api/commands", { method: "POST", body: command });
}
