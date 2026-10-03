"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { newCommand, type Command } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import { drainUploads, pendingEntries, pendingUploads, UPLOADS_EVENT, type StoredEntry, type StoredUpload } from "@shared/offline";
import type { OutletView, VehicleView } from "@shared/domain/types";
import type { DriverGateway, RunData } from "./gateway.ts";
import { DeliveryKind, hasRun, keepRunDay, keptRunDay, nextRunDay, operatingDate, pickSheet, project, type Stop } from "./run.ts";

// The driver's day: read from the server when it answers, from the phone when it
// does not, and shown with the writes still waiting applied on top.
//
// - A write is sent at once only when nothing is waiting and the phone is
//   online; otherwise it joins the queue behind what is already there, so
//   nothing overtakes. Either way the driver is told "saved" only once the phone
//   holds it durably or the server has taken it (EXE-01).
// - A rule refusal on a write sent at once is shown. A network failure, an
//   outage or an expired session is not a refusal: the write is kept (SEC-01).
// - The server's clock decides every time (R-EXE-10). What the phone shows as
//   waiting or late for a queued arrival is its own reading until the server answers.

export type Outcome = { ok: true; queued: boolean } | { ok: false; error: Error };

export type Run = {
  date: string;
  loading: boolean;
  /** Nothing from the server and nothing kept: the phone has never seen today. */
  unavailable: boolean;
  /** Showing the copy kept on this phone, read at this time. */
  keptAt: Date | null;
  syncedAt: Date | null;
  /** The server answered that nobody is signed in: the queue is kept, and sent after signing in again (SEC-01). */
  expired: boolean;
  vehicle: VehicleView | null;
  stops: Stop[];
  outlets: Record<string, OutletView>;
  /** Proof artifacts still on this phone, by the stop they belong to. */
  uploadsWaiting: StoredUpload[];
  busy: boolean;
  refresh: () => void;
  act: (kind: string, payload: Record<string, unknown>, stop?: Stop) => Promise<Outcome>;
  saveArtifact: DriverGateway["saveArtifact"];
};

/**
 * @param queueState changes whenever the sync engine sent, kept or had a write
 *     refused; the queue is then read again and, once nothing is in flight,
 *     the server's copy too. A refusal changes no count, so a count alone
 *     would leave a refused write drawn as if it had happened (EXE-03).
 */
export function useRun(gateway: DriverGateway, accountId: string, online: boolean, queueState: string, onQueued: () => void): Run {
  const today = useMemo(() => operatingDate(new Date()), []);
  // The run's day: today, or the first day ahead with a released trip (issue #114).
  const [date, setDate] = useState(() => keptRunDay(accountId, today));
  const [data, setData] = useState<RunData | null>(null);
  const [keptAt, setKeptAt] = useState<Date | null>(null);
  const [syncedAt, setSyncedAt] = useState<Date | null>(null);
  const [expired, setExpired] = useState(false);
  const [loading, setLoading] = useState(true);
  const [entries, setEntries] = useState<StoredEntry[]>([]);
  const [uploads, setUploads] = useState<StoredUpload[]>([]);
  const [busy, setBusy] = useState(false);
  const [tick, setTick] = useState(0);
  const entriesRef = useRef<StoredEntry[]>([]);

  const refresh = useCallback(() => setTick((n) => n + 1), []);

  const readDevice = useCallback(async () => {
    const [queued, artifacts] = await Promise.all([pendingEntries(accountId), pendingUploads(accountId)]);
    entriesRef.current = queued;
    setEntries(queued);
    setUploads(artifacts);
    return queued;
  }, [accountId]);

  // The day: the server's copy when it answers, otherwise the one kept here.
  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;
    (async () => {
      await readDevice();
      if (online) {
        try {
          const fresh = await gateway.run(date, controller.signal);
          if (cancelled) return;
          if (!hasRun(fresh.sheets) && date === today) {
            const ahead = await nextRunDay(today, (day) => gateway.run(day, controller.signal));
            if (cancelled) return;
            if (ahead) {
              keepRunDay(accountId, ahead);
              setDate(ahead);
              return;
            }
          }
          setData(fresh);
          setKeptAt(null);
          setSyncedAt(new Date());
          setExpired(false);
          return;
        } catch (failure) {
          // Fall through to the kept copy: an outage is not an empty day.
          if (!cancelled && failure instanceof ApiError && failure.status === 401) setExpired(true);
        }
      }
      const snapshot = await gateway.keptRun(date);
      if (cancelled) return;
      if (snapshot) {
        setData(snapshot.data);
        setKeptAt(snapshot.savedAt);
      }
    })().finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [gateway, accountId, today, date, online, tick, readDevice]);

  // The shell's engine sent, or kept, something: read the queue again, and the
  // server's copy once nothing of ours is in flight.
  useEffect(() => {
    void readDevice().then((queued) => {
      if (queued.filter((entry) => !entry.needsReview).length === 0) refresh();
    });
  }, [queueState, readDevice, refresh]);

  // Proof artifacts go on their own, whenever the phone is online.
  useEffect(() => {
    if (!online) return;
    const send = () => void drainUploads(accountId).then(() => readDevice());
    send();
    window.addEventListener(UPLOADS_EVENT, send);
    const timer = window.setInterval(send, 30_000);
    return () => {
      window.removeEventListener(UPLOADS_EVENT, send);
      window.clearInterval(timer);
    };
  }, [online, accountId, readDevice]);

  const sheet = data ? pickSheet(data.sheets) : null;
  const stops = useMemo(
    () => (sheet ? project(sheet, entries.map((entry) => ({ command: entry.payload as Command, needsReview: entry.needsReview === true }))) : []),
    [sheet, entries],
  );

  const act = useCallback<Run["act"]>(
    async (kind, payload, stop) => {
      const command = newCommand(kind, payload, stop ? stop.rowVersion : null);
      const keepOnPhone = async (): Promise<Outcome> => {
        const saved = await gateway.queue(command);
        if (!saved.durable) {
          return { ok: false, error: new Error(`Not saved on this phone: ${saved.reason ?? "storage unavailable"}`) };
        }
        await readDevice();
        onQueued();
        return { ok: true, queued: true };
      };
      // Anything already waiting goes first, so this write queues behind it.
      const inFlight = (await readDevice()).some((entry) => !entry.needsReview);
      if (!online || inFlight) return keepOnPhone();

      setBusy(true);
      try {
        await gateway.send(command);
        // Read the stop back before answering, so the next screen opens on what
        // the server now holds rather than on what it held a moment ago.
        try {
          setData(await gateway.run(date, new AbortController().signal));
          setKeptAt(null);
          setSyncedAt(new Date());
        } catch {
          refresh();
        }
        return { ok: true, queued: false };
      } catch (failure) {
        const refused = failure instanceof ApiError && !failure.isRetryable && failure.status !== 401;
        if (!refused) return keepOnPhone();
        refresh();
        return { ok: false, error: failure };
      } finally {
        setBusy(false);
      }
    },
    [gateway, date, online, readDevice, refresh, onQueued],
  );

  return {
    date,
    loading,
    unavailable: !loading && data === null,
    keptAt,
    syncedAt,
    expired,
    vehicle: data?.vehicles.find((v) => v.vehicleId === sheet?.vehicleId) ?? data?.vehicles[0] ?? null,
    stops,
    outlets: data?.outlets ?? {},
    uploadsWaiting: uploads,
    busy,
    refresh,
    act,
    saveArtifact: gateway.saveArtifact,
  };
}

export { DeliveryKind };
