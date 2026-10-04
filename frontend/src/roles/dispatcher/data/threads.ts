"use client";

import { useCallback } from "react";
import { request } from "@shared/api/client";
import { ApiError } from "@shared/api/problem";
import type { RunSheetStopView, ThreadView } from "@shared/domain/types";
import type { ThreadDraft } from "@shared/ui";
import { useDispatcherInbox } from "../inbox.tsx";
import { etaOf, type Run } from "./liveDesk.ts";

// Live's ways into a trip's thread (issue #136): "Notify store", "Send an
// update", "Voice". Each opens the thread in the side sheet, written to the
// right person, so the dispatcher reads what was said before sending.

/** The trip a run is on now, or its last. */
export function tripOf(run: Run): string | null {
  return (run.day.current ?? run.day.stops[run.day.stops.length - 1])?.tripId ?? null;
}

/** "Running 12 min late · now expected 10:42", or "On the way · expected 10:30". */
export function updateText(next: RunSheetStopView | null, lateMinutes: number | null): string {
  if (!next) return "";
  return lateMinutes !== null && lateMinutes > 0 ? `Running ${lateMinutes} min late · now expected ${etaOf(next)}` : `On the way · expected ${etaOf(next)}`;
}

/** Minutes the stop is expected after its planned arrival, from the run sheet. */
export function lateBy(next: RunSheetStopView | null, date: string): number | null {
  if (!next?.expectedArrival) return null;
  const planned = new Date(`${date}T${next.plannedArrival.slice(0, 5)}:00+05:30`).getTime();
  return Math.round((new Date(next.expectedArrival).getTime() - planned) / 60_000);
}

export async function threadOfTrip(tripId: string): Promise<string | null> {
  try {
    return (await request<ThreadView>(`/api/threads/by-subject?type=trip&id=${encodeURIComponent(tripId)}`)).threadId;
  } catch (failure) {
    if (failure instanceof ApiError && failure.status === 404) return null;
    throw failure;
  }
}

/**
 * Opens a trip's thread with a draft. Answers null when it opened, else what to
 * say: the trip has no thread before its plan is published.
 */
export function useOpenTripThread(): (tripId: string | null, draft?: ThreadDraft) => Promise<string | null> {
  const inbox = useDispatcherInbox();
  return useCallback(
    async (tripId, draft) => {
      if (!tripId || !inbox) return "This trip has no messages yet.";
      try {
        const threadId = await threadOfTrip(tripId);
        if (!threadId) return "This trip has no messages yet. Its thread opens when the plan is published.";
        inbox.openThread({ threadId, ...(draft ? { draft } : {}) });
        return null;
      } catch (failure) {
        return failure instanceof Error ? failure.message : "Could not open the messages.";
      }
    },
    [inbox],
  );
}
