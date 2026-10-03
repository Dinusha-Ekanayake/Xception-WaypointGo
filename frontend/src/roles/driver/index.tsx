"use client";

import DeliveryReport from "./screens/DeliveryReport.tsx";
import Home from "./screens/Home.tsx";
import RefusedUploads from "./screens/RefusedUploads.tsx";
import Route from "./screens/Route.tsx";
import RouteMap from "./screens/RouteMap.tsx";
import RunComplete from "./screens/RunComplete.tsx";
import { ProblemSheet, SavedSheet, SignOutSheet } from "./screens/Sheets.tsx";
import StopDetail from "./screens/StopDetail.tsx";
import TopBar from "./TopBar.tsx";
import { Banner, OutlineButton } from "./ui.tsx";
import { nextStop } from "./data/run.ts";
import { useDriver } from "./useDriver.ts";

/**
 * The driver's phone (issue #21, Figma "12 · Driver · Mobile"). What each
 * screen does is in useDriver; this file chooses which one is drawn.
 */
export default function Driver({ userId, displayName, scope }: { userId: string; displayName: string; scope: string[] }): React.JSX.Element {
  const d = useDriver(userId);
  const { run, view, screen, shown, reporting, detail, online, error, notice, busy } = d;
  const stops = run.stops;
  return (
    <div className={`${d.dark ? "go-dark " : ""}min-h-dvh bg-go-canvas font-go text-go-ink`} data-theme={d.dark ? "dark" : "light"}>
      <div className="mx-auto flex min-h-dvh w-full max-w-[720px] flex-col">
        <TopBar
          online={online}
          syncedAt={run.syncedAt}
          keptAt={run.keptAt}
          uploads={run.uploadsWaiting.length}
          dark={d.dark}
          onTheme={d.theme}
          onBack={screen === "home" ? undefined : screen === "map" ? () => d.go({ name: "route", deliveryId: null }) : () => d.go({ name: "home" })}
          onSignOut={screen === "home" ? d.askSignOut : undefined}
        />

        {run.expired && !run.loading ? (
          <div className="flex flex-col gap-3 px-5 pt-3">
            <Banner tone="warn" title="You have been signed out" live>
              Nothing on this phone is lost. Sign in again and everything you recorded is sent.
            </Banner>
            <OutlineButton onClick={() => window.location.reload()}>Sign in again</OutlineButton>
          </div>
        ) : (
          run.keptAt &&
          !run.loading && (
            <div className="px-5 pt-3">
              <Banner tone="warn" title="Showing the run saved on this phone" live>
                {online ? "Waypoint is not answering." : "This phone is offline."} You can keep working: everything you record is kept here and sent when the connection is back.
              </Banner>
            </div>
          )
        )}
        {d.location.needsConsent && screen !== "map" && (
          <div className="flex flex-col gap-3 px-5 pt-3">
            <Banner tone="warn" title="Share your location while the run is open?">
              So the dispatcher and the store can see where the truck is. Only while your run is open.
            </Banner>
            <div className="grid grid-cols-2 gap-3">
              <OutlineButton onClick={d.location.decline}>Not now</OutlineButton>
              <OutlineButton onClick={d.location.allow}>Share location</OutlineButton>
            </div>
          </div>
        )}
        {d.location.state === "denied" && nextStop(stops) && screen !== "map" && (
          <p role="status" className="flex items-center gap-2 px-5 pt-3 text-[13px] text-go-muted">
            Location off · the dispatcher sees your stops only
            <button type="button" onClick={d.location.allow} className="underline">Turn on</button>
          </p>
        )}
        {d.problemFor === null && (error || notice) && (
          <div className="px-5 pt-3">
            <Banner tone={error ? "bad" : "good"} title={error ?? notice ?? ""} live />
          </div>
        )}

        {run.loading ? (
          <p role="status" className="px-5 py-10 text-[17px] text-go-muted">
            Loading today's run…
          </p>
        ) : (
          <>
            {screen === "home" && (
              <>
                <Home
                  displayName={displayName}
                  depot={scope[0] ?? ""}
                  vehicle={run.vehicle}
                  stops={stops}
                  unavailable={run.unavailable}
                  online={online}
                  vehicleStatus={d.vehicleStatus}
                  onVehicleStatus={(status) => void d.reportStatus(status)}
                  onOpenRun={() => void d.openRun()}
                  onOpenStop={(stop) => void d.openStop(stop)}
                  onProblem={() => d.openProblem("run")}
                />
                <div className="px-5 pb-8">
                  <RefusedUploads uploads={run.uploadsWaiting} stops={stops} onDiscard={(id) => void d.dropUpload(id)} />
                </div>
              </>
            )}
            {screen === "route" && shown && (
              <Route
                date={run.date}
                stops={stops}
                next={shown}
                outlets={run.outlets}
                busy={busy}
                now={d.now}
                onArrived={(stop) => void d.arrived(stop)}
                onReport={(stop) => d.go({ name: "report", deliveryId: stop.deliveryId, failed: null })}
                onProblem={d.openProblem}
                onOpenMap={() => d.go({ name: "map" })}
              />
            )}
            {screen === "map" && nextStop(stops) && (
              <RouteMap next={nextStop(stops)!} outlet={run.outlets[nextStop(stops)!.outletId]} recorder={d.location} syncedAt={run.syncedAt} />
            )}
            {screen === "report" && reporting && view.name === "report" && (
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
            )}
            {screen === "stop" && detail && (
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
            )}
            {screen === "complete" && run.vehicle && (
              <RunComplete
                vehicleId={run.vehicle.vehicleId}
                stops={stops}
                uploadsWaiting={run.uploadsWaiting.length}
                writesWaiting={d.sync.pending}
                onHome={() => d.go({ name: "home" })}
              />
            )}
          </>
        )}

        {d.problemFor !== null && (
          <ProblemSheet stop={d.problemFor === "run" ? null : d.problemFor} busy={busy} error={error} onSend={(problem) => void d.report(problem)} onClose={d.closeProblem} />
        )}
        {d.saved && <SavedSheet title={d.saved.title} onPhone={d.saved.onPhone} last={d.saved.last} warning={d.saved.warning} onNext={() => void d.afterSaved()} />}
        {d.leaving && <SignOutSheet waiting={d.waiting} online={online} onSignOut={d.signOut} onClose={d.cancelSignOut} />}
      </div>
    </div>
  );
}
