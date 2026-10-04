import { request } from "@shared/api/client";
import { useResource } from "@shared/api/useResource";
import type { DemoView } from "@shared/domain/types";

// Demo runtime for any role (issue #231). Null whenever demo mode is off, the
// read failed or nobody is signed in, so callers fall back to normal behaviour.
export function useDemo(pollMs = 15_000): DemoView | null {
  const demo = useResource((signal: AbortSignal) => request<DemoView>("/api/demo", { signal }), "demo-runtime", pollMs);
  return demo.data?.enabled ? demo.data : null;
}

/** A map's refresh interval: quicker in demo mode so a simulated vehicle visibly moves. */
export function livePoll(demo: DemoView | null, normalMs: number): number {
  return demo ? Math.min(normalMs, 4_000) : normalMs;
}
