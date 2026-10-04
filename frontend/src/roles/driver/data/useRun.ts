"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { newCommand, type Command } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import { drainUploads, pendingEntries, pendingUploads, UPLOADS_EVENT, type StoredEntry, type StoredUpload } from "@shared/offline";
import type { OutletView, VehicleView } from "@shared/domain/types";
import type { DriverGateway, RunData } from "./gateway.ts";
import { DeliveryKind, acknowledged, hasRun, isStopAck, keepRunDay, keptRunDay, lookAhead, operatingDate, project, todaysSheet, type NextRun, type Stop } from "./run.ts";
import { businessNow } from "@shared/wording";
import { useClockOffset } from "@shared/demo/useDemo";

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

/** How often the day is read again while the app is on screen, so changes reach the phone by themselves. */
const RUN_POLL_MS = 2_000;

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
  /** With nothing released today: the next trip ahead, released or still at the dock (U2). */
  nextRun: NextRun | null;
};

/**
 * @param queueState changes whenever the sync engine sent, kept or had a write
 *     refused; the queue is then read again and, once nothing is in flight,
 *     the server's copy too. A refusal changes no count, so a count alone
 *     would leave a refused write drawn as if it had happened (EXE-03).
 */
export function useRun(gateway: DriverGateway, accountId: string, online: boolean, queueState: string, onQueued: () => void, depot: string | null = null): Run {
  // Today on the business clock: a moved demo clock moves the run's day with it.
  const offset = useClockOffset();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const today = useMemo(() => operatingDate(businessNow()), [offset]);
  // The run's day: today, or the first day ahead with a released trip (issue #114).
  const [date, setDate] = useState(() => keptRunDay(accountId, today));
  useEffect(() => setDate(keptRunDay(accountId, today)), [accountId, today]);
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
  // The day as last read, for the version of the next write: React state lags a render behind.
  const dataRef = useRef<RunData | null>(null);
  dataRef.current = data;
  // Versions the server acknowledged this session: a floor, whatever copy is on screen.
  const acked = useRef(new Map<string, number>());
  // One write at a time, so each names the version the one before it produced.
  const writes = useRef<Promise<unknown>>(Promise.resolve());

  const refresh = useCallback(() => setTick((n) => n + 1), []);

  const readDevice = useCallback(async () => {
    const [queued, artifacts] = await Promise.all([pendingEntries(accountId), pendingUploads(accountId)]);
    entriesRef.current = queued;
    setEntries(queued);
    setUploads(artifacts);
    return queued;
  }, [accountId]);

  // The day: the server's copy when it answers, otherwise the one kept here.
  const reading = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;
    reading.current = true;
    (async () => {
      await readDevice();
      if (online) {
        try {
          const fresh = await gateway.run(date, controller.signal);
          if (cancelled) return;
          dataRef.current = fresh;
          setData(fresh);
          setKeptAt(null);
          setSyncedAt(businessNow());
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
      reading.current = false;
    });
    return () => {
      cancelled = true;
      reading.current = false;
      controller.abort();
    };
  }, [gateway, date, online, tick, readDevice]);

  // Nothing released today: look ahead for the next trip in the background, every
  // day at once, so Home is drawn first and never waits on it (U1). A released run
  // becomes the phone's day; a trip still at the dock is named on Home (U2).
  const [nextRun, setNextRun] = useState<NextRun | null>(null);
  const nothingToday = data !== null && !hasRun(data.sheets);
  useEffect(() => {
    if (!online || date !== today || !nothingToday) return;
    const controller = new AbortController();
    lookAhead(today, (day) => gateway.probeDay(day, depot, controller.signal))
      .then((found) => {
        if (controller.signal.aborted) return;
        setNextRun(found);
        if (found?.released) {
          keepRunDay(accountId, found.date);
          setDate(found.date);
        }
      })
      .catch(() => {
        // Unreachable: Home keeps today's state; the next pass tries again.
      });
    return () => controller.abort();
    // Every fifteenth read of the day (about every 30 s): it asks a week of days at once.
  }, [online, date, today, nothingToday, Math.floor(tick / 15), gateway, depot, accountId]);

  // Keep the day current without a manual reload: every 2 s while the app is on
  // screen and online, and at once when it comes back to the front or back
  // online. A release by the loader, a change by dispatch or the store's receipt
  // then reach the phone by themselves. Writes waiting on the phone are kept
  // and drawn over the fresh copy as before.
  useEffect(() => {
    let last = Date.now();
    const again = (force: boolean) => {
      if (document.visibilityState !== "visible" || !navigator.onLine || reading.current) return;
      if (!force && Date.now() - last < 3_000) return;
      last = Date.now();
      refresh();
    };
    const timer = window.setInterval(() => again(true), RUN_POLL_MS);
    const back = () => again(false);
    document.addEventListener("visibilitychange", back);
    window.addEventListener("focus", back);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", back);
      window.removeEventListener("focus", back);
    };
  }, [refresh]);

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

  const sheet = data ? todaysSheet(data.sheets) : null;
  const stops = useMemo(
    () => (sheet ? project(sheet, entries.map((entry) => ({ command: entry.payload as Command, needsReview: entry.needsReview === true }))) : []),
    [sheet, entries],
  );

  /**
   * The version a write for this stop must name: what the last read holds with
   * every write still on the phone applied, and never below what the caller saw.
   */
  const versionOf = useCallback((stop: Stop, queued: StoredEntry[]): number => {
    const sheet = dataRef.current ? todaysSheet(dataRef.current.sheets) : null;
    const projected = sheet
      ? project(sheet, queued.map((entry) => ({ command: entry.payload as Command, needsReview: entry.needsReview === true }))).find(
          (other) => other.deliveryId === stop.deliveryId,
        )
      : undefined;
    return Math.max(stop.rowVersion, projected?.rowVersion ?? 0, acked.current.get(stop.deliveryId) ?? 0);
  }, []);

  const act = useCallback<Run["act"]>(
    (kind, payload, stop) => {
      const write = async (): Promise<Outcome> => {
        const command = newCommand(kind, payload, stop ? versionOf(stop, await readDevice()) : null);
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
          const ack = await gateway.send(command);
          // The answer names the stop's new version: keep it before anything
          // else can fail, so the next write never names the old one.
          if (isStopAck(ack.result)) {
            acked.current.set(ack.result.deliveryId, Math.max(ack.result.rowVersion, acked.current.get(ack.result.deliveryId) ?? 0));
            if (dataRef.current) {
              const known = { ...dataRef.current, sheets: acknowledged(dataRef.current.sheets, ack.result) };
              dataRef.current = known;
              setData(known);
              // The kept copy too, or a reload with no signal would bring the old version back.
              await gateway.keepRun(date, known).catch(() => undefined);
            }
          }
          // Read the stop back before answering, so the next screen opens on what
          // the server now holds rather than on what it held a moment ago.
          try {
            const fresh = await gateway.run(date, new AbortController().signal);
            dataRef.current = fresh;
            setData(fresh);
            setKeptAt(null);
            setSyncedAt(businessNow());
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
      };
      const next = writes.current.then(write);
      writes.current = next.catch(() => undefined);
      return next;
    },
    [gateway, date, online, readDevice, refresh, onQueued, versionOf],
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
    nextRun: date === today && nothingToday ? nextRun : null,
  };
}

export { DeliveryKind };
