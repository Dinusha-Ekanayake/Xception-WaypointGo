"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { newCommand } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import { useOnline } from "@shared/api/useResource";
import type { HandoverEntryResult } from "@shared/domain/receipt";
import { ExecutionCommandKind, ReceiptCommandKind, type FailureReason, type ReportedVehicleStatus } from "@shared/domain/types";
import { discardUpload, useSync } from "@shared/offline";
import { useShell } from "@shared/ui";
import { createGateway } from "./data/gateway.ts";
import { usePositionRecorder } from "./data/position.ts";
import { isFinished, nextStop, type Stop } from "./data/run.ts";
import { DeliveryKind, useRun, type Outcome } from "./data/useRun.ts";
import type { HandoverAnswer } from "./screens/DeliveryPinConfirmModal.tsx";
import type { Report } from "./screens/DeliveryReport.tsx";
import type { ProofDraft } from "./screens/ProofCapture.tsx";
import type { Problem } from "./screens/Sheets.tsx";

// What the driver's screens do, as commands. Every write goes through useRun,
// which keeps it on the phone when it cannot be sent, so a day with no signal
// loses nothing (EXE-01). The screens in index.tsx only draw what this returns.

export type View =
  | { name: "home" }
  | { name: "route"; deliveryId: string | null }
  | { name: "report"; deliveryId: string; failed: FailureReason | null }
  | { name: "stop"; deliveryId: string }
  | { name: "complete" }
  | { name: "map" };

export type Saved = {
  title: string;
  onPhone: boolean;
  last: boolean;
  warning: string | null;
  /** The stop just recorded; null when nothing was handed over, so there is no PIN to enter. */
  handedOver: Stop | null;
};

const THEME_KEY = "waypoint.driver.theme";

function storedTheme(): boolean {
  try {
    return window.localStorage.getItem(THEME_KEY) === "dark";
  } catch {
    return false;
  }
}

function words(outcome: Outcome): string | null {
  return outcome.ok ? null : outcome.error.message;
}

export function useDriver(userId: string) {
  const online = useOnline();
  const shell = useShell();
  const sync = useSync(userId);
  const gateway = useMemo(() => createGateway(userId), [userId]);
  const run = useRun(gateway, userId, online, `${sync.pending}:${sync.held.length}`, sync.syncNow);

  const [view, setView] = useState<View>({ name: "home" });
  // GPS while a run is open (issue #161): a released trip with a stop still to do.
  const stillToDo = nextStop(run.stops);
  const location = usePositionRecorder(gateway, run.vehicle?.vehicleId ?? null, stillToDo?.tripId ?? null, stillToDo !== null);
  const [dark, setDark] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [saved, setSaved] = useState<Saved | null>(null);
  const [problemFor, setProblemFor] = useState<Stop | "run" | null>(null);
  const [leaving, setLeaving] = useState(false);
  const [working, setWorking] = useState(false);
  const [vehicleStatus, setVehicleStatus] = useState<ReportedVehicleStatus | null>(null);
  const [now, setNow] = useState(() => new Date());

  useEffect(() => setDark(storedTheme()), []);

  // The route screen says how late the next stop is running, by this phone's clock.
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const theme = () => {
    setDark((value) => {
      try {
        window.localStorage.setItem(THEME_KEY, value ? "light" : "dark");
      } catch {
        // The theme is a convenience; without storage it lasts until reload.
      }
      return !value;
    });
  };

  const go = useCallback((next: View) => {
    setError(null);
    setNotice(null);
    setView(next);
    window.scrollTo({ top: 0 });
  }, []);

  const { stops, act } = run;
  const byId = (id: string | null) => (id ? stops.find((stop) => stop.deliveryId === id) ?? null : null);
  const waiting = sync.pending + run.uploadsWaiting.length;
  const busy = run.busy || working;

  /** Tells dispatch the vehicle is heading to this stop. The arrival does not depend on it. */
  const start = async (stop: Stop) => {
    if (stop.outcome !== "PENDING" || stop.startedAt !== null) return;
    const outcome = await act(DeliveryKind.start, { deliveryId: stop.deliveryId }, stop);
    if (!outcome.ok) setError(words(outcome));
  };

  const openRun = async () => {
    const next = nextStop(stops);
    if (!next) return go({ name: "complete" });
    // Already at the stop, recorded here or before a restart: straight to its report.
    if (next.outcome === "ARRIVED") return go({ name: "report", deliveryId: next.deliveryId, failed: null });
    go({ name: "route", deliveryId: null });
    await start(next);
  };

  const openStop = async (stop: Stop) => {
    if (isFinished(stop)) return go({ name: "stop", deliveryId: stop.deliveryId });
    if (stop.outcome === "ARRIVED") return go({ name: "report", deliveryId: stop.deliveryId, failed: null });
    go({ name: "route", deliveryId: stop.deliveryId });
    await start(stop);
  };

  /** True once the arrival is recorded, sent or kept on this phone. */
  const arrived = async (stop: Stop): Promise<boolean> => {
    setError(null);
    const outcome = await act(DeliveryKind.arrive, { deliveryId: stop.deliveryId, deviceArrivedAt: new Date().toISOString() }, stop);
    if (!outcome.ok) {
      setError(words(outcome));
      return false;
    }
    go({ name: "report", deliveryId: stop.deliveryId, failed: null });
    return true;
  };

  /**
   * Keeps the photo and the signature on this phone, then records the proof
   * that names them. The files travel on their own and the server accepts
   * either order (EXE-10).
   */
  const captureProof = async (stop: Stop, version: number, proof: ProofDraft): Promise<Outcome> => {
    const keepFile = async (kind: "photo" | "signature", blob: Blob | null) => {
      if (!blob) return null;
      const id = crypto.randomUUID();
      const kept = await run.saveArtifact(stop.deliveryId, id, kind, blob);
      return kept.durable ? id : null;
    };
    const [photo, signature] = await Promise.all([keepFile("photo", proof.photo), keepFile("signature", proof.signature)]);
    const fallback = proof.fallbackReason.trim() || (photo || signature ? "" : "This phone could not store the photo or the signature");
    return act(
      DeliveryKind.proof,
      {
        deliveryId: stop.deliveryId,
        photoAttachmentId: photo,
        signatureAttachmentId: signature,
        recipientName: proof.recipientName.trim() || null,
        fallbackReason: fallback || null,
      },
      { ...stop, rowVersion: version },
    );
  };

  /** True once the record is kept, sent or on this phone; its proof may still have failed (see `saved`). */
  const confirm = async (stop: Stop, report: Report): Promise<boolean> => {
    setError(null);
    setWorking(true);
    try {
      const { record } = report;
      const recorded = await act(
        DeliveryKind.record,
        {
          deliveryId: stop.deliveryId,
          outcome: record.outcome,
          deliveredUnits: record.outcome === "FAILED" ? null : record.deliveredUnits,
          reason: record.reason.trim() || null,
          dispositionNote: record.dispositionNote.trim() || null,
        },
        stop,
      );
      if (!recorded.ok) {
        setError(words(recorded));
        return false;
      }
      // The record moved the stop's version on by one, sent or waiting.
      const proven = report.proof ? await captureProof(stop, stop.rowVersion + 1, report.proof) : null;
      setSaved({
        title: record.outcome === "DELIVERED" ? "Delivery confirmed" : record.outcome === "PARTIAL" ? "Partial delivery recorded" : "Recorded as not delivered",
        onPhone: recorded.queued || (proven?.ok === true && proven.queued),
        last: stops.every((other) => other.deliveryId === stop.deliveryId || isFinished(other)),
        warning: proven && !proven.ok ? `The delivery is recorded, but its proof was not: ${proven.error.message} Open the stop from Home to add it.` : null,
        handedOver: record.outcome === "FAILED" ? null : stop,
      });
      return true;
    } finally {
      setWorking(false);
    }
  };

  const addProof = async (stop: Stop, proof: ProofDraft) => {
    setError(null);
    setWorking(true);
    try {
      const outcome = await captureProof(stop, stop.rowVersion, proof);
      if (!outcome.ok) return setError(words(outcome));
      setNotice(outcome.queued ? "Proof saved on this phone. It is sent when the connection is back." : "Proof saved and sent.");
    } finally {
      setWorking(false);
    }
  };

  const afterSaved = async () => {
    const wasLast = saved?.last === true;
    setSaved(null);
    const next = nextStop(stops);
    if (wasLast || !next) return go({ name: "complete" });
    go({ name: "route", deliveryId: null });
    await start(next);
  };

  const report = async (problem: Problem) => {
    const stop = problemFor === "run" ? null : problemFor;
    if (problem.kind === "not-delivered") {
      setProblemFor(null);
      if (stop) go({ name: "report", deliveryId: stop.deliveryId, failed: problem.reason });
      return;
    }
    if (!run.vehicle) return;
    setError(null);
    const outcome = await act(ExecutionCommandKind.reportFault, {
      vehicleId: run.vehicle.vehicleId,
      deliveryId: stop?.deliveryId ?? null,
      kind: problem.fault,
      description: problem.description,
    });
    if (!outcome.ok) return setError(words(outcome));
    setProblemFor(null);
    setNotice(outcome.queued ? "Report saved on this phone. Dispatch gets it when the connection is back." : "Dispatch has your report.");
  };

  const reportStatus = async (status: ReportedVehicleStatus) => {
    if (!run.vehicle) return;
    setError(null);
    const outcome = await act(ExecutionCommandKind.reportVehicleStatus, { vehicleId: run.vehicle.vehicleId, status, note: null });
    if (!outcome.ok) return setError(words(outcome));
    setVehicleStatus(status);
    setNotice(outcome.queued ? "Vehicle status saved on this phone." : "Vehicle status sent to dispatch.");
  };

  /**
   * The store manager's handover PIN (R-RCP-09). Online only: the PIN is checked
   * by the server and expires, so a queued guess would mean nothing. Never a
   * gate: whatever the answer, the delivery stands.
   */
  const verifyHandover = async (stop: Stop, pin: string): Promise<HandoverAnswer> => {
    try {
      const ack = await gateway.send(newCommand(ReceiptCommandKind.verifyHandover, { orderId: stop.orderId, pin }));
      const result = ack.result as HandoverEntryResult;
      return { outcome: result.outcome, attemptsLeft: result.attemptsLeft };
    } catch (failure) {
      if (failure instanceof ApiError && failure.code === "FORBIDDEN" && /no handover PIN/i.test(failure.message)) {
        return { error: "The store manager has not confirmed the receipt yet, so there is no PIN to check. Ask them to confirm it, or skip." };
      }
      if (failure instanceof ApiError) return { error: failure.message };
      return { error: "Waypoint is not answering. The delivery is recorded; you can skip the PIN." };
    }
  };

  /** A proof file the server refused: only the driver drops it. */
  const dropUpload = async (id: string) => {
    await discardUpload(userId, id);
    run.refresh();
  };

  const shown = view.name === "route" ? (() => {
    const chosen = byId(view.deliveryId);
    return chosen && !isFinished(chosen) ? chosen : nextStop(stops);
  })() : null;
  const reported = view.name === "report" ? byId(view.deliveryId) : null;
  // A report whose stop already has its outcome, recorded here or replanned by
  // dispatch while the phone was away, shows what was recorded instead (EXE-22).
  const settled = reported !== null && isFinished(reported);
  const reporting = settled ? null : reported;
  const detail = view.name === "stop" ? byId(view.deliveryId) : settled ? reported : null;
  // A screen whose stop is no longer on the run falls back to Home.
  const lost = (view.name === "route" && !shown && nextStop(stops) !== null) || (view.name === "report" && !reported) || (view.name === "stop" && !detail);
  const screen = lost ? "home" : view.name === "route" && !shown ? "complete" : settled ? "stop" : view.name;

  return {
    online,
    location,
    run,
    sync,
    view,
    screen,
    shown,
    reporting,
    detail,
    dark,
    theme,
    error,
    notice,
    saved,
    problemFor,
    leaving,
    busy,
    waiting,
    vehicleStatus,
    now,
    go,
    openRun,
    openStop,
    arrived,
    confirm,
    addProof,
    afterSaved,
    report,
    reportStatus,
    verifyHandover,
    dropUpload,
    openProblem: (target: Stop | "run") => setProblemFor(target),
    closeProblem: () => {
      setProblemFor(null);
      setError(null);
    },
    askSignOut: () => setLeaving(true),
    cancelSignOut: () => setLeaving(false),
    signOut: () => {
      setLeaving(false);
      shell?.onSignOut();
    },
  };
}
