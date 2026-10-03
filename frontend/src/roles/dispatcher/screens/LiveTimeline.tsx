"use client";

import type { RunSheetStopView } from "@shared/domain/types";
import { Pill, type Tone } from "@shared/ui";
import { mapStatus, type MapStatus, type VehicleDay } from "../data/live.ts";
import { hhmm } from "../data/plan.ts";

// Figma "05 Live: timeline": one row per vehicle on a shared clock, a dot per
// stop at its delivered or predicted arrival, a line at the time now. A dot is
// a reading of the run sheet, so it moves as each stop closes.

const STATUS: Record<MapStatus, { label: string; tone: Tone }> = {
  "on-time": { label: "On time", tone: "success" },
  "at-risk": { label: "At risk", tone: "warning" },
  late: { label: "Late", tone: "danger" },
  returning: { label: "Returning", tone: "info" },
  offline: { label: "Offline", tone: "muted" },
};

const OFFSET_MINUTES = 330;

function minutesOfDay(time: string): number {
  const [h, m] = time.split(":");
  return Number(h) * 60 + Number(m);
}

function minutesOfInstant(iso: string): number {
  const d = new Date(iso);
  return (d.getUTCHours() * 60 + d.getUTCMinutes() + OFFSET_MINUTES) % 1440;
}

/** Where the stop sits on the clock: when it happened, else when it is expected. */
function stopMinute(stop: RunSheetStopView): number {
  const actual = stop.completedAt ?? stop.arrivedAt;
  if (actual) return minutesOfInstant(actual);
  if (stop.expectedArrival) return minutesOfInstant(stop.expectedArrival);
  return minutesOfDay(stop.plannedArrival);
}

const clockLabel = (minutes: number): string => `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;

export type TimelineFilter = "all" | "at-risk" | "offline";

/** The vehicles a depot and status filter keep, shared by the header chips, the timeline and the cards. */
export function filterDays(allDays: VehicleDay[], depotOf: Record<string, string>, date: string, now: Date, depot: string, filter: TimelineFilter) {
  const statusOf = (d: VehicleDay): MapStatus => mapStatus(date, d, null, now);
  const risky = (d: VehicleDay): boolean => statusOf(d) === "at-risk" || statusOf(d) === "late";
  const inDepot = allDays.filter((d) => depot === "all" || depotOf[d.vehicleId] === depot);
  const days = inDepot.filter((d) => filter === "all" || (filter === "at-risk" ? risky(d) : statusOf(d) === "offline"));
  return { days, inDepot, atRisk: inDepot.filter(risky).length, offline: inDepot.filter((d) => statusOf(d) === "offline").length };
}

const BAR: Record<MapStatus, string> = { "on-time": "bg-go-signal", "at-risk": "bg-go-warning", late: "bg-go-danger", returning: "bg-go-info", offline: "bg-go-secondary" };

/** Figma "05 Live: timeline" right panel card: status dot, ETA and a progress bar. */
export function TimelineCard({ day, depot, date, now }: { day: VehicleDay; depot?: string; date: string; now: Date }): React.JSX.Element {
  const key = mapStatus(date, day, null, now);
  const status = STATUS[key];
  const done = day.stops.length ? day.done / day.stops.length : 0;
  const border = key === "at-risk" || key === "late" ? "border-go-warning bg-go-warning/5" : "border-go-rule";
  return (
    <div className={`flex flex-col gap-1.5 rounded-go-card border px-3 py-2.5 ${border}`}>
      <span className="flex items-center gap-2">
        <span className={`h-2 w-2 shrink-0 rounded-full ${BAR[key]}`} />
        <span className="min-w-0 flex-1 truncate text-[13px] font-medium text-go-ink">
          {day.vehicleId}
          {depot ? ` · ${depot}` : ""}
        </span>
        <Pill tone={status.tone}>{status.label}</Pill>
      </span>
      <span className="text-[11px] text-go-secondary">
        {day.current ? `Expected ${clockLabel(stopMinute(day.current))} · window ${hhmm(day.current.windowClose)}` : `${day.done} of ${day.stops.length} delivered · run finished`}
      </span>
      <span className="h-1 w-full rounded-full bg-go-subtle">
        <span className={`block h-1 rounded-full ${BAR[key]}`} style={{ width: `${Math.round(done * 100)}%` }} />
      </span>
    </div>
  );
}

export default function LiveTimeline({ days, depotOf, date, now }: { days: VehicleDay[]; depotOf: Record<string, string>; date: string; now: Date }): React.JSX.Element {
  const nowMinute = minutesOfInstant(now.toISOString());
  const all = days.flatMap((day) => day.stops.map(stopMinute));
  const first = all.length ? Math.min(...all, nowMinute) : 8 * 60;
  const last = all.length ? Math.max(...all, nowMinute) : 18 * 60;
  const start = Math.floor((first - 45) / 60) * 60;
  const end = Math.ceil((last + 30) / 60) * 60;
  const span = Math.max(end - start, 60);
  const at = (minute: number): string => `${Math.min(100, Math.max(0, ((minute - start) / span) * 100))}%`;
  const hours = Array.from({ length: Math.floor(span / 60) + 1 }, (_, i) => start + i * 60);
  const showNow = nowMinute >= start && nowMinute <= end;

  return (
    <section aria-label="Timeline" className="flex min-w-0 flex-1 flex-col gap-3 rounded-[24px] bg-white p-4 shadow-go-card">
      <div className="flex">
        <span className="w-[210px] shrink-0 px-1 text-xs text-go-secondary">Run</span>
        <div className="relative h-5 flex-1">
          {hours.map((h) => (
            <span key={h} className="absolute -translate-x-1/2 text-xs tabular-nums text-go-secondary" style={{ left: at(h) }}>
              {clockLabel(h)}
            </span>
          ))}
          {showNow && (
            <span className="absolute -top-0.5 -translate-x-1/2 rounded-md bg-go-ink px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-white" style={{ left: at(nowMinute) }}>
              Now {clockLabel(nowMinute)}
            </span>
          )}
        </div>
      </div>

      <div className="relative">
        {hours.map((h) => (
          <span key={h} aria-hidden className="absolute bottom-0 top-0 w-px bg-go-rule" style={{ left: `calc(210px + (100% - 210px) * ${((h - start) / span).toFixed(4)})` }} />
        ))}
        {showNow && <span aria-hidden className="absolute bottom-0 top-0 z-10 w-px bg-go-ink" style={{ left: `calc(210px + (100% - 210px) * ${((nowMinute - start) / span).toFixed(4)})` }} />}
        {days.length === 0 && <p className="py-8 text-center text-[13px] text-go-secondary">No vehicle has left the dock. A vehicle appears here when the loader releases it.</p>}
        {days.map((day) => {
          const status = STATUS[mapStatus(date, day, null, now)];
          const minutes = day.stops.map(stopMinute);
          const lead = Math.min(...minutes) - 30;
          const sorted = [...day.stops].sort((a, b) => a.sequence - b.sequence);
          return (
            <div key={day.vehicleId} className="relative flex min-h-[64px] items-center border-t border-go-rule py-3">
              <div className="flex w-[210px] shrink-0 flex-col gap-0.5 px-1 text-[12px] text-go-secondary">
                <span className="flex items-center gap-2">
                  <span className="text-[13px] font-medium text-go-ink">{day.vehicleId}</span>
                  <Pill tone={status.tone}>{status.label}</Pill>
                </span>
                <span>
                  {depotOf[day.vehicleId] ? `${depotOf[day.vehicleId]} · ` : ""}
                  {day.done} of {day.stops.length} delivered
                </span>
                <span>
                  {day.current ? `Expected ${clockLabel(stopMinute(day.current))} · window ${hhmm(day.current.windowClose)}` : "Run finished"}
                </span>
              </div>
              <div className="relative h-6 flex-1">
                <span aria-hidden className="absolute left-0 right-0 top-1/2 h-px bg-go-divider" />
                <span title="Departed depot" className="absolute top-1/2 h-2 w-2 -translate-x-1/2 -translate-y-1/2 bg-go-ink" style={{ left: at(lead) }} />
                {sorted.map((stop) => {
                  const exception = stop.outcome === "FAILED" || (stop.lateMinutes ?? 0) > 0;
                  const delivered = stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL";
                  const delayed = !delivered && day.risk !== "ok" && stop.outcome === "PENDING";
                  if (exception) {
                    return (
                      <span key={stop.deliveryId} title={`Exception · stop ${stop.sequence} · ${stop.outletId} · ${clockLabel(stopMinute(stop))}`} className="absolute top-1/2 z-20 flex h-6 w-6 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border border-go-danger/40 bg-go-danger/10" style={{ left: at(stopMinute(stop)) }}>
                        <span className="flex h-4 w-4 items-center justify-center rounded-full bg-go-danger text-[10px] font-bold leading-none text-white ring-2 ring-go-danger/30">!</span>
                      </span>
                    );
                  }
                  const dot = delivered ? "bg-go-signal border-go-signal" : delayed ? "border-go-warning bg-white" : "border-go-secondary bg-white";
                  return (
                    <span
                      key={stop.deliveryId}
                      title={`Stop ${stop.sequence} · ${stop.outletId} · ${clockLabel(stopMinute(stop))}`}
                      className={`absolute top-1/2 flex h-3 w-3 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border-2 ${dot} ${stop.expectedArrival || delivered ? "" : "border-dashed"} ${exception ? "ring-4 ring-go-danger/25" : ""}`}
                      style={{ left: at(stopMinute(stop)) }}
                    >
                      {exception && <span className="h-1.5 w-1.5 rounded-full bg-go-danger" />}
                    </span>
                  );
                })}
                {mapStatus(date, day, null, now) === "returning" && (
                  <span title="Back at depot" className="absolute top-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 border-2 border-go-info bg-white" style={{ left: at(Math.max(...minutes) + 45) }} />
                )}
              </div>
            </div>
          );
        })}
      </div>

      <p className="px-1 text-[11px] text-go-secondary">Dots sit at predicted arrival: ETAs update as each stop closes</p>
      <ul className="flex flex-wrap gap-x-4 gap-y-1 rounded-full bg-go-subtle px-4 py-2 text-[11px] text-go-secondary">
        <li className="flex items-center gap-1.5"><span className="h-2 w-2 bg-go-ink" />Departed depot</li>
        <li className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full bg-go-signal" />Delivered</li>
        <li className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full border-2 border-go-secondary" />Planned</li>
        <li className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full border-2 border-go-warning" />At risk</li>
        <li className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full border-2 border-dashed border-go-secondary" />Estimated (no signal)</li>
        <li className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 border-2 border-go-info" />Back at depot</li>
        <li className="flex items-center gap-1.5"><span className="h-2.5 w-2.5 rounded-full bg-go-danger ring-2 ring-go-danger/25" />Exception</li>
      </ul>
    </section>
  );
}
