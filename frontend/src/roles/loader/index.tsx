"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useOnline, useResource } from "@shared/api/useResource";
import { useSync } from "@shared/offline";
import { Notice } from "@shared/ui";
import { lockOperator } from "@app-shell/operators";
import type { Operator } from "@app-shell/session";
import TopBar from "./TopBar.tsx";
import { LangProvider, useT } from "./i18n.tsx";
import { createGateway } from "./data/gateway.ts";
import { depotToday, hhmm } from "./data/manifest.ts";
import DockBoard from "./screens/DockBoard.tsx";
import LoadSheet from "./screens/LoadSheet.tsx";
import OperatorGate from "./screens/OperatorGate.tsx";

// The loader workspace from Figma "08 Loader · Phone", "07 Loader · Tablet"
// and "09 Loader · Tablet portrait". Resilient offline tier (src/shared/offline/tiers.ts):
// checks are kept on the device while offline and sent when it returns.

export default function Loader(props: Parameters<typeof LoaderWorkspace>[0]): React.JSX.Element {
  return (
    <LangProvider>
      <LoaderWorkspace {...props} />
    </LangProvider>
  );
}

function LoaderWorkspace({
  userId,
  displayName,
  scope,
  operator: initialOperator,
}: {
  userId: string;
  displayName: string;
  /** Depot codes from the session. The server enforces them. */
  scope: string[];
  operator: Operator | null;
}): React.JSX.Element {
  const tr = useT();
  const gateway = useMemo(() => createGateway(userId), [userId]);
  const online = useOnline();
  const depot = scope[0] ?? "";
  const date = depotToday();
  const [openId, setOpenId] = useState<string | null>(null);
  const [tripSync, setTripSync] = useState<Date | null>(null);
  const sync = useSync(userId);
  const [operator, setOperator] = useState<Operator | null>(initialOperator);
  const [lockError, setLockError] = useState<string | null>(null);

  const trips = useResource(depot ? (signal) => gateway.readyTrips(depot, date, signal) : null, `${depot}:${date}`, 30_000);
  const outletList = useResource(depot ? (signal) => gateway.outlets(depot, signal) : null, depot);
  const outlets = useMemo(() => new Map((outletList.data ?? []).map((o) => [o.outletId, o])), [outletList.data]);
  const open = trips.data?.find((t) => t.tripId === openId) ?? null;

  const { refresh } = trips;
  const back = useCallback(() => {
    setOpenId(null);
    refresh();
  }, [refresh]);
  useEffect(() => setOperator(initialOperator), [initialOperator]);

  const lock = useCallback(async () => {
    if (!online || sync.pending > 0) return;
    try {
      await lockOperator();
      setOperator(null);
      setOpenId(null);
      setLockError(null);
    } catch (failure) {
      setLockError(failure instanceof Error ? failure.message : "Could not lock this device.");
    }
  }, [online, sync.pending]);

  return (
    <div className="mx-auto flex min-h-dvh w-full max-w-[720px] flex-col bg-go-canvas font-go text-go-ink md:max-w-[1280px]">
      <TopBar
        displayName={operator?.displayName ?? displayName}
        depot={depot}
        title={open ? `${open.vehicleId} · ${tr("Trip {n}", { n: open.tripNumber })}` : undefined}
        subtitle={open ? tr("Departs {time}", { time: hhmm(open.plannedDeparture) }) : undefined}
        online={online}
        syncedAt={open ? tripSync : trips.loadedAt}
        waiting={sync.pending}
        sample={gateway.sample}
        onBack={open ? back : undefined}
        onLock={operator ? () => void lock() : undefined}
        lockDisabled={!online || sync.pending > 0}
      />
      {lockError && (
        <div className="px-5 pb-3">
          <Notice
            tone="danger"
            live
            title={lockError}
            action={
              <button type="button" onClick={() => setLockError(null)} className="min-h-12 shrink-0 px-2 text-[13px] font-medium text-go-teal">
                {tr("Dismiss")}
              </button>
            }
          />
        </div>
      )}
      {!operator ? (
        <OperatorGate online={online} pending={sync.pending} onOperator={setOperator} />
      ) : !depot ? (
        <p className="px-5 text-[15px] text-go-muted">Your account has no depot in scope. Ask an administrator to grant one.</p>
      ) : open ? (
        <LoadSheet
          key={open.tripId}
          gateway={gateway}
          trip={open}
          outlets={outlets}
          online={online}
          waiting={sync.pending}
          onQueued={sync.syncNow}
          onSynced={setTripSync}
          onBack={back}
          actingUserId={operator.userId}
        />
      ) : (
        <DockBoard depot={depot} meId={operator.userId} trips={trips} online={online} onOpen={setOpenId} />
      )}
    </div>
  );
}
