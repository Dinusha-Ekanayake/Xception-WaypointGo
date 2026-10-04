"use client";

import { useEffect, useMemo, useRef, useState } from "react";
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
import HomeNoVehicle from "./screens/HomeNoVehicle.tsx";
import Messages from "./screens/Messages.tsx";
import { unreadMessages } from "./data/messages.ts";
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

/**
 * The driver's phone (issues #21 and #117, Figma "12 · Driver · Mobile"). What
 * each screen does is in useDriver; this file chooses which one is drawn. The
 * Figma screens draw the run sheet Execution serves; the delivery form, the
 * proof, the problem report and the stop detail keep their working forms.
 */
export default function Driver({ userId, displayName, scope }: { userId: string; displayName: string; scope: string[] }): React.JSX.Element {
  const d = useDriver(userId, scope[0] ?? null);
  const inbox = useInbox(userId);
  const shell = useShell();
  const { run, view, screen, shown, reporting, detail, online, error, notice, busy } = d;
  const stops = run.stops;
  const routeStops = useMemo(() => toRouteStops(stops, run.outlets, run.date, d.now), [stops, run.outlets, run.date, d.now]);
  const byId = (id: string): Stop | undefined => stops.find((stop) => stop.deliveryId === id);
  const depot = scope[0] ?? "";
  const sync = syncLabel(online, run.uploadsWaiting.length, run.syncedAt, run.keptAt);

  const [lang, setLang] = useDeviceLang();
  const [driving, setDriving] = useState(false);
  const [formFor, setFormFor] = useState<string | null>(null);
  const [pinFor, setPinFor] = useState<Stop | null>(null);
  const [revised, setRevised] = useState<NotificationView | null>(null);
  const [talking, setTalking] = useState(false);
  // The stop row last tapped on the route, one shared element with the stop's header (UX polish 4).
  const [movingStop, setMovingStop] = useState<string | null>(null);
  const unread = unreadMessages(inbox.items);
  const openMessages = () => {
    setTalking(true);
    if (unread.length > 0 && online) void inbox.markRead(unread.map((n) => n.notificationId)).catch(() => undefined);
  };
  const seen = useRef<Set<string> | null>(null);

  // The delivery form opens from the waiting screen; a stop reported as not
  // delivered from the problem sheet opens it straight away.
  useEffect(() => {
    if (view.name !== "report") return setFormFor(null);
    if (view.failed) setFormFor(view.deliveryId);
  }, [view]);

  // A run sheet revised while the driver is out says so once (Figma "Run sheet changed").
  useEffect(() => {
    const ids = inbox.items.map((item) => item.notificationId);
    if (seen.current === null) {
      if (!inbox.loading) seen.current = new Set(ids);
      return;
    }
    const fresh = inbox.items.find((item) => !seen.current!.has(item.notificationId) && item.readAt === null && item.eventType === "plan.revised");
    ids.forEach((id) => seen.current!.add(id));
    if (fresh) setRevised(fresh);
  }, [inbox.items, inbox.loading]);

  // Space opens driving mode; only its slider closes it.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target;
      if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement || target instanceof HTMLButtonElement) return;
      if ((event.code === "Space" || event.key === " ") && nextStop(stops)) {
        event.preventDefault();
        setDriving(true);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [stops]);

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
    <main
      aria-label="Driver workspace"
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
              {...(d.tripId ? { onMessages: openMessages, unreadMessages: unread.length } : {})}
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
            <SkeletonRows rows={4} label="Loading today's run…" />
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
                {...(d.tripId ? { onOpenMessages: openMessages } : {})}
                driverName={displayName}
                depotName={depot}
                vehicle={run.vehicle}
                nextRun={run.nextRun}
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
                onVerifyPin={(pin) => d.verifyHandover(reporting, pin)}
                onAddProof={() => d.go({ name: "stop", deliveryId: reporting.deliveryId })}
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
                  error={error}
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
                  error={error}
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

        {talking && (
          <Messages
            accountId={userId}
            tripId={d.tripId}
            vehicleId={run.vehicle?.vehicleId ?? null}
            online={online}
            dark={d.dark}
            sender={d.postMessage}
            onBack={() => setTalking(false)}
          />
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
              <Banner tone="warn" title="You have been signed out" live>
                Nothing on this phone is lost. Sign in again and everything you recorded is sent.
              </Banner>
              <OutlineButton onClick={() => window.location.reload()}>Sign in again</OutlineButton>
            </div>
          ) : (
            run.keptAt &&
            !run.loading &&
            screen === "home" && (
              <Banner tone="warn" title="Showing the run saved on this phone" live>
                {online ? "Waypoint is not answering." : "This phone is offline."} Everything you record is kept here and sent when the connection is back.
              </Banner>
            )
          )}
          {d.problemFor === null && !d.saved && (error || notice) && formFor === null && screen !== "stop" && (
            <div className="pointer-events-auto">
              <Banner tone={error ? "bad" : "good"} title={error ?? notice ?? ""} live />
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
          <div className="absolute inset-x-0 bottom-10 z-40 flex justify-center">
            <OutlineButton onClick={() => d.nextPoint && d.location.simulate?.(d.nextPoint)}>
              {d.location.simulating ? "Driving… (demo)" : "Simulate drive to the next stop (demo)"}
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
              <span>Location off · the dispatcher sees your stops only</span>
              <button
                type="button"
                onClick={d.location.allow}
                className="h-8 shrink-0 rounded-full bg-go-action px-3.5 text-[13px] font-medium text-go-on-action active:scale-95"
              >
                Turn on
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
            error={error}
            onSend={(problem) => d.report(problem)}
            onClose={d.closeProblem}
          />
        )}
        {d.saved && !pinFor && (
          <SavedSheet
            title={d.saved.title}
            onPhone={d.saved.onPhone}
            last={d.saved.last}
            warning={d.saved.warning}
            onHandover={d.saved.handedOver ? () => setPinFor(d.saved!.handedOver) : undefined}
            onNext={() => void d.afterSaved()}
          />
        )}
        <DeliveryPinConfirmModal
          isOpen={pinFor !== null}
          onClose={() => setPinFor(null)}
          onVerify={(pin) => d.verifyHandover(pinFor!, pin)}
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
              onExit={() => setDriving(false)}
              isNight={d.dark}
              stops={routeStops}
              stopIndex={activeIndex(stops)}
              onToggleTheme={d.theme}
              unread={inbox.unread}
            />
          </div>
        )}
      </div>
    </main>
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
  return (
    <div className={cx(dark && "go-dark", "absolute inset-0 bg-go-canvas font-go text-go-ink", top && !onBack ? "overflow-hidden pt-[74px]" : "overflow-y-auto", top && onBack && "pt-[74px]")}>
      {onBack && (
        <div className="flex items-center justify-between px-5 pt-5">
          <button type="button" onClick={onBack} className="-ml-2 flex min-h-12 items-center gap-2 rounded-full px-2 text-[19px] font-medium text-go-ink">
            <BackIcon />
            Back
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
