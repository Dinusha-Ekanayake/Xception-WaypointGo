"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useOnline, useResource } from "@shared/api/useResource";
import { Notice } from "@shared/ui";
import TopBar from "./TopBar.tsx";
import { createGateway } from "./data/gateway.ts";
import { depotToday } from "./data/manifest.ts";
import DockBoard from "./screens/DockBoard.tsx";
import LoadSheet from "./screens/LoadSheet.tsx";

// The loader workspace from Figma "08 Loader · Phone", phone first and usable
// on a dock tablet. Resilient offline tier (src/shared/offline/tiers.ts):
// checks are kept on the device while offline and sent when it returns.

export default function Loader({
  userId,
  displayName,
  scope,
}: {
  userId: string;
  displayName: string;
  /** Depot codes from the session. The server enforces them. */
  scope: string[];
}): React.JSX.Element {
  const gateway = useMemo(() => createGateway(userId), [userId]);
  const online = useOnline();
  const depot = scope[0] ?? "";
  const date = depotToday();
  const [openId, setOpenId] = useState<string | null>(null);
  const [tripSync, setTripSync] = useState<Date | null>(null);
  const [waiting, setWaiting] = useState(0);

  const trips = useResource(depot ? (signal) => gateway.readyTrips(depot, date, signal) : null, `${depot}:${date}`, 30_000);
  const outletList = useResource(depot ? (signal) => gateway.outlets(depot, signal) : null, depot);
  const outlets = useMemo(() => new Map((outletList.data ?? []).map((o) => [o.outletId, o])), [outletList.data]);
  const open = trips.data?.find((t) => t.tripId === openId) ?? null;

  const { refresh } = trips;
  const back = useCallback(() => {
    setOpenId(null);
    refresh();
  }, [refresh]);
  const queued = useCallback(() => setWaiting((n) => n + 1), []);
  const [flushError, setFlushError] = useState<string | null>(null);

  // Send kept checks as soon as the connection returns, whichever screen is open.
  useEffect(() => {
    if (!online || waiting === 0) return;
    let cancelled = false;
    gateway
      .flush()
      .then((report) => {
        if (cancelled) return;
        if (report.heldForReview > 0) {
          setFlushError(
            `${report.heldForReview} saved ${report.heldForReview === 1 ? "check" : "checks"} could not be applied because the load sheet changed. Check those orders again.`,
          );
        }
        setWaiting(report.remaining - report.heldForReview);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [online, waiting, gateway]);

  return (
    <div className="mx-auto flex min-h-dvh w-full max-w-[720px] flex-col lg:max-w-[1280px] bg-go-canvas font-go text-go-ink">
      <TopBar
        displayName={displayName}
        online={online}
        syncedAt={open ? tripSync : trips.loadedAt}
        waiting={waiting}
        sample={gateway.sample}
        onBack={open ? back : undefined}
      />
      {flushError && (
        <div className="px-5 pb-3">
          <Notice
            tone="danger"
            live
            title={flushError}
            action={
              <button type="button" onClick={() => setFlushError(null)} className="min-h-12 shrink-0 px-2 text-[13px] font-medium text-go-teal">
                Dismiss
              </button>
            }
          />
        </div>
      )}
      {!depot ? (
        <p className="px-5 text-[15px] text-go-muted">Your account has no depot in scope. Ask an administrator to grant one.</p>
      ) : open ? (
        <LoadSheet
          key={open.tripId}
          gateway={gateway}
          trip={open}
          outlets={outlets}
          online={online}
          waiting={waiting}
          onQueued={queued}
          onSynced={setTripSync}
        />
      ) : (
        <DockBoard depot={depot} trips={trips} online={online} onOpen={setOpenId} />
      )}
    </div>
  );
}
