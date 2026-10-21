"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useProductNames } from "@shared/catalogue/useProductNames";
import { flushSync } from "react-dom";
import type { NotificationView } from "@shared/domain/notification";
import { ago } from "@shared/notifications/inbox";
import { useInbox } from "@shared/notifications/useInbox";
import { cx, useDeviceLang, useMedia, useShell, SkeletonRows, SkipLink } from "@shared/ui";
import { nextStop, type Stop } from "./data/run.ts";
import { activeIndex, syncLabel, toRouteStops, tripStatus, type RouteStop } from "./data/stopView.ts";
import DeliveryPinConfirmModal from "./screens/DeliveryPinConfirmModal.tsx";
import DeliveryReport from "./screens/DeliveryReport.tsx";
import StopHandover from "./screens/StopHandover.tsx";
import DrivingModeScreen from "./screens/DrivingModeScreen.tsx";
import { metres } from "@shared/ui/map/geo";
import { chime, unlockSoundOnFirstTap } from "./data/chime.ts";
import { mayChime, OFF, onMotion, openByHand, remindDue, slideOut, type DrivingState } from "./data/drivingMode.ts";
import HomeNoVehicle from "./screens/HomeNoVehicle.tsx";
import { unreadMessages } from "./data/messages.ts";
import { useKeptTripAudio } from "@shared/messaging/audioCache";
import RefusedUploads from "./screens/RefusedUploads.tsx";
import RouteChangedBottomSheet from "./screens/RouteChangedBottomSheet.tsx";
import RouteMap from "./screens/RouteMap.tsx";
import RouteNextStop from "./screens/RouteNextStop.tsx";
import RunCompleteScreen from "./screens/RunCompleteScreen.tsx";
import { ProblemSheet, SavedSheet } from "./screens/Sheets.tsx";
import SignOutConfirmBottomSheet from "./screens/SignOutConfirmBottomSheet.tsx";
import LocationConsentBottomSheet from "./screens/LocationConsentBottomSheet.tsx";
import StopDetail from "./screens/StopDetail.tsx";
import { BackIcon, Banner, DriverMorphHeader, OutlineButton } from "./ui.tsx";
import { useDriver } from "./useDriver.ts";
import { DriverLang, useT } from "./i18n.tsx";
import { translate, type Translate } from "./data/strings.ts";

/** Notifications that change the driver's route: a revised plan, or a stop the dispatcher skipped. */
const ROUTE_CHANGED = new Set(["plan.revised", "delivery.skipped"]);

/**
 * The driver's phone (issues #21 and #117, Figma "12 · Driver · Mobile"). What
 * each screen does is in useDriver; this file chooses which one is drawn. The
 * Figma screens draw the run sheet Execution serves; the delivery form, the
 * proof, the problem report and the stop detail keep their working forms.
 */
export default function Driver({ userId, displayName, scope }: { userId: string; displayName: string; scope: string[] }): React.JSX.Element {
  // The warehouse's names on every product label (docs/issues/product-names/PLAN.md).
  useProductNames();
  const d = useDriver(userId, scope[0] ?? null);
  const inbox = useInbox(userId);
  const shell = useShell();
  const { run, view, screen, shown, reporting, detail, online, error, notice, busy } = d;
  const stops = run.stops;
  const [lang, setLang] = useDeviceLang();
  // This component sits above the language provider it renders, so it translates with the language directly.
  const tr = useCallback<Translate>((text, vars) => translate(lang, text, vars), [lang]);
  const routeStops = useMemo(() => toRouteStops(stops, run.outlets, run.date, d.now, tr), [stops, run.outlets, run.date, d.now, tr]);
  const byId = (id: string): Stop | undefined => stops.find((stop) => stop.deliveryId === id);
  const depot = scope[0] ?? "";
  const sync = syncLabel(online, run.uploadsWaiting.length, run.syncedAt, run.keptAt, tr);

  // The driving screen: on by itself when the vehicle moves, off after it stands still a while (R-EXE-28).
  const [drive, setDrive] = useState<DrivingState>(OFF);
  const driving = drive.on;
  const motion = d.location.motion;
  const underWay = nextStop(stops) !== null;
  useEffect(() => {
    const look = () => setDrive((s) => onMotion(s, motion, Date.now(), underWay));
    look();
    const timer = window.setInterval(look, 5_000);
    return () => window.clearInterval(timer);
  }, [motion, underWay]);
  useEffect(() => unlockSoundOnFirstTap(), []);
  // While driving, a new notification chimes (a changed plan always, a message at most every 5 minutes).
  const lastChime = useRef<number | null>(null);
  const heard = useRef<Set<string> | null>(null);
  useEffect(() => {
    const ids = inbox.items.filter((n) => n.readAt === null).map((n) => n.notificationId);
    if (heard.current === null) {
      if (!inbox.loading) heard.current = new Set(ids);
      return;
    }
    const fresh = inbox.items.filter((n) => n.readAt === null && !heard.current!.has(n.notificationId));
    ids.forEach((id) => heard.current!.add(id));
    if (!driving || fresh.length === 0) return;
    const urgent = fresh.some((n) => ROUTE_CHANGED.has(n.eventType));
    if (mayChime(lastChime.current, Date.now(), urgent)) {
      lastChime.current = Date.now();
      chime(urgent ? "plan" : "message");
    }
  }, [inbox.items, inbox.loading, driving]);
  const [formFor, setFormFor] = useState<string | null>(null);
  const [pinFor, setPinFor] = useState<Stop | null>(null);
  // Stable, because the PIN popup closes itself on a timer that restarts whenever this changes.
  const closePin = useCallback(() => setPinFor(null), []);
  const [revised, setRevised] = useState<NotificationView | null>(null);
  // The stop row last tapped on the route, one shared element with the stop's header (UX polish 4).
  const [movingStop, setMovingStop] = useState<string | null>(null);
  const unread = unreadMessages(inbox.items);
  // R-NOT-18: an unread message chimes again, a few times, until it is seen.
  const reminders = useRef(new Map<string, { heardAt: number; reminded: number }>());
  const oldestUnread = unread.length > 0 ? unread.reduce((a, b) => (a.createdAt <= b.createdAt ? a : b)).notificationId : null;
  useEffect(() => {
    if (oldestUnread === null) return;
    const look = () => {
      const now = Date.now();
      const r = reminders.current.get(oldestUnread) ?? { heardAt: now, reminded: 0 };
      reminders.current.set(oldestUnread, r);
      if (!remindDue(r.heardAt, r.reminded, now)) return;
      r.reminded += 1;
      lastChime.current = now;
      chime("message");
    };
    look();
    const timer = window.setInterval(look, 30_000);
    return () => window.clearInterval(timer);
  }, [oldestUnread]);
  // R-MSG-11: the trip's messages can be heard later with no signal.
  useKeptTripAudio(userId, d.tripId, online, unread.map((n) => n.notificationId).join(","));
  // The bell: messages are heard on Home, not in a chat (decision 2026-10-09).
  const openNotifications = () => {
    if (view.name !== "home") d.go({ name: "home" });
    // Everything up to now, the same number the bell and the Home heading show.
    if ((inbox.unread ?? 0) > 0 && online) void inbox.markAllRead(new Date()).catch(() => undefined);
  };
  const seen = useRef<Set<string> | null>(null);

  // The delivery form opens from the waiting screen; a stop reported as not
  // delivered from the problem sheet opens it straight away.
  useEffect(() => {
    if (view.name !== "report") return setFormFor(null);
    if (view.failed) setFormFor(view.deliveryId);
  }, [view]);

  // A run sheet revised while the driver is out says so once (Figma "Run sheet changed"), and so
  // does a stop the dispatcher took off (R-EXE-31): the run sheet is read again at once, since the
  // driver watches no change stream and would otherwise drive to a skipped store until the next poll.
  const refreshRun = useRef(run.refresh);
  refreshRun.current = run.refresh;
  useEffect(() => {
    const ids = inbox.items.map((item) => item.notificationId);
    if (seen.current === null) {
      if (!inbox.loading) seen.current = new Set(ids);
      return;
    }
    const fresh = inbox.items.find((item) => !seen.current!.has(item.notificationId) && item.readAt === null && ROUTE_CHANGED.has(item.eventType));
    ids.forEach((id) => seen.current!.add(id));
    if (fresh) {
      setRevised(fresh);
      refreshRun.current();
    }
  }, [inbox.items, inbox.loading]);

  // Space opens driving mode; only its slider closes it.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target;
      if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement || target instanceof HTMLButtonElement) return;
      if ((event.code === "Space" || event.key === " ") && nextStop(stops)) {
        event.preventDefault();
        setDrive(openByHand);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [stops]);

  // The straight-line distance to the next stop, for the driving screen; null with no location or no exact store point.
  const distanceToNext = (): string | null => {
    const next = nextStop(stops);
    const here = d.location.here;
    const point = next ? run.outlets[next.outletId]?.location : null;
    if (!here || !point) return null;
    const km = metres(here, { lat: Number(point.latitude), lon: Number(point.longitude) }) / 1000;
    return `${km.toFixed(2)} km`;
  };

  const arrivedAt = (target: RouteStop) => {
    const stop = byId(target.id);
    if (!stop) return;
    if (stop.outcome === "ARRIVED") d.go({ name: "report", deliveryId: stop.deliveryId, failed: null });
    else void d.arrived(stop);
  };

  // Home makes room for the notices above it: drawn over it, a signed-out
  // notice and its button covered the driver's name and the trip card.
  const notices = useRef<HTMLDivElement | null>(null);
  const [noticeHeight, setNoticeHeight] = useState(0);
  useEffect(() => {
    const element = notices.current;
    if (!element) return;
    const observer = new ResizeObserver(() => setNoticeHeight(element.offsetHeight));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  const header = screen === "home" || screen === "route" || screen === "map";
  const next = nextStop(stops);
  // On a desk (a mouse and a window with room to spare) the run is drawn at a
  // phone's size and shape, as it is designed; a phone or tablet still fills
  // its screen, so nothing is cut off sideways (issue #201).
  const framed = useMedia("(pointer: fine) and (min-width: 768px) and (min-height: 600px)");

  return (
    <DriverLang lang={lang}>
      <main
        aria-label={tr("Driver workspace")}
        data-theme={d.dark ? "dark" : "light"}
        // The run fills the device: a phone either way up, and on a tablet a
        // column as wide as the screens are drawn for (issue #201). The phone
        // mock-up it sat in before was cut off on a phone held sideways and on a
        // landscape tablet.
        className={cx(
          // The screen less the demo bar above it, and clear of the home bar when installed edge to edge.
          "flex h-[calc(100dvh-var(--demo-banner-h,0px))] w-full justify-center overflow-hidden pb-[env(safe-area-inset-bottom)] font-go transition-colors",
          framed && "items-center",
          d.dark ? "go-dark bg-[#161616] md:bg-[#0a0a0a]" : "bg-[#E7F3F2] md:bg-[#d6e7e5]"
        )}
      >
        <SkipLink targetId="driver-content" />
        <div
          id="driver-content"
          tabIndex={-1}
          className={cx(
            "relative w-full overflow-hidden transition-colors md:shadow-2xl",
            framed ? "h-[min(852px,calc(100dvh-48px-var(--demo-banner-h,0px)))] max-w-[393px] rounded-[44px]" : "h-full md:max-w-[600px]",
            d.dark ? "bg-[#161616]" : "bg-[#E7F3F2]"
          )}
        >
          {header && (
            <div className="driver-header-container absolute top-0 inset-x-0 z-30">
              <DriverMorphHeader
                activeScreen={screen === "map" ? "route-map" : screen === "route" ? "route-next-stop" : "home"}
                syncLabel={sync}
                onBack={() => (screen === "map" ? d.go({ name: "route", deliveryId: null }) : d.go({ name: "home" }))}
                lang={lang}
                onToggleLang={setLang}
                displayName={displayName}
                onSignOut={d.askSignOut}
                onToggleTheme={d.theme}
                isNight={d.dark}
                onMessages={openNotifications}
                unreadMessages={inbox.unread ?? inbox.items.filter((n) => n.readAt === null).length}
              />
            </div>
          )}

          {run.loading ? (
            // A card-shaped skeleton in the Home layout's own shape, not a full-screen
            // spinner: the driver UX plan prioritises a fast-feeling load. The label
            // keeps the exact words the screen used to show, so a screen reader hears
            // the same thing it always did.
            <div className="absolute inset-x-0 top-[96px] flex flex-col gap-3 px-5">
              <div className="h-24 w-full animate-pulse rounded-go-card-l bg-go-card" aria-hidden />
              <SkeletonRows rows={4} label={tr("Loading today's run…")} />
            </div>
          ) : (
            // Where the browser crossfades screens itself (shared/ui/transition.ts), this fade would play twice.
            <div
              key={screen}
              className="absolute inset-0 z-0 animate-fade-in supports-[view-transition-name:none]:animate-none short:overflow-y-auto"
              style={screen === "home" && noticeHeight > 0 ? { top: noticeHeight + 8 } : undefined}
            >
              {screen === "home" && (
                <HomeNoVehicle
                  accountId={userId}
                  driverName={displayName}
                  loadFuelPass={d.fuelPass}
                  depotName={depot}
                  vehicle={run.vehicle}
                  nextRun={run.nextRun}
                  depotCheckIn={d.depotCheckIn}
                  tripStatus={tripStatus(stops)}
                  stopCount={stops.length}
                  unavailable={run.unavailable}
                  online={online}
                  vehicleStatus={d.vehicleStatus}
                  onVehicleStatus={(status) => void d.reportStatus(status)}
                  onStartTrip={() => void d.openRun()}
                  onProblem={() => d.openProblem("run")}
                  inbox={inbox}
                  now={d.now}
                  isNight={d.dark}
                  onToggleTheme={d.theme}
                  hideHeader
                />
              )}
              {screen === "route" && shown && (
                <RouteNextStop
                  stops={routeStops}
                  stopIndex={stops.indexOf(shown)}
                  syncLabel={sync}
                  onSelectStop={(index) => {
                    const stop = stops[index];
                    if (!stop) return;
                    // The tapped row is named before the transition starts, so it moves into the stop's header.
                    flushSync(() => setMovingStop(stop.deliveryId));
                    void d.openStop(stop);
                  }}
                  movingStop={movingStop}
                  onBack={() => d.go({ name: "home" })}
                  onOpenMap={() => d.go({ name: "map" })}
                  onArrived={arrivedAt}
                  onProblem={() => d.openProblem(shown)}
                  isNight={d.dark}
                  onToggleTheme={d.theme}
                  hideHeader
                />
              )}
              {screen === "map" && next && (
                <GoLayer dark={d.dark} top>
                  <RouteMap next={next} outlet={run.outlets[next.outletId]} recorder={d.location} syncedAt={null} className="h-full w-full" />
                </GoLayer>
              )}
              {screen === "report" && reporting && view.name === "report" && formFor !== reporting.deliveryId && (
                // Issue #21: hand over, wait for the store's report, accept it with the store's PIN or say why you move on.
                <StopHandover
                  key={reporting.deliveryId}
                  stop={reporting}
                  stops={routeStops}
                  stopIndex={stops.indexOf(reporting)}
                  syncLabel={sync}
                  online={online}
                  busy={busy}
                  isNight={d.dark}
                  onBack={() => d.go({ name: "route", deliveryId: reporting.deliveryId })}
                  onToggleTheme={d.theme}
                  onHandOver={() => d.handOver(reporting)}
                  onMoveOn={(reason, note) => d.moveOn(reporting, reason, note)}
                  onEnterPin={() => setPinFor(reporting)}
                  onNext={() => void d.toNextStop()}
                  onProblem={() => d.openProblem(reporting)}
                />
              )}
              {screen === "report" && reporting && view.name === "report" && formFor === reporting.deliveryId && (
                <GoLayer dark={d.dark} sync={sync} onBack={() => setFormFor(null)}>
                  <DeliveryReport
                    key={`${reporting.deliveryId}:${view.failed ?? ""}`}
                    stop={reporting}
                    outlet={run.outlets[reporting.outletId]}
                    total={stops.length}
                    busy={busy}
                    timingUncertain={reporting.waiting}
                    startFailed={view.failed}
                    error={error && tr(error)}
                    onConfirm={(result) => void d.confirm(reporting, result)}
                  />
                </GoLayer>
              )}
              {screen === "stop" && detail && (
                <GoLayer dark={d.dark} sync={sync} onBack={() => d.go({ name: "home" })}>
                  <StopDetail
                    key={detail.deliveryId}
                    stop={detail}
                    outlet={run.outlets[detail.outletId]}
                    total={stops.length}
                    proofOnPhone={run.uploadsWaiting.filter((upload) => upload.subject === detail.deliveryId).length}
                    busy={busy}
                    error={error && tr(error)}
                    onProof={(proof) => void d.addProof(detail, proof)}
                  />
                </GoLayer>
              )}
              {screen === "complete" && (
                <RunCompleteScreen
                  onBack={() => d.go({ name: "home" })}
                  onBackToHome={() => d.go({ name: "home" })}
                  isNight={d.dark}
                  onToggleTheme={d.theme}
                  vehicleId={run.vehicle?.vehicleId ?? ""}
                  depotName={depot}
                  stops={stops}
                  uploadsWaiting={run.uploadsWaiting.length}
                  writesWaiting={d.sync.pending - d.sync.held.length}
                  writesHeld={d.sync.held.length}
                  syncedAt={run.syncedAt}
                />
              )}
            </div>
          )}


          {/* What is degraded, said on screen (rule 9): an expired session, a saved copy, location, a refused file. */}
          <div className={cx("absolute inset-x-0 z-40 flex flex-col gap-2 px-5 pointer-events-none", header ? "top-[78px]" : "top-[84px]")}>
            {/* The shell's sync badge opens writes the server refused, for review; MCP is in Settings (#177). */}
            <div className="pointer-events-auto flex items-center justify-end gap-2 empty:hidden">
              {shell?.sync}
            </div>
            <div ref={notices} className="flex flex-col gap-2 empty:hidden">
            {run.expired && !run.loading ? (
              // One solid card, like the location prompt below: the outline button alone let the screen show through.
              <div className="pointer-events-auto flex flex-col gap-2 rounded-[22px] bg-go-card p-2 shadow-go-card">
                <Banner tone="warn" title={tr("You have been signed out")} live>
                  {tr("Nothing on this phone is lost. Sign in again and everything you recorded is sent.")}
                </Banner>
                <OutlineButton onClick={() => window.location.reload()}>{tr("Sign in again")}</OutlineButton>
              </div>
            ) : (
              run.keptAt &&
              !run.loading &&
              screen === "home" && (
                <Banner tone="warn" title={tr("Showing the run saved on this phone")} live>
                  {tr(online ? "Waypoint is not answering." : "This phone is offline.")}{" "}
                  {tr("Everything you record is kept here and sent when the connection is back.")}
                </Banner>
              )
            )}
            {d.problemFor === null && !d.saved && (error || notice) && formFor === null && screen !== "stop" && (
              <div className="pointer-events-auto">
                <Banner tone={error ? "bad" : "good"} title={tr(error ?? notice ?? "")} live />
              </div>
            )}
            {screen === "home" && run.uploadsWaiting.some((upload) => upload.needsReview) && (
              <div className="pointer-events-auto rounded-[18px] bg-go-canvas">
                <RefusedUploads uploads={run.uploadsWaiting} stops={stops} onDiscard={(id) => void d.dropUpload(id)} />
              </div>
            )}
            </div>
          </div>
          {d.location.simulate && d.nextPoint && screen === "route" && (
            // Kept inside the screen's gutter at any width, on one line: the driver button is full width and large by default.
            <div className="absolute inset-x-0 bottom-10 z-40 flex justify-center px-5">
              <OutlineButton className="max-w-[420px] bg-go-canvas min-h-12! px-3! text-[15px]!" onClick={() => d.nextPoint && d.location.simulate?.(d.nextPoint)}>
                {tr(d.location.simulating ? "Driving… (demo)" : "Simulate drive to the next stop (demo)")}
              </OutlineButton>
            </div>
          )}
          {d.location.state === "denied" && next && screen === "route" && (
            // A floating pill over the run, so it reads as a state and not as part of the page.
            <div className="pointer-events-none absolute inset-x-0 bottom-5 z-40 flex justify-center px-4">
              <p
                role="status"
                className="pointer-events-auto flex animate-rise-in items-center gap-3 rounded-full bg-go-card py-1.5 pr-1.5 pl-4 text-[13px] text-go-ink shadow-[0_8px_24px_rgba(0,0,0,0.18)] motion-reduce:animate-none"
              >
                <span>{tr("Location off · the dispatcher sees your stops only")}</span>
                <button
                  type="button"
                  onClick={d.location.allow}
                  className="h-8 shrink-0 rounded-full bg-go-action px-3.5 text-[13px] font-medium text-go-on-action active:scale-95"
                >
                  {tr("Turn on")}
                </button>
              </p>
            </div>
          )}

          {d.problemFor !== null && (
            <ProblemSheet
              stop={d.problemFor === "run" ? null : d.problemFor}
              tripId={d.tripId}
              accountId={userId}
              sender={d.postMessage}
              busy={busy}
              error={error && tr(error)}
              onSend={(problem) => d.report(problem)}
              onClose={d.closeProblem}
            />
          )}
          {d.saved && !pinFor && (
            <SavedSheet
              title={tr(d.saved.title)}
              onPhone={d.saved.onPhone}
              last={d.saved.last}
              warning={d.saved.warning}
              onHandover={d.saved.handedOver ? () => setPinFor(d.saved!.handedOver) : undefined}
              onNext={() => void d.afterSaved()}
            />
          )}
          <DeliveryPinConfirmModal
            isOpen={pinFor !== null}
            onClose={closePin}
            // A correct PIN accepts the store's report: the run moves on behind the
            // "Handover confirmed" popup, with no extra step (issue #21).
            onVerify={async (pin) => {
              const answer = await d.verifyHandover(pinFor!, pin);
              if ("outcome" in answer && (answer.outcome === "VERIFIED" || answer.outcome === "ALREADY_CONFIRMED")) void d.toNextStop();
              return answer;
            }}
            isNight={d.dark}
            stopName={pinFor?.outletId ?? ""}
            online={online}
          />
          <LocationConsentBottomSheet
            isOpen={d.location.needsConsent && screen === "route"}
            onDecline={d.location.decline}
            onAllow={d.location.allow}
            isNight={d.dark}
          />
          <SignOutConfirmBottomSheet
            isOpen={d.leaving}
            onClose={d.cancelSignOut}
            onConfirm={d.signOut}
            waiting={d.waiting}
            online={online}
            isNight={d.dark}
          />
          <RouteChangedBottomSheet
            isOpen={revised !== null}
            onClose={() => {
              if (revised) void inbox.markRead([revised.notificationId]);
              setRevised(null);
            }}
            onViewRoute={() => {
              if (revised) void inbox.markRead([revised.notificationId]);
              setRevised(null);
              void d.openRun();
            }}
            time={revised ? ago(revised.createdAt, d.now) : undefined}
            isNight={d.dark}
          />

          {driving && (
            <div className="absolute inset-0 z-50 animate-fade-in">
              <DrivingModeScreen
                onExit={() => setDrive((s) => slideOut(s, motion))}
                isNight={d.dark}
                stops={routeStops}
                stopIndex={activeIndex(stops)}
                unread={inbox.unread}
                distance={distanceToNext()}
              />
            </div>
          )}
        </div>
      </main>
    </DriverLang>
  );
}

/** The working forms keep the GO design tokens; inside the phone frame they scroll on their own layer. */
function GoLayer({
  dark,
  sync,
  onBack,
  top = false,
  children,
}: {
  dark: boolean;
  sync?: string;
  onBack?: () => void;
  /** Under the persistent header, which brings its own Back. */
  top?: boolean;
  children: React.ReactNode;
}): React.JSX.Element {
  const t = useT();
  return (
    <div className={cx(dark && "go-dark", "absolute inset-0 bg-go-canvas font-go text-go-ink", top && !onBack ? "overflow-hidden pt-[74px]" : "overflow-y-auto", top && onBack && "pt-[74px]")}>
      {onBack && (
        <div className="flex items-center justify-between px-5 pt-5">
          <button type="button" onClick={onBack} className="-ml-2 flex min-h-12 items-center gap-2 rounded-full px-2 text-[19px] font-medium text-go-ink">
            <BackIcon />
            {t("Back")}
          </button>
          {sync && (
            <span role="status" className="rounded-full bg-go-card px-4 py-2 text-[15px] font-medium shadow-go-float">
              {sync}
            </span>
          )}
        </div>
      )}
      {children}
    </div>
  );
}
