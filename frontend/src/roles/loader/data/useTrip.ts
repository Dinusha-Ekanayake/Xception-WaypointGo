"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { newCommand } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import { useResource, type Resource } from "@shared/api/useResource";
import {
  LoadingCommandKind,
  type CheckStatus,
  type FlagShortfall,
  type ItemView,
  type ManifestLineView,
  type ManifestView,
  type ReleaseTrip,
} from "@shared/domain/types";
import type { LoadingGateway } from "./gateway.ts";
import { itemKey, orderStatusOf } from "./manifest.ts";

// One trip's load sheet and every write against it.
//
// - Writes carry the manifest's rowVersion; a stale one is a conflict, never a merge.
// - The loader tier is resilient: offline, or when a send fails on the network,
//   the command is kept on this device and shown as waiting, then sent in order
//   when the connection returns. A rule rejection is shown, never queued.
// - A new plan version under the loader (R-LOD-03) is a visible state that must
//   be acknowledged, and the orders it reset are marked for recheck.
// - Loading is item by item (decision 2026-10-01). An order's state is derived
//   from its items with the same rule the server applies.

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
  /** Tick or untick one item, or with no item every unchecked item of the order. */
  check: (line: ManifestLineView, item: ItemView | null, status: "LOADED" | "PENDING") => Promise<Outcome>;
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
  const manifest = useResource((signal) => gateway.manifest(tripId, signal), `${tripId}`, 2_000);
  const [baseline, setBaseline] = useState<number | null>(null);
  const [local, setLocal] = useState<Record<string, Pick<ItemView, "status" | "loadedUnits">>>({});
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
      let touched = false;
      const items = line.items.map((item) => {
        const override = local[itemKey(line.orderId, item.lineNo)];
        if (!override) return item;
        touched = true;
        return { ...item, ...override };
      });
      const status = items.length > 0 ? orderStatusOf(items) : line.status;
      return {
        ...line,
        items,
        status,
        loadedUnits: items.length > 0 ? items.reduce((n, i) => n + i.loadedUnits, 0) : line.loadedUnits,
        waiting: touched && waiting > 0,
        recheck: line.attempt > 1 && status === "PENDING",
      };
    });
  }, [data, local, waiting]);

  /** Optimistic view of the items a write changes, kept until the server answers or the queue drains. */
  const setItems = (orderId: string, changes: { lineNo: number; status: CheckStatus; loadedUnits: number }[]) => () =>
    setLocal((all) => {
      const next = { ...all };
      for (const c of changes) next[itemKey(orderId, c.lineNo)] = { status: c.status, loadedUnits: c.loadedUnits };
      return next;
    });

  const current = (orderId: string) => lines.find((l) => l.orderId === orderId);

  return {
    manifest,
    lines,
    busy,
    error,
    clearError: () => setError(null),
    planChangedFrom: data && baseline !== null && data.planVersion > baseline ? baseline : null,
    acknowledgePlan: () => data && setBaseline(data.planVersion),
    start: () => run(LoadingCommandKind.start, { tripId }),
    check: (line, item, status) => {
      const items = current(line.orderId)?.items ?? line.items;
      // Same targets as the server: one item, or every item of the order still in the
      // opposite state. A whole-order tick never clears a flag.
      const targets = item
        ? [item]
        : items.filter((i) => (status === "LOADED" ? i.status === "PENDING" : i.status === "LOADED"));
      const loadedUnits = item ? (status === "LOADED" ? item.units : 0) : targets.reduce((n, i) => n + (status === "LOADED" ? i.units : 0), 0);
      return run(
        LoadingCommandKind.check,
        { tripId, orderId: line.orderId, lineNo: item?.lineNo ?? null, status, loadedUnits, reason: null },
        setItems(line.orderId, targets.map((i) => ({ lineNo: i.lineNo, status, loadedUnits: status === "LOADED" ? i.units : 0 }))),
      );
    },
    flag: (payload) => {
      const items = current(payload.orderId)?.items ?? [];
      const changes =
        payload.lineNo !== null
          ? items
              .filter((i) => i.lineNo === payload.lineNo)
              .map((i) => ({ lineNo: i.lineNo, status: payload.kind as CheckStatus, loadedUnits: Math.max(0, i.units - payload.missingUnits) }))
          : items
              .filter((i) => i.status === "PENDING" || i.status === "LOADED")
              .map((i) => ({ lineNo: i.lineNo, status: payload.kind as CheckStatus, loadedUnits: 0 }));
      return run(LoadingCommandKind.shortfall, { ...payload, tripId }, setItems(payload.orderId, changes));
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
