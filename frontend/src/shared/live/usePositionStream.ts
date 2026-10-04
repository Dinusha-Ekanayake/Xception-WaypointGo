"use client";

import { useEffect, useRef, useState } from "react";
import { request } from "@shared/api/client";
import { useOnline, useResource, type Resource } from "@shared/api/useResource";
import { livePoll, useDemo } from "@shared/demo/useDemo";
import type { VehiclePositionView } from "@shared/domain/types";

// Where the vehicles are, as they move (R-EXE-23). One event stream per depot
// (or outlet) on GET /api/execution/positions/stream, which the server feeds on
// connect, about a second after each new fix and every 20 s regardless; the
// browser reconnects it by itself. The same read is polled as a net: every
// minute while the stream speaks, every 15 s once it has been silent for 45 s,
// and then `paused` is true so the screen says live updates are paused (rule 9).

/** How long the stream may be silent before the map says updates are paused. */
export const PAUSED_AFTER_MS = 45_000;
const CHECK_MS = 5_000;
const NET_MS = 60_000;

export type PositionScope = { kind: "depot" | "outlet"; codes: string[] };

export type PositionStream = Resource<VehiclePositionView[]> & {
  /** Positions are arriving on the stream right now. */
  live: boolean;
  /** The stream has been silent too long: the map is on the slower poll and says so. */
  paused: boolean;
};

const path = (scope: PositionScope, code: string, date: string, stream: boolean) =>
  `/api/execution/positions${stream ? "/stream" : ""}?${scope.kind}=${encodeURIComponent(code)}&date=${encodeURIComponent(date)}`;

export function usePositionStream(scope: PositionScope, date: string, enabled = true): PositionStream {
  const online = useOnline();
  const demo = useDemo();
  const key = `${scope.kind}|${scope.codes.join(",")}|${date}`;
  const [byCode, setByCode] = useState<Record<string, VehiclePositionView[]>>({});
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [live, setLive] = useState(false);
  const [paused, setPaused] = useState(false);
  const heardAt = useRef(0);
  const active = enabled && scope.codes.length > 0;

  // A new depot, outlet or day starts empty: one day's vehicles are never drawn on another.
  useEffect(() => {
    setByCode({});
    setUpdatedAt(null);
  }, [key]);

  const polled = useResource(
    active
      ? async (signal: AbortSignal) =>
          Object.fromEntries(
            await Promise.all(
              scope.codes.map(async (code) => [code, await request<VehiclePositionView[]>(path(scope, code, date, false), { signal })] as const),
            ),
          )
      : null,
    `positions|${key}`,
    active ? (live ? NET_MS : livePoll(demo, 15_000)) : 0,
  );

  useEffect(() => {
    if (!polled.data || !polled.loadedAt) return;
    setByCode((before) => ({ ...before, ...polled.data }));
    setUpdatedAt(polled.loadedAt);
  }, [polled.data, polled.loadedAt]);

  useEffect(() => {
    if (!active || !online || typeof EventSource === "undefined") {
      setLive(false);
      return;
    }
    heardAt.current = Date.now();
    setPaused(false);
    const sources = scope.codes.map((code) => {
      const source = new EventSource(path(scope, code, date, true));
      source.addEventListener("positions", (event) => {
        try {
          const positions = JSON.parse((event as MessageEvent<string>).data) as VehiclePositionView[];
          heardAt.current = Date.now();
          setByCode((before) => ({ ...before, [code]: positions }));
          setUpdatedAt(new Date());
          setLive(true);
          setPaused(false);
        } catch {
          // A malformed event is ignored; the next one, or the poll, corrects the map.
        }
      });
      return source;
    });
    // A dropped connection is not yet "paused": EventSource reconnects by itself.
    const watch = window.setInterval(() => {
      if (Date.now() - heardAt.current < PAUSED_AFTER_MS) return;
      setLive(false);
      setPaused(true);
    }, CHECK_MS);
    return () => {
      sources.forEach((s) => s.close());
      window.clearInterval(watch);
    };
    // Keyed on the scope and day, not the array identity a parent re-creates each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active, online, key]);

  return {
    data: active && updatedAt ? scope.codes.flatMap((code) => byCode[code] ?? []) : null,
    // A failed poll is only an error when the stream is not carrying the map either.
    error: live ? null : polled.error,
    loading: polled.loading && updatedAt === null,
    loadedAt: updatedAt,
    refresh: polled.refresh,
    live,
    paused: paused && online,
  };
}
