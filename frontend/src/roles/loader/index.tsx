"use client";

import { useCallback, useEffect, useLayoutEffect, useMemo, useState } from "react";
import { flushSync } from "react-dom";
import { useOnline, useResource } from "@shared/api/useResource";
import { registerResolver, useSync } from "@shared/offline";
import { useInbox } from "@shared/notifications/useInbox";
import { Notice, useScrollMemory, withTransition } from "@shared/ui";
import { crew, lockOperator, replayBeforeSync } from "@app-shell/operators";
import { keptCrew, logOfflineSwitch } from "@app-shell/offlinePin";
import type { CrewMember } from "@app-shell/operators";
import type { Operator } from "@app-shell/session";
import TopBar from "./TopBar.tsx";
import { useWide } from "./useWide.ts";
import { LangProvider, useT } from "./i18n.tsx";
import { ThemeProvider, useTheme } from "./theme.tsx";
import { createGateway } from "./data/gateway.ts";
import { depotToday, hhmm } from "./data/manifest.ts";
import { dockDay, stillToLoad } from "./data/dockDay.ts";
import { redoVersion, tripOf } from "./data/redo.ts";
import DockBoard from "./screens/DockBoard.tsx";
import Locked from "./screens/Locked.tsx";
import LoadSheet from "./screens/LoadSheet.tsx";
import Notifications from "./screens/Notifications.tsx";
import OperatorGate from "./screens/OperatorGate.tsx";
import Settings from "./screens/Settings.tsx";
import { useClockOffset } from "@shared/demo/useDemo";

// The loader workspace from Figma "08 Loader · Phone", "07 Loader · Tablet"
// and "09 Loader · Tablet portrait". Resilient offline tier (src/shared/offline/tiers.ts):
// checks are kept on the device while offline and sent when it returns.

export default function Loader(props: Parameters<typeof LoaderWorkspace>[0]): React.JSX.Element {
  return (
    <ThemeProvider>
      <LangProvider>
        <LoaderWorkspace {...props} />
      </LangProvider>
    </ThemeProvider>
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
  // The dock's day: the first from today with a trip still to load (issue #114),
  // today on the business clock, which demo mode can move.
  useClockOffset();
  const today = depotToday();
  const [date, setDate] = useState(today);
  const [openId, setOpenId] = useState<string | null>(null);
  const [tripSync, setTripSync] = useState<Date | null>(null);
  // Before the first sync pass: queued work goes after the switches it was recorded under.
  useLayoutEffect(() => replayBeforeSync(userId), [userId]);
  const sync = useSync(userId);
  const [operator, setOperator] = useState<Operator | null>(initialOperator);
  // A held check is redone on the trip's current version, under whoever is loading now.
  const operatorId = operator?.userId ?? null;
  useEffect(
    () =>
      registerResolver("loading:", async (entry, waiting) => {
        const tripId = tripOf(entry);
        if (!tripId) return null;
        if (!operatorId) return null;
        const manifest = await gateway.manifest(tripId, new AbortController().signal);
        return { expectedVersion: redoVersion(tripId, manifest.rowVersion, waiting), actingUserId: operatorId };
      }),
    [gateway, operatorId],
  );
  const [lockError, setLockError] = useState<string | null>(null);
  const [settings, setSettings] = useState(false);
  // The PIN and Settings screens stand alone in Figma, with only a way back.
  const [pinStep, setPinStep] = useState(false);
  // Who locked the device (Figma 08), kept across a reload so the locked screen survives one.
  const [locked, setLockedState] = useState<Operator | null>(() => readLocked(userId));
  const [unlocking, setUnlocking] = useState(false);
  // From a tablet up the locked screen carries its own keypad (Figma 06 Locked).
  const wide = useWide();
  const setLocked = useCallback((who: Operator | null) => {
    setLockedState(who);
    writeLocked(userId, who);
  }, [userId]);
  const { theme } = useTheme();

  const trips = useResource(depot ? (signal) => gateway.readyTrips(depot, date, signal) : null, `${depot}:${date}`, 2_000);
  // Found again on start, on reconnecting, and once the shown day has nothing left to load.
  const dayDone = trips.data !== null && !stillToLoad(trips.data);
  useEffect(() => {
    if (!depot || !online) return;
    const controller = new AbortController();
    dockDay(today, (day) => gateway.readyTrips(depot, day, controller.signal))
      .then((day) => !controller.signal.aborted && setDate(day))
      .catch(() => {
        // Unreachable: keep the day shown; the board says it could not read it.
      });
    return () => controller.abort();
  }, [depot, online, gateway, today, dayDone]);
  // Notifications for this device's account (issue #118); none in the sample.
  const inbox = useInbox(userId, !gateway.sample);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  // "Synced 02:23" syncs now: send what waits, read the board and an open trip again.
  const [syncKey, setSyncKey] = useState(0);
  const syncNow = () => {
    sync.syncNow();
    trips.refresh();
    setSyncKey((k) => k + 1);
  };
  const outletList = useResource(depot ? (signal) => gateway.outlets(depot, signal) : null, depot);
  const outlets = useMemo(() => new Map((outletList.data ?? []).map((o) => [o.outletId, o])), [outletList.data]);
  const open = trips.data?.find((t) => t.tripId === openId) ?? null;
  // Back from a load sheet, the board is where it was; a load sheet opens at the top (UX polish 2).
  useScrollMemory(operator && !open && !settings ? "loader:board" : null);

  const { refresh } = trips;
  // The trip last opened: its row on the board and the load sheet's truck card are one
  // shared element, so it moves in and back out (UX polish 4). Named before the
  // transition starts, so the board's snapshot already carries it.
  const [moving, setMoving] = useState<string | null>(null);
  const openTrip = useCallback((tripId: string) => {
    flushSync(() => setMoving(tripId));
    withTransition(() => setOpenId(tripId), "forward");
  }, []);
  const back = useCallback(() => {
    withTransition(() => setOpenId(null), "back");
    refresh();
  }, [refresh]);
  useEffect(() => setOperator(initialOperator), [initialOperator]);
  // Keep the crew list fresh while online, so the next loader can switch with
  // their PIN even if the connection is gone by then. A failure leaves the last
  // list, and the PIN screen says when there is none.
  useEffect(() => {
    if (online) crew(userId).catch(() => undefined);
  }, [online, userId]);

  /** Ends the operator on the server, or in the offline log, so nobody is the actor. */
  const endOperator = useCallback(async () => {
    if (!online) {
      if (!logOfflineSwitch(userId, { userId: null, at: new Date().toISOString() })) {
        throw new Error("This device can't save the lock. Connect and try again.");
      }
    } else {
      await lockOperator(userId);
    }
  }, [online, userId]);

  const lock = useCallback(async () => {
    const who = operator;
    try {
      await endOperator();
      setOperator(null);
      setLocked(who);
      setOpenId(null);
      setLockError(null);
    } catch (failure) {
      setLockError(failure instanceof Error ? failure.message : "Could not lock this device.");
    }
  }, [endOperator, operator, setLocked]);

  /** Switch user (Figma ⇄): nobody is working, and the crew list is shown. */
  const switchUser = useCallback(async () => {
    try {
      if (operator) await endOperator();
      setOperator(null);
      setLocked(null);
      setUnlocking(false);
      setOpenId(null);
      setLockError(null);
    } catch (failure) {
      setLockError(failure instanceof Error ? failure.message : "Could not switch user.");
    }
  }, [endOperator, operator, setLocked]);

  const signedIn = useCallback((who: Operator) => {
    writeRecent(userId, who.userId);
    setOperator(who);
    setLocked(null);
    setUnlocking(false);
  }, [setLocked, userId]);

  const lockedTrip = locked
    ? trips.data?.find((t) => t.holder?.userId === locked.userId && t.status !== "COMPLETED") ?? null
    : null;
  const unlockMember: CrewMember | null = locked
    ? keptCrew(userId)?.members.find((m) => m.userId === locked.userId)
      ?? { userId: locked.userId, displayName: locked.displayName, employeeCode: locked.employeeCode, offlineVerifier: null }
    : null;

  return (
    <div className={`loader-workspace ${theme === "dark" ? "go-dark " : ""}min-h-dvh bg-go-canvas`} data-theme={theme}>
    <div className="mx-auto flex min-h-dvh w-full max-w-[720px] flex-col bg-go-canvas font-go text-go-ink md:max-w-none">
      {!settings && !pinStep && <TopBar
        displayName={operator?.displayName ?? null}
        depot={depot}
        title={open ? `${open.vehicleId} · ${tr("Trip {n}", { n: open.tripNumber })}` : undefined}
        subtitle={open ? tr("Departs {time}", { time: hhmm(open.plannedDeparture) }) : undefined}
        online={online}
        syncedAt={open ? tripSync : trips.loadedAt}
        waiting={sync.pending}
        sample={gateway.sample}
        onBack={open ? back : undefined}
        onLock={operator ? () => void lock() : undefined}
        onSwitch={operator ? () => void switchUser() : undefined}
        onSettings={() => setSettings(true)}
        unread={inbox.unread}
        onNotifications={gateway.sample ? undefined : () => setNotificationsOpen(true)}
        onSync={syncNow}
        syncing={sync.syncing || trips.loading}
      />}
      {notificationsOpen && <Notifications inbox={inbox} onClose={() => setNotificationsOpen(false)} />}
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
      {settings ? (
        <Settings deviceName={displayName} onClose={() => setSettings(false)} />
      ) : !operator && locked && wide ? (
        <OperatorGate
          key="locked"
          account={userId}
          online={online}
          onOperator={signedIn}
          unlock={unlockMember}
          aside={<Locked gateway={gateway} operator={locked} depot={depot} trip={lockedTrip} onUnlock={() => {}} onSwitch={() => void switchUser()} wide />}
        />
      ) : !operator && locked && unlocking ? (
        <OperatorGate
          key="unlock"
          account={userId}
          online={online}
          onOperator={signedIn}
          unlock={unlockMember}
          onCancelUnlock={() => setUnlocking(false)}
          onPinStep={setPinStep}
        />
      ) : !operator && locked ? (
        <Locked
          gateway={gateway}
          operator={locked}
          depot={depot}
          trip={lockedTrip}
          onUnlock={() => setUnlocking(true)}
          onSwitch={() => void switchUser()}
        />
      ) : !operator ? (
        <OperatorGate key="crew" account={userId} online={online} onOperator={signedIn} recentId={readRecent(userId)} onPinStep={setPinStep} />
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
          accountId={userId}
          refreshKey={syncKey}
        />
      ) : (
        <DockBoard depot={depot} meId={operator.userId} trips={trips} online={online} onOpen={openTrip} moving={moving} date={date} />
      )}
    </div>
    </div>
  );
}

// Who locked this device, and who last worked on it, kept per device so a
// reload shows the locked screen again and the crew list marks the last loader.
const LOCKED_KEY = (account: string) => `waypoint.loader.locked.${account}`;
const RECENT_KEY = (account: string) => `waypoint.loader.recent.${account}`;

function readLocked(account: string): Operator | null {
  try {
    const raw = window.localStorage.getItem(LOCKED_KEY(account));
    return raw ? (JSON.parse(raw) as Operator) : null;
  } catch {
    return null;
  }
}

function writeLocked(account: string, who: Operator | null): void {
  try {
    if (who) {
      window.localStorage.setItem(LOCKED_KEY(account), JSON.stringify(who));
      window.localStorage.setItem(RECENT_KEY(account), who.userId);
    } else {
      window.localStorage.removeItem(LOCKED_KEY(account));
    }
  } catch {
    // Blocked storage: the locked screen lasts until reload.
  }
}

function writeRecent(account: string, operatorId: string): void {
  try {
    window.localStorage.setItem(RECENT_KEY(account), operatorId);
  } catch {
    // Blocked storage: nobody is marked as recent.
  }
}

function readRecent(account: string): string | null {
  try {
    return window.localStorage.getItem(RECENT_KEY(account));
  } catch {
    return null;
  }
}
