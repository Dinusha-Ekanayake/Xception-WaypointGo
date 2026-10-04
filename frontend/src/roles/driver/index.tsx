"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import type { NotificationView } from "@shared/domain/notification";
import { ago } from "@shared/notifications/inbox";
import { useInbox } from "@shared/notifications/useInbox";
import { cx, useDeviceLang, useMedia, useShell } from "@shared/ui";
import { nextStop, type Stop } from "./data/run.ts";
import { activeIndex, syncLabel, toRouteStops, tripStatus, type RouteStop } from "./data/stopView.ts";
import DeliveryPinConfirmModal from "./screens/DeliveryPinConfirmModal.tsx";
import DeliveryReport from "./screens/DeliveryReport.tsx";
import DeliveryReportWaiting from "./screens/DeliveryReportWaiting.tsx";
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
  const d = useDriver(userId);
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

  const header = screen === "home" || screen === "route" || screen === "map";
  const next = nextStop(stops);
  // A tablet held sideways (an in-cab mount) shows the trip map beside the
  // run, so the map is always in view; the Map screen itself stays one column.
  const sideMap = useMedia("(min-width: 1024px) and (orientation: landscape)") && next !== null && screen !== "map";

  return (
    <main
      aria-label="Driver workspace"
      data-theme={d.dark ? "dark" : "light"}
      // The run fills the device: a phone either way up, and on a tablet a
      // column as wide as the screens are drawn for (issue #201). The phone
      // mock-up it sat in before was cut off on a phone held sideways and on a
      // landscape tablet.
      className={cx(
        "flex h-dvh max-h-dvh w-full justify-center overflow-hidden font-go transition-colors",
        d.dark ? "go-dark bg-[#161616] md:bg-[#0a0a0a]" : "bg-[#E7F3F2] md:bg-[#d6e7e5]"
      )}
    >
      {sideMap && next && (
        <aside aria-label="Trip map" className="relative min-w-0 flex-1 overflow-hidden">
          <RouteMap next={next} outlet={run.outlets[next.outletId]} recorder={d.location} syncedAt={run.syncedAt} className="h-full w-full" />
        </aside>
      )}
      <div
        className={cx(
          "relative h-dvh w-full overflow-hidden transition-colors md:max-w-[600px] md:shadow-2xl",
          sideMap && "lg:max-w-[480px]",
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
            />
          </div>
        )}

        {run.loading ? (
          <p role="status" className="absolute inset-x-0 top-[96px] px-8 text-[17px] text-go-muted">
            Loading today's run…
          </p>
        ) : (
          <div key={screen} className="absolute inset-0 animate-fade-in short:overflow-y-auto">
            {screen === "home" && (
              <HomeNoVehicle
                driverName={displayName}
                depotName={depot}
                vehicle={run.vehicle}
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
                onSelectStop={(index) => stops[index] && void d.openStop(stops[index])}
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
                <RouteMap next={next} outlet={run.outlets[next.outletId]} recorder={d.location} syncedAt={run.syncedAt} />
              </GoLayer>
            )}
            {screen === "report" && reporting && view.name === "report" && formFor !== reporting.deliveryId && (
              <DeliveryReportWaiting
                stops={routeStops}
                stopIndex={stops.indexOf(reporting)}
                syncLabel={sync}
                onBack={() => d.go({ name: "route", deliveryId: reporting.deliveryId })}
                onConfirm={() => setFormFor(reporting.deliveryId)}
                onProblem={() => d.openProblem(reporting)}
                isNight={d.dark}
                onToggleTheme={d.theme}
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
            {(screen === "home" || screen === "route") && d.tripId && (
              <button
                type="button"
                onClick={openMessages}
                aria-label={unread.length > 0 ? `Messages, ${unread.length} new` : "Messages"}
                className="flex min-h-10 items-center gap-2 rounded-full bg-go-card px-4 text-[14px] font-medium text-go-ink shadow-go-card"
              >
                Messages
                {unread.length > 0 && <span className="min-w-5 rounded-full bg-go-danger px-1.5 text-center text-[12px] text-white">{unread.length > 99 ? "99+" : unread.length}</span>}
              </button>
            )}
            {shell?.sync}
          </div>
          {run.expired && !run.loading ? (
            <div className="pointer-events-auto flex flex-col gap-2">
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
          {d.location.needsConsent && screen === "route" && (
            // One solid card: on its own the outline buttons were drawn over the stop name.
            <div className="pointer-events-auto flex flex-col gap-2 rounded-[22px] bg-go-card p-2 shadow-go-card">
              <Banner tone="warn" title="Share your location while the run is open?">
                So the dispatcher and the store can see where the truck is. Only while your run is open.
              </Banner>
              <div className="grid grid-cols-2 gap-2">
                <OutlineButton onClick={d.location.decline}>Not now</OutlineButton>
                <OutlineButton onClick={d.location.allow}>Share location</OutlineButton>
              </div>
            </div>
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
        {d.location.state === "denied" && next && screen === "route" && (
          <p role="status" className="absolute inset-x-0 bottom-2 z-40 flex justify-center gap-2 text-[13px] text-go-muted">
            Location off · the dispatcher sees your stops only
            <button type="button" onClick={d.location.allow} className="underline">
              Turn on
            </button>
          </p>
        )}

        {d.problemFor !== null && (
          <ProblemSheet stop={d.problemFor === "run" ? null : d.problemFor} busy={busy} error={error} onSend={(problem) => void d.report(problem)} onClose={d.closeProblem} />
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
    <div className={cx(dark && "go-dark", "absolute inset-0 overflow-y-auto bg-go-canvas font-go text-go-ink", top && "pt-[74px]")}>
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
