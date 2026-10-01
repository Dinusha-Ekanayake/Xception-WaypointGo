"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { newCommand } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import { useResource, type Resource } from "@shared/api/useResource";
import {
  LoadingCommandKind,
  type CheckStatus,
  type FlagShortfall,
  type IssueKind,
  type ManifestLineView,
  type ManifestView,
  type ReleaseTrip,
} from "@shared/domain/types";
import type { LoadingGateway } from "./gateway.ts";

// One trip's load sheet and every write against it.
//
// - Writes carry the manifest's rowVersion; a stale one is a conflict, never a merge.
// - The loader tier is resilient: offline, or when a send fails on the network,
//   the command is kept on this device and shown as waiting, then sent in order
//   when the connection returns. A rule rejection is shown, never queued.
// - A new plan version under the loader (R-LOD-03) is a visible state that must
//   be acknowledged, and the orders it reset are marked for recheck.

export type Line = ManifestLineView & { waiting: boolean; recheck: boolean };

export type Outcome = { ok: true; queued: boolean } | { ok: false; error: ApiError | Error };

export type Trip = {
  manifest: Resource<ManifestView>;
  lines: Line[];
  busy: boolean;
  error: ApiError | Error | null;
  clearError: () => void;
  /** The plan version the loader started on, when a newer one has arrived. */
  planChangedFrom: number | null;
  acknowledgePlan: () => void;
  start: () => Promise<Outcome>;
  check: (line: ManifestLineView, status: CheckStatus) => Promise<Outcome>;
  flag: (payload: Omit<FlagShortfall, "tripId">) => Promise<Outcome>;
  release: (checklist: Omit<ReleaseTrip, "tripId">) => Promise<Outcome>;
  handBack: () => Promise<Outcome>;
};

/**
 * @param waiting writes kept on this device, counted by the role container,
 *   which also sends them when the connection returns so they survive leaving
 *   this screen.
 * @param onQueued called once per write kept on the device.
 */
export function useTrip(
  gateway: LoadingGateway,
  tripId: string,
  online: boolean,
  waiting: number,
  onQueued: () => void,
  actingUserId: string,
): Trip {
  const manifest = useResource((signal) => gateway.manifest(tripId, signal), `${tripId}`, 15_000);
  const [baseline, setBaseline] = useState<number | null>(null);
  const [local, setLocal] = useState<Record<string, Pick<ManifestLineView, "status" | "loadedUnits">>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const expected = useRef<number | null>(null);
  const { data, refresh } = manifest;

  useEffect(() => {
    if (!data) return;
    // While writes wait on this device, the local view stays authoritative.
    if (waiting === 0) {
      expected.current = data.rowVersion;
      setLocal({});
    }
    setBaseline((b) => b ?? data.planVersion);
  }, [data, waiting]);

  const run = useCallback(
    async (kind: string, payload: unknown, optimistic?: () => void): Promise<Outcome> => {
      const command = newCommand(kind, payload, expected.current, actingUserId);
      const keep = async (): Promise<Outcome> => {
        const kept = await gateway.queue(command);
        if (!kept.durable) {
          const failure = new Error(`Not saved on this device: ${kept.reason ?? "storage unavailable"}`);
          setError(failure);
          return { ok: false, error: failure };
        }
        optimistic?.();
        // Each accepted command moves the version on by one.
        if (expected.current !== null) expected.current++;
        onQueued();
        return { ok: true, queued: true };
      };
      if (!online) return keep();

      setBusy(true);
      optimistic?.();
      try {
        const ack = await gateway.send(command);
        const rowVersion = (ack.result as { rowVersion?: unknown } | null)?.rowVersion;
        if (typeof rowVersion === "number") expected.current = rowVersion;
        refresh();
        return { ok: true, queued: false };
      } catch (failure) {
        if (!(failure instanceof ApiError) || failure.isRetryable) return keep();
        setLocal({});
        refresh();
        setError(failure);
        return { ok: false, error: failure };
      } finally {
        setBusy(false);
      }
    },
    [gateway, online, refresh, onQueued, actingUserId],
  );

  // Once the container has sent what waited, read the server's view again.
  useEffect(() => {
    if (waiting === 0) refresh();
  }, [waiting, refresh]);

  const lines = useMemo<Line[]>(() => {
    if (!data) return [];
    return data.lines.map((line) => {
      const override = local[line.orderId];
      return {
        ...line,
        ...override,
        waiting: override !== undefined && waiting > 0,
        recheck: line.attempt > 1 && (override?.status ?? line.status) === "PENDING",
      };
    });
  }, [data, local, waiting]);

  const setLine = (orderId: string, status: CheckStatus, loadedUnits: number) => () =>
    setLocal((all) => ({ ...all, [orderId]: { status, loadedUnits } }));

  return {
    manifest,
    lines,
    busy,
    error,
    clearError: () => setError(null),
    planChangedFrom: data && baseline !== null && data.planVersion > baseline ? baseline : null,
    acknowledgePlan: () => data && setBaseline(data.planVersion),
    start: () => run(LoadingCommandKind.start, { tripId }),
    check: (line, status) => {
      const loadedUnits = status === "LOADED" ? line.itemCount : 0;
      return run(
        LoadingCommandKind.check,
        { tripId, orderId: line.orderId, status, loadedUnits, reason: null },
        setLine(line.orderId, status, loadedUnits),
      );
    },
    flag: (payload) => {
      const line = data?.lines.find((l) => l.orderId === payload.orderId);
      const loaded = line ? Math.max(0, line.itemCount - payload.missingUnits) : 0;
      return run(LoadingCommandKind.shortfall, { ...payload, tripId }, setLine(payload.orderId, payload.kind, loaded));
    },
    release: async (checklist) => {
      // Release needs the server's answer; it is never queued.
      if (!online) {
        const failure = new Error("Release needs a connection, so the dispatcher sees it before the vehicle leaves.");
        return { ok: false, error: failure };
      }
      setBusy(true);
      try {
        const ack = await gateway.send(newCommand(LoadingCommandKind.release, { tripId, ...checklist }, expected.current, actingUserId));
        const rowVersion = (ack.result as { rowVersion?: unknown } | null)?.rowVersion;
        if (typeof rowVersion === "number") expected.current = rowVersion;
        refresh();
        return { ok: true, queued: false };
      } catch (failure) {
        return { ok: false, error: failure instanceof Error ? failure : new Error(String(failure)) };
      } finally {
        setBusy(false);
      }
    },
    handBack: () => run(LoadingCommandKind.handBack, { tripId }),
  };
}
