"use client";

import { useEffect, useState } from "react";
import { request } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";
import type { ReceiptAnswerView } from "@shared/domain/types";

// Issue #21, store-led handover: after handing over, the phone waits for the
// store to check the load and answer. The answer is read, never queued: offline
// there is nothing to read, and the screen says so and lets the driver move on.

/** How often the waiting screen asks whether the store has answered. */
export const ANSWER_POLL_MS = 10_000;

export type StoreAnswer =
  /** Not asked yet, or asking for the first time. */
  | { state: "checking" }
  /** The store has not answered yet (the server says 404 until it does). */
  | { state: "waiting" }
  | { state: "answered"; answer: ReceiptAnswerView }
  /** The phone cannot ask: no signal, or the server is not answering. */
  | { state: "unreachable" };

/** One read: the answer, null while the store has not answered. */
export async function readStoreAnswer(orderId: string, signal?: AbortSignal): Promise<ReceiptAnswerView | null> {
  try {
    return await request<ReceiptAnswerView>(`/api/receipts/${encodeURIComponent(orderId)}/answer`, signal ? { signal } : {});
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return null;
    throw failure;
  }
}

/**
 * Polls the store's answer for a handed-over order while `active`. Stops once
 * the answer is in and its PIN settled (accepted or locked), since nothing more
 * will change that the driver needs to see.
 */
export function useStoreAnswer(orderId: string | null, active: boolean, online: boolean, pollMs: number = ANSWER_POLL_MS): StoreAnswer & { refresh: () => void } {
  const [answer, setAnswer] = useState<StoreAnswer>({ state: "checking" });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    setAnswer({ state: "checking" });
  }, [orderId]);

  useEffect(() => {
    if (!orderId || !active) return;
    if (!online) {
      setAnswer((current) => (current.state === "answered" ? current : { state: "unreachable" }));
      return;
    }
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | null = null;
    let stopped = false;
    const ask = async () => {
      try {
        const found = await readStoreAnswer(orderId, controller.signal);
        if (stopped) return;
        setAnswer(found ? { state: "answered", answer: found } : { state: "waiting" });
        const settled = found !== null && found.handover.status !== "AWAITING";
        if (!settled) timer = setTimeout(() => void ask(), pollMs);
      } catch {
        if (stopped) return;
        setAnswer((current) => (current.state === "answered" ? current : { state: "unreachable" }));
        timer = setTimeout(() => void ask(), pollMs);
      }
    };
    void ask();
    return () => {
      stopped = true;
      controller.abort();
      if (timer) clearTimeout(timer);
    };
  }, [orderId, active, online, pollMs, tick]);

  return { ...answer, refresh: () => setTick((n) => n + 1) };
}
