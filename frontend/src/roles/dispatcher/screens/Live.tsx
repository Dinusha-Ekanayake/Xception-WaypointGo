"use client";

import { useEffect, useMemo, useState } from "react";
import type { RunSheetStopView } from "@shared/domain/types";
import { KpiCard, Notice, Pill, Segmented, type Tone } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import { attention, byUrgency, isLate, totals, vehicleDay, type VehicleDay } from "../data/live.ts";
import { hhmm } from "../data/plan.ts";
import { dayLabel } from "../data/scope.ts";
import { useLive } from "../data/useDay.ts";
import DayPicker from "./DayTools.tsx";
import { Retry } from "./Orders.tsx";
import LiveMapView from "./LiveMap.tsx";
import Refusal from "./Refusal.tsx";

// Figma "05 Live": a Map view (LiveMap.tsx, issue #161) and a Timeline view,
// the list of each vehicle's stops in order. The timeline is also the way to
// every vehicle without the map. Run sheets are read every 30 seconds and
// positions every 15 while the tab is visible and online; the header says when.

const STATE: Record<VehicleDay["state"], string> = {
  "not-started": "Released, not started",
  driving: "On the way to",
  "at-stop": "At",
  finished: "Run finished",
};

const VIEW_KEY = "waypoint.dispatcher.live-view";

function storedView(): "map" | "timeline" {
  try {
    return window.localStorage.getItem(VIEW_KEY) === "timeline" ? "timeline" : "map";
  } catch {
    return "map";
  }
}

const DOCK: Record<string, { label: string; tone: Tone }> = {
  NOT_STARTED: { label: "Not started", tone: "muted" },
  IN_PROGRESS: { label: "Loading", tone: "info" },
  BLOCKED: { label: "Blocked", tone: "danger" },
  READY: { label: "Ready to release", tone: "success" },
  COMPLETED: { label: "Released", tone: "success" },
};

export default function Live({
  depots,
  scopeLabel,
  date,
  onDate,
  online,
}: {
  depots: string[];
  scopeLabel: string;
  date: string;
  onDate: (date: string) => void;
  online: boolean;
}): React.JSX.Element {
  const live = useLive(depots, date);
  const [now, setNow] = useState(() => new Date());
  const [openId, setOpenId] = useState<string | null>(null);
  const [view, setView] = useState<"map" | "timeline">("timeline");
  useEffect(() => setView(storedView()), []);
  const chooseView = (next: "map" | "timeline") => {
    setView(next);
    try {
      window.localStorage.setItem(VIEW_KEY, next);
    } catch {
      // Remembered for this visit only.
    }
  };

  // "Running late" compares a window with the time now, so the time moves.
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const sheets = live.data?.sheets ?? [];
  const dock = (live.data?.dock ?? []).filter((trip) => trip.releasedAt === null);
  const days = useMemo(() => byUrgency(sheets.map((sheet) => vehicleDay(sheet, now))), [sheets, now]);
  const needs = useMemo(() => attention(sheets, now), [sheets, now]);
  const t = totals(days, live.data?.dock ?? []);

  return (
    <>
      <PageHeader
        title={`Live · ${dayLabel(date)}`}
        subtitle={`${live.data ? `${t.onTheRoad} on the road · ${t.atDock} at the dock` : "Loading"} · ${scopeLabel}`}
        online={online}
        lastSyncedAt={live.loadedAt}
        onSync={live.refresh}
        syncing={live.loading}
        tools={
          <div className="flex items-center gap-2">
            <Segmented label="View" value={view} onChange={chooseView} options={[{ value: "map", label: "Map" }, { value: "timeline", label: "Timeline" }]} />
            <DayPicker date={date} onDate={onDate} />
          </div>
        }
      />
      {live.error && <Refusal error={live.error} what="the live view" action={<Retry onClick={live.refresh} />} />}

      <div className="flex w-full gap-3.5 max-md:flex-col">
        <KpiCard label="On the road" value={live.data ? t.onTheRoad : "…"} note={`${t.finished} finished`} />
        <KpiCard label="Stops done" value={live.data ? `${t.stopsDone} of ${t.stops}` : "…"} note="recorded by drivers" />
        <KpiCard label="At the dock" value={live.data ? t.atDock : "…"} note="trips not released yet" />
        <KpiCard label="Need you" value={live.data ? needs.length : "…"} note="not delivered, late, or proof owed" valueClassName={needs.length ? "text-go-danger-strong" : "text-go-ink"} />
      </div>

      {view === "map" && <LiveMapView depots={depots} depotOf={live.data?.depotOf ?? {}} date={date} days={days} now={now} />}
      {!online && view === "map" && <Notice tone="warning" title="Live updates are paused" />}

      <div className={`${view === "map" ? "hidden" : "flex"} min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col`}>
        <section aria-label="Vehicles on the road" className="flex min-w-0 flex-1 flex-col gap-2.5 rounded-[24px] bg-white p-4 shadow-go-card">
          <div className="flex items-baseline justify-between px-1">
            <h2 className="text-[17px] font-medium text-go-ink">On the road</h2>
            <span className="text-xs text-go-secondary">most urgent first</span>
          </div>
          {live.data && days.length === 0 && (
            <p className="py-8 text-center text-[13px] text-go-secondary">No vehicle has left the dock on {dayLabel(date)}. A vehicle appears here when the loader releases it.</p>
          )}
          {days.map((day) => (
            <VehicleCard key={day.vehicleId} day={day} date={date} now={now} open={openId === day.vehicleId} onToggle={() => setOpenId(openId === day.vehicleId ? null : day.vehicleId)} />
          ))}
        </section>

        <div className="flex w-full flex-col gap-[18px] lg:max-w-[340px]">
          <section aria-label="Needs you" className="flex flex-col gap-2 rounded-[24px] bg-white p-4 shadow-go-card">
            <h2 className="px-1 text-[17px] font-medium text-go-ink">Needs you</h2>
            {needs.length === 0 ? (
              <p className="px-1 text-[13px] text-go-secondary">Nothing is late or failed.</p>
            ) : (
              <ul className="flex flex-col gap-1.5">
                {needs.map((item) => (
                  <li key={`${item.kind}-${item.stop.deliveryId}`}>
                    <button type="button" onClick={() => setOpenId(item.vehicleId)} className="flex w-full items-center gap-2 rounded-go-card bg-go-subtle px-3 py-2 text-left text-[13px] text-go-ink">
                      <span className="min-w-0 flex-1 truncate">
                        <span className="font-medium">{item.vehicleId}</span> · stop {item.stop.sequence} · {item.stop.outletId}
                      </span>
                      <Pill tone={item.kind === "failed" ? "danger" : "warning"}>{item.kind === "failed" ? "Not delivered" : item.kind === "late" ? "Late" : "Proof owed"}</Pill>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section aria-label="At the dock" className="flex flex-col gap-2 rounded-[24px] bg-white p-4 shadow-go-card">
            <h2 className="px-1 text-[17px] font-medium text-go-ink">At the dock</h2>
            {dock.length === 0 ? (
              <p className="px-1 text-[13px] text-go-secondary">{live.data ? "No trip is waiting at the dock." : "Loading…"}</p>
            ) : (
              <ul className="flex flex-col">
                {dock.map((trip) => (
                  <li key={trip.tripId} className="flex items-center gap-2 border-t border-go-rule px-1 py-2 text-[13px] text-go-ink first:border-t-0">
                    <span className="min-w-0 flex-1 truncate">
                      <span className="font-medium">{trip.vehicleId}</span> · T{trip.tripNumber} · {trip.districtName}
                      <span className="text-go-secondary"> · departs {hhmm(trip.plannedDeparture)}</span>
                    </span>
                    <Pill tone={DOCK[trip.status]?.tone ?? "muted"}>{DOCK[trip.status]?.label ?? trip.status}</Pill>
                  </li>
                ))}
              </ul>
            )}
          </section>
          {!online && <Notice tone="warning" title="Live updates are paused" />}
        </div>
      </div>
    </>
  );
}

const SEGMENT: Record<RunSheetStopView["outcome"], string> = {
  PENDING: "bg-go-divider",
  ARRIVED: "bg-go-info",
  DELIVERED: "bg-go-signal",
  PARTIAL: "bg-go-warning",
  FAILED: "bg-go-danger",
  SKIPPED: "bg-go-divider",
};

const OUTCOME: Record<RunSheetStopView["outcome"], { label: string; tone: Tone }> = {
  PENDING: { label: "To do", tone: "muted" },
  ARRIVED: { label: "At the stop", tone: "info" },
  DELIVERED: { label: "Delivered", tone: "success" },
  PARTIAL: { label: "Partly delivered", tone: "warning" },
  FAILED: { label: "Not delivered", tone: "danger" },
  SKIPPED: { label: "Replanned", tone: "muted" },
};

function VehicleCard({ day, date, now, open, onToggle }: { day: VehicleDay; date: string; now: Date; open: boolean; onToggle: () => void }): React.JSX.Element {
  const risk: { label: string; tone: Tone } =
    day.risk === "failed" ? { label: `${day.failed} not delivered`, tone: "danger" } : day.risk === "late" ? { label: `${day.late} late`, tone: "warning" } : { label: "On time", tone: "success" };
  return (
    <div className={`rounded-go-card border ${day.risk === "failed" ? "border-go-danger" : day.risk === "late" ? "border-go-warning" : "border-go-rule"}`}>
      <button type="button" aria-expanded={open} onClick={onToggle} className="flex w-full flex-col gap-2 px-4 py-3 text-left">
        <span className="flex flex-wrap items-center gap-2">
          <span className="text-[15px] font-medium text-go-ink">{day.vehicleId}</span>
          <span className="min-w-0 flex-1 truncate text-[13px] text-go-secondary">
            {STATE[day.state]}
            {day.current && day.state !== "not-started" ? ` ${day.current.outletId}` : ""}
            {day.current ? ` · window closes ${hhmm(day.current.windowClose)}` : ""}
          </span>
          <Pill tone={risk.tone}>{risk.label}</Pill>
        </span>
        <span role="img" aria-label={`${day.done} of ${day.stops.length} stops done`} className="flex gap-1">
          {day.stops.map((stop) => (
            <span key={stop.deliveryId} className={`h-1.5 flex-1 rounded-full ${SEGMENT[stop.outcome]}`} />
          ))}
        </span>
      </button>
      {open && (
        <ol className="flex flex-col px-4 pb-3">
          {day.stops.map((stop) => (
            <li key={stop.deliveryId} className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-go-rule py-2 text-[13px] text-go-ink">
              <span className="w-6 tabular-nums text-go-secondary">{String(stop.sequence).padStart(2, "0")}</span>
              <span className="min-w-[120px] flex-1">
                <span className="font-medium">{stop.outletId}</span>
                <span className="text-go-secondary">
                  {" "}
                  · window {hhmm(stop.windowOpen)} to {hhmm(stop.windowClose)} · planned {hhmm(stop.plannedArrival)}
                </span>
              </span>
              {isLate(date, stop, now) && <Pill tone="warning">{stop.lateMinutes ? `${stop.lateMinutes} min late` : "Running late"}</Pill>}
              {(stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL") && !stop.proofCaptured && <Pill tone="warning">Proof owed</Pill>}
              <Pill tone={OUTCOME[stop.outcome].tone}>{OUTCOME[stop.outcome].label}</Pill>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
