import { request } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";
import { setBeforeDrain } from "@shared/offline";
import {
  clearOfflineSwitches,
  keepCrew,
  offlineSwitches,
  type CrewList,
  type CrewMember,
} from "./offlinePin.ts";
import type { Operator } from "./session.ts";

export type { CrewMember };

/** The crew list, kept on the device so a loader can switch with their PIN offline. */
export async function crew(account: string): Promise<CrewList> {
  const list = await request<CrewList>("/api/session/crew");
  keepCrew(account, list);
  return list;
}

/**
 * Sends switches made offline, oldest first, so the server's operator history
 * covers the work queued under them. Must run before that work syncs, and
 * before any online switch or lock, which would otherwise come first in history.
 */
export async function replayOfflineSwitches(account: string): Promise<void> {
  const switches = offlineSwitches(account);
  if (switches.length === 0) return;
  try {
    await request<{ operator: Operator | null }>("/api/session/operator/offline", {
      method: "POST",
      body: { switches },
    });
  } catch (failure) {
    // A refusal is final (R-IAM-27): drop the switches so the queue is not stuck
    // behind them. Work recorded under them is then refused by the server and
    // held on screen for review, which is visible; a stuck queue is not.
    if (!(failure instanceof ApiError) || failure.status >= 500 || [401, 408, 429].includes(failure.status)) throw failure;
  }
  clearOfflineSwitches(account);
}

/** Queued work is sent only after the switches it was recorded under. */
export function replayBeforeSync(account: string): () => void {
  return setBeforeDrain(account, () => replayOfflineSwitches(account));
}

export async function switchOperator(account: string, userId: string, pin: string): Promise<Operator> {
  await replayOfflineSwitches(account);
  return request<Operator>("/api/session/operator", {
    method: "POST",
    body: { userId, pin },
  });
}

export async function lockOperator(account: string): Promise<void> {
  await replayOfflineSwitches(account);
  await request<null>("/api/session/operator", { method: "DELETE" });
}
