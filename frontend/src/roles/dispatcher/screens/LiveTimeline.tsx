"use client";

import type { ReportMarkView, RunSheetStopView } from "@shared/domain/types";
import { REPORT_LABEL, ROLE_LABEL } from "@shared/messaging/thread";
import { ChatIcon, cx } from "@shared/ui";
import { hhmm } from "../data/plan.ts";
import { etaOf, progress, routeLabel, runTitle, type Run } from "../data/liveDesk.ts";
import { Bar, Chip, STATUS as LOOK } from "./LiveParts.tsx";

// Figma "05 Live: timeline": one row per vehicle on a shared clock, a dot per
// stop at its delivered or predicted arrival, a line at the time now. A dot is
// a reading of the run sheet, so it moves as each stop closes. A report on the
// trip's thread is the pulsing "Exception · click to open" sign at its time; it
// opens the thread at that report (issue #136).


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

/** Figma "On the road" card: status dot, title and chip, ETA against the window, and a bar of stops closed. */
export function RoadCard({ run, onSelect }: { run: Run; onSelect: () => void }): React.JSX.Element {
  const look = LOOK[run.status];
  const next = run.day.current;
  const alert = run.status === "at-risk" || run.status === "late";
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-label={`${runTitle(run)}, ${look.label}`}
      className={cx(
        "flex w-full flex-col gap-1.5 rounded-2xl border px-3.5 py-3 text-left transition-colors hover:bg-go-subtle",
        alert ? "border-[#c08a3e]/70 bg-go-warning-tint/40" : "border-go-divider bg-white",
      )}
    >
      <span className="flex items-center gap-2">
        <span aria-hidden className={cx("size-2 shrink-0 rounded-full", look.dot)} />
        <span className="min-w-0 flex-1 truncate text-[14px] font-medium text-go-ink">{runTitle(run)}</span>
        <Chip tone={look.tone}>{look.label}</Chip>
      </span>
      <span className="text-[12px] text-go-secondary">
        {run.status === "returning"
          ? `All ${run.day.stops.length} stops closed · back to ${run.depot ?? "the depot"}`
          : next
            ? `Expected ${etaOf(next)} · window ${hhmm(next.windowClose)}`
            : `${run.day.done} of ${run.day.stops.length} closed`}
      </span>
      <Bar share={run.status === "returning" ? 1 : progress(run)} className={look.bar} />
    </button>
  );
}

/** The reports on a run's trips; a report made before the trips were known matches by vehicle. */
export function reportsOf(run: Run, reports: ReportMarkView[]): ReportMarkView[] {
  const trips = new Set(run.day.stops.map((s) => s.tripId));
  return reports.filter((r) => trips.has(r.tripId) || (r.vehicleId !== null && r.vehicleId === run.day.vehicleId && trips.size === 0));
}

export default function LiveTimeline({
  runs,
  now,
  onOpen,
  reports = [],
  onReport,
  onMessages,
}: {
  runs: Run[];
  now: Date;
  onOpen: (vehicleId: string) => void;
  reports?: ReportMarkView[];
  onReport?: (report: ReportMarkView) => void;
  /** Opens the run's trip thread, whether or not anyone has reported (issue #136). */
  onMessages?: (run: Run) => void;
}): React.JSX.Element {
  const nowMinute = minutesOfInstant(now.toISOString());
  const marks = new Map(runs.map((r) => [r.day.vehicleId, reportsOf(r, reports)]));
  const all = runs.flatMap((r) => [
    ...r.day.stops.map(stopMinute),
    ...(r.trip?.releasedAt ? [minutesOfInstant(r.trip.releasedAt)] : []),
    ...(marks.get(r.day.vehicleId) ?? []).map((m) => minutesOfInstant(m.at)),
  ]);
  const first = all.length ? Math.min(...all, nowMinute) : 8 * 60;
  const last = all.length ? Math.max(...all, nowMinute) : 18 * 60;
  // Fewer labels on a long day, so they never run into each other on a narrow screen.
  const rough = Math.ceil((last + 30) / 60) * 60 - Math.floor((first - 45) / 60) * 60;
  const step = rough <= 8 * 60 ? 60 : rough <= 14 * 60 ? 120 : 180;
  const start = Math.floor((first - 45) / step) * step;
  const end = Math.ceil((last + 30) / step) * step;
  const span = Math.max(end - start, 60);
  const at = (minute: number): string => `${Math.min(100, Math.max(0, ((minute - start) / span) * 100))}%`;
  const hours = Array.from({ length: Math.floor(span / step) + 1 }, (_, i) => start + i * step);
  const edge = (minute: number): string => (minute - start < span * 0.04 ? "translate-x-0" : end - minute < span * 0.04 ? "-translate-x-full" : "-translate-x-1/2");
  const showNow = nowMinute >= start && nowMinute <= end;

  /** The waving "Exception · click to open" sign, centred on the run's line; it opens the newest report. */
  const sign = (list: ReportMarkView[], minute: number, key: string, outlet?: string): React.JSX.Element => {
    const mark = list[list.length - 1]!;
    const time = clockLabel(minutesOfInstant(mark.at));
    const where = outlet ? ` at ${outlet}` : "";
    return (
      <button
        key={key}
        type="button"
        data-testid="report-sign"
        data-outlet={outlet}
        onClick={() => onReport?.(mark)}
        title={`Exception · click to open · ${REPORT_LABEL[mark.reportType]}${where} · ${ROLE_LABEL[mark.authorRole]} · ${time}`}
        aria-label={`${REPORT_LABEL[mark.reportType]}${where} reported by the ${ROLE_LABEL[mark.authorRole].toLowerCase()} at ${time}${list.length > 1 ? `, and ${list.length - 1} more` : ""}, open the messages`}
        className="absolute top-1/2 z-30 flex h-7 w-7 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full before:absolute before:-inset-1.5"
        style={{ left: at(minute) }}
      >
        <span aria-hidden className="absolute inset-0 rounded-full bg-go-danger/20" />
        <span
          aria-hidden
          className="relative flex h-5 w-5 origin-[50%_85%] animate-wave items-center justify-center rounded-full bg-go-danger text-[11px] font-bold leading-none text-white ring-2 ring-white motion-reduce:animate-none"
        >
          !
        </span>
        {list.length > 1 && (
          <span aria-hidden className="absolute -top-1.5 -right-1.5 min-w-4 rounded-full bg-go-ink px-1 text-[9px] font-semibold leading-4 text-white">
            {list.length}
          </span>
        )}
      </button>
    );
  };

  return (
    <section aria-label="Timeline" className="[--lab:180px] xl:[--lab:210px] flex min-w-0 flex-1 flex-col gap-3 rounded-[24px] bg-white p-4 shadow-go-card">
      <div className="flex">
        <span className="w-[var(--lab)] shrink-0 px-1 text-xs text-go-secondary">Run</span>
        <div className="relative h-5 flex-1">
          {hours.filter((h) => !showNow || Math.abs(h - nowMinute) > step / 3).map((h) => (
            <span key={h} className={`absolute whitespace-nowrap text-xs tabular-nums text-go-secondary ${edge(h)}`} style={{ left: at(h) }}>
              {clockLabel(h)}
            </span>
          ))}
          {showNow && (
            <span className={`absolute -top-0.5 z-10 whitespace-nowrap ${edge(nowMinute)} rounded-md bg-go-ink px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-white`} style={{ left: at(nowMinute) }}>
              Now {clockLabel(nowMinute)}
            </span>
          )}
        </div>
      </div>

      <div className="relative">
        {hours.map((h) => (
          <span key={h} aria-hidden className="absolute bottom-0 top-0 w-px bg-go-rule" style={{ left: `calc(var(--lab) + (100% - var(--lab)) * ${((h - start) / span).toFixed(4)})` }} />
        ))}
        {showNow && <span aria-hidden className="absolute bottom-0 top-0 z-10 w-px bg-go-ink" style={{ left: `calc(var(--lab) + (100% - var(--lab)) * ${((nowMinute - start) / span).toFixed(4)})` }} />}
        {runs.length === 0 && (
          <p className="relative z-20 mx-auto my-6 w-fit rounded-full bg-white px-4 py-2 text-center text-[13px] text-go-secondary">
            No vehicle matches. A vehicle appears here when the loader releases it.
          </p>
        )}
        {runs.map((run) => {
          const day = run.day;
          const look = LOOK[run.status];
          const last = day.stops[day.stops.length - 1];
          const minutes = day.stops.map(stopMinute);
          const lead = run.trip?.releasedAt ? minutesOfInstant(run.trip.releasedAt) : Math.min(...minutes) - 30;
          const sorted = [...day.stops].sort((a, b) => a.sequence - b.sequence);
          // Reports about a stop on this run sit on that stop; the rest at the time they were made.
          const rowMarks = marks.get(day.vehicleId) ?? [];
          const stopOutlets = new Set(sorted.map((st) => st.outletId));
          const onStop = new Map<string, ReportMarkView[]>();
          for (const m of rowMarks) {
            if (m.outletId && stopOutlets.has(m.outletId)) onStop.set(m.outletId, [...(onStop.get(m.outletId) ?? []), m]);
          }
          const loose = rowMarks.filter((m) => !m.outletId || !stopOutlets.has(m.outletId));
          return (
            <div key={day.vehicleId} className="relative flex min-h-[86px] items-center border-t border-go-rule py-3">
              {onMessages && (
                <button
                  type="button"
                  data-testid="run-messages"
                  onClick={() => onMessages(run)}
                  aria-label={`Messages for ${day.vehicleId}`}
                  title={`Messages for ${day.vehicleId}`}
                  className="absolute top-2.5 left-[calc(var(--lab)-36px)] z-30 flex size-8 items-center justify-center rounded-full bg-go-surface text-go-teal transition-colors before:absolute before:-inset-1 hover:bg-go-soft"
                >
                  <ChatIcon className="size-[17px]" />
                  {(marks.get(day.vehicleId) ?? []).length > 0 && (
                    <span aria-hidden className="absolute -top-0.5 -right-0.5 size-2.5 rounded-full bg-go-danger ring-2 ring-white" />
                  )}
                </button>
              )}
              <button type="button" onClick={() => onOpen(day.vehicleId)} className="relative z-20 flex w-[var(--lab)] shrink-0 flex-col gap-0.5 px-1 text-left text-[12.5px] text-go-ink">
                <span className="flex items-center gap-2">
                  <span className="text-[14px] font-medium">{day.vehicleId}</span>
                  <Chip tone={look.tone}>{look.label}</Chip>
                </span>
                <span>
                  {[routeLabel(run), run.trip ? `T${run.trip.tripNumber}` : null].filter(Boolean).join(" · ") || `${day.stops.length} stops`}
                </span>
                <span className="text-go-secondary">
                  {run.status === "offline" && run.position
                    ? `No signal · last seen ${clockLabel(minutesOfInstant(run.position.recordedAt))}`
                    : run.status === "returning"
                      ? `${day.done} of ${day.stops.length} delivered · returning`
                      : day.current && last
                        ? `${day.stops.length - day.done === 1 ? "Last stop" : `${day.stops.length - day.done} stops`} · expected ${etaOf(last)} · window ${hhmm(last.windowClose)}`
                        : "Run finished"}
                </span>
              </button>
              <div className="relative h-6 flex-1">
                <span aria-hidden className="absolute left-0 right-0 top-1/2 h-px bg-go-divider" />
                <span title="Departed depot" className="absolute top-1/2 h-2 w-2 -translate-x-1/2 -translate-y-1/2 bg-go-ink" style={{ left: at(lead) }} />
                {sorted.map((stop) => {
                  // A report about this stop takes the stop's place on the line.
                  const reported = onStop.get(stop.outletId);
                  if (reported) return sign(reported, stopMinute(stop), stop.deliveryId, stop.outletId);
                  const exception = stop.outcome === "FAILED" || (stop.lateMinutes ?? 0) > 0;
                  const delivered = stop.outcome === "DELIVERED" || stop.outcome === "PARTIAL";
                  const delayed = !delivered && (run.status === "at-risk" || run.status === "late") && stop.outcome === "PENDING";
                  const estimated = !delivered && run.status === "offline";
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
                      className={`absolute top-1/2 flex h-3 w-3 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full border-2 ${dot} ${estimated ? "border-dashed" : ""} ${exception ? "ring-4 ring-go-danger/25" : ""}`}
                      style={{ left: at(stopMinute(stop)) }}
                    >
                      {exception && <span className="h-1.5 w-1.5 rounded-full bg-go-danger" />}
                    </span>
                  );
                })}
                {loose.map((mark) => sign([mark], minutesOfInstant(mark.at), mark.messageId))}
                {run.status === "returning" && (
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
        <li className="flex items-center gap-1.5"><span className="flex h-3 w-3 items-center justify-center rounded-full bg-go-danger text-[8px] font-bold text-white">!</span>Report · click to open</li>
      </ul>
    </section>
  );
}
