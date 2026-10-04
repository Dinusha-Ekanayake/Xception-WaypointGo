import { useEffect, useSyncExternalStore } from "react";
import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { DemoView } from "@shared/domain/types";
import { clockOffsetMs, onClockOffset, setClockOffset } from "@shared/wording";

// Demo runtime for any role (issue #231). Null whenever demo mode is off, the
// read failed or nobody is signed in, so callers fall back to normal behaviour.
// Each answer also sets the business clock's offset (shared/wording/now.ts); a
// failed read keeps the last one, so a phone that goes offline stays on the demo day.
export function useDemo(pollMs = 15_000): DemoView | null {
  const demo = useResource((signal: AbortSignal) => request<DemoView>("/api/demo", { signal }), "demo-runtime", pollMs);
  const data = demo.data;
  useEffect(() => {
    if (data) setClockOffset(data.enabled ? data.offsetSeconds : 0);
  }, [data]);
  return data?.enabled ? data : null;
}

/**
 * The demo clock's offset in milliseconds, re-rendering when it changes. A
 * component that works out "today" once (in a memo or a state initialiser)
 * lists it as a dependency, so a moved demo clock moves that day too.
 */
export function useClockOffset(): number {
  return useSyncExternalStore(onClockOffset, clockOffsetMs, () => 0);
}

/** A map's refresh interval: quicker in demo mode so a simulated vehicle visibly moves. */
export function livePoll(demo: DemoView | null, normalMs: number): number {
  return demo ? Math.min(normalMs, 4_000) : normalMs;
}
