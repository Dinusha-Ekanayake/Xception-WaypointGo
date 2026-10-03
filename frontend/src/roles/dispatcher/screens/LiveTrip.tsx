"use client";

import { useState } from "react";
import type { RunSheetStopView } from "@shared/domain/types";
import { ConnectionStatus, cx } from "@shared/ui";
import { activity, depotClock, etaOf, limits, round1, runTitle, silentMinutes, type Run } from "../data/liveDesk.ts";
import { hhmm } from "../data/plan.ts";
import { useFuel } from "../data/useDay.ts";
import { driverLine, useDriver } from "./LivePanel.tsx";
import { Action, Bar, Chip, NOT_AVAILABLE_NOTE, STATUS } from "./LiveParts.tsx";

// Figma "05 Live · trip" (189:21943), opened from "Open trip": the driver, the
// trip's limits right now, its stops and what each outlet was told, what has
// happened on the trip and a way to send an update. What outlets were told and
// sending an update wait on store and driver messages; they are drawn as
// designed and disabled.

export default function LiveTrip({
  run,
  date,
  depotName,
  now,
  online,
  lastSyncedAt,
  onBack,
}: {
  run: Run;
  date: string;
  depotName: string;
  now: Date;
  online: boolean;
  lastSyncedAt: Date | null;
  onBack: () => void;
}): React.JSX.Element {
  const { name } = useDriver(run);
  const fuel = useFuel(run.day.vehicleId, date);
  const look = STATUS[run.status];
  const next = run.day.current;
  const quiet = silentMinutes(run, now);
  const waiting = run.day.stops.filter((s) => s.outcome === "PENDING" || s.outcome === "ARRIVED");
  const [audience, setAudience] = useState<"driver" | "waiting" | "all">("waiting");
  const lateBy = next?.expectedArrival
    ? Math.round((new Date(next.expectedArrival).getTime() - new Date(`${date}T${hhmm(next.plannedArrival)}:00+05:30`).getTime()) / 60_000)
    : null;

  return (
    <div className="flex w-full flex-col gap-4">
      <header className="flex w-full flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <button type="button" onClick={onBack} className="flex w-fit items-center gap-2 text-[16px] text-go-ink">
            <span aria-hidden className="text-[20px] leading-none">‹</span> Back
          </button>
          <h1 className="text-[28px] leading-tight font-medium text-go-ink">{runTitle(run)}</h1>
          <p className="text-[14px] text-go-secondary">{driverLine(run, name) || `${run.day.done} of ${run.day.stops.length} stops done`}</p>
        </div>
        <div className="flex items-center gap-3">
          <Chip tone={look.tone}>{`${look.label}${next && run.status !== "on-time" ? ` · ${next.outletId}` : ""}`}</Chip>
          <ConnectionStatus online={online} lastSyncedAt={lastSyncedAt} offlineNote="read only" />
        </div>
      </header>

      <div className="grid w-full items-start gap-4 lg:grid-cols-[280px_1fr_300px]">
        <div className="flex flex-col gap-4">
          <Card>
            <div className="flex items-center justify-between gap-2">
              <h2 className="text-[15px] font-medium text-go-ink">{name ?? "Driver"}</h2>
              {run.position && <Chip tone="muted">{`${run.status === "offline" ? "Last seen" : "Last ping"} ${depotClock(run.position.recordedAt)}`}</Chip>}
            </div>
            <p className="text-[14px] text-go-ink">{next ? `On the way to ${next.outletId}` : run.status === "returning" ? `Returning to ${depotName}` : "Run finished"}</p>
            <p className="text-[12.5px] text-go-secondary">
              {waiting.length} {waiting.length === 1 ? "stop" : "stops"} left · {run.status === "offline" ? `no signal${quiet !== null ? ` ${quiet} min` : ""}` : run.position ? "signal ok" : "no live location"}
            </p>
          </Card>

          <Card>
            <h2 className="text-[15px] font-medium text-go-ink">Limits right now</h2>
            {limits(run, fuel.data ?? null, now).map((l) => (
              <div key={l.label} className="flex flex-col gap-1.5">
                <span className="flex justify-between text-[13px]">
                  <span className="text-go-secondary">{l.label}</span>
                  <span className="font-medium text-go-ink">{l.value}</span>
                </span>
                <Bar share={l.share} className={l.share >= 0.95 ? "bg-go-danger" : l.share >= 0.85 ? "bg-[#c08a3e]" : "bg-go-teal"} />
              </div>
            ))}
            {!run.trip && <p className="text-[12.5px] text-go-secondary">The trip&apos;s load and times are not available.</p>}
          </Card>

          <Card>
            <h2 className="text-[15px] font-medium text-go-ink">On the vehicle</h2>
            <p className="text-[14px] text-go-ink">
              {waiting.length} {waiting.length === 1 ? "order" : "orders"} left{run.trip ? ` · ${round1(Number(run.trip.volumeM3))} m³ loaded` : ""}
            </p>
            <ul className="flex flex-col gap-0.5 text-[12.5px] text-go-secondary">
              {waiting.map((s) => (
                <li key={s.deliveryId}>
                  {s.outletId} · {s.mallOutlet ? "mall stop" : "street stop"} · {s.itemCount} units
                </li>
              ))}
            </ul>
          </Card>
        </div>

        <Card>
          <div className="flex items-baseline justify-between">
            <h2 className="text-[15px] font-medium text-go-ink">Stops · what each outlet was told</h2>
            <span className="text-[12px] text-go-secondary">from outlet screens</span>
          </div>
          <table className="w-full text-left text-[13px]">
            <thead>
              <tr className="border-b border-go-rule text-[12px] text-go-secondary">
                <th className="py-2 font-normal">Outlet</th>
                <th className="py-2 font-normal">Expected</th>
                <th className="py-2 font-normal">Status</th>
                <th className="py-2 font-normal">Told</th>
                <th className="py-2 font-normal">Response</th>
              </tr>
            </thead>
            <tbody>
              {run.day.stops.map((s) => {
                const st = stopStatus(s, run, next);
                return (
                  <tr key={s.deliveryId} className={cx("border-b border-go-rule last:border-0", st.done && "text-go-secondary")}>
                    <td className="py-3 font-semibold text-go-ink">
                      <span aria-hidden className={cx("mr-2 inline-block size-2 rounded-full", st.dot)} />
                      {s.outletId}
                    </td>
                    <td className="py-3 tabular-nums">{etaOf(s)}</td>
                    <td className={cx("py-3", st.text)}>{st.label}</td>
                    <td className="py-3 text-go-secondary">-</td>
                    <td className="py-3 text-go-secondary">-</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <p className="text-[12px] text-go-secondary">What each outlet was told is not recorded yet; outlets see their expected arrival on their own screen.</p>
          {next && run.status !== "on-time" && (
            <div className="rounded-2xl bg-go-warning-tint px-3.5 py-3">
              <p className="text-[14px] font-medium text-go-ink">If the window is missed</p>
              <p className="text-[12.5px] text-go-secondary">Deferred automatically · missed window · first on the next delivery day</p>
            </div>
          )}
        </Card>

        <div className="flex flex-col gap-4">
          <Card>
            <h2 className="text-[15px] font-medium text-go-ink">Trip activity</h2>
            <ul className="flex flex-col gap-2.5">
              {activity(run, depotName).map((a, i) => (
                <li key={i} className="flex flex-col">
                  <span className="flex justify-between text-[11.5px] text-go-secondary">
                    <span>{a.at}</span>
                    <span className={a.by === "system" ? "text-go-teal" : ""}>{a.by}</span>
                  </span>
                  <span className="text-[14px] text-go-ink">{a.text}</span>
                </li>
              ))}
              {activity(run, depotName).length === 0 && <li className="text-[12.5px] text-go-secondary">Nothing recorded yet.</li>}
            </ul>
          </Card>

          <Card>
            <h2 className="text-[15px] font-medium text-go-ink">Send an update</h2>
            <div role="group" aria-label="Send to" className="flex flex-wrap gap-1.5">
              {(
                [
                  ["driver", "Driver"],
                  ["waiting", "Waiting outlet"],
                  ["all", "All outlets"],
                ] as const
              ).map(([value, label]) => (
                <button
                  key={value}
                  type="button"
                  aria-pressed={audience === value}
                  onClick={() => setAudience(value)}
                  className={cx("rounded-full px-2.5 py-1 text-[12px] font-medium", audience === value ? "bg-go-ink text-white" : "bg-go-surface text-go-ink")}
                >
                  {label}
                </button>
              ))}
            </div>
            <textarea
              disabled
              rows={2}
              aria-label="Message"
              defaultValue={lateBy !== null && lateBy > 0 && next ? `Running ${lateBy} min late · now expected ${etaOf(next)}` : next ? `On the way · expected ${etaOf(next)}` : ""}
              className="w-full resize-none rounded-xl bg-go-subtle px-3 py-2.5 text-[13px] text-go-secondary"
            />
            <div className="flex gap-2">
              <Action unavailable>Voice</Action>
              <Action primary unavailable className="flex-1">
                {audience === "driver" ? "Send to driver" : audience === "all" ? `Send to ${run.day.stops.length} outlets` : `Send to ${waiting.length} ${waiting.length === 1 ? "outlet" : "outlets"}`}
              </Action>
            </div>
            <p className="text-[11px] text-go-secondary">{NOT_AVAILABLE_NOTE}</p>
          </Card>
        </div>
      </div>
    </div>
  );
}

function Card({ children }: { children: React.ReactNode }): React.JSX.Element {
  return <section className="flex flex-col gap-2.5 rounded-[20px] bg-white px-[18px] py-4 shadow-go-card">{children}</section>;
}

function stopStatus(s: RunSheetStopView, run: Run, next: RunSheetStopView | null): { label: string; dot: string; text: string; done: boolean } {
  if (s.outcome === "DELIVERED") return { label: (s.lateMinutes ?? 0) > 0 ? `Delivered · ${s.lateMinutes} min late` : "Delivered", dot: "bg-go-teal", text: "text-go-teal", done: true };
  if (s.outcome === "PARTIAL") return { label: "Partly delivered", dot: "bg-[#c08a3e]", text: "text-go-warning-text", done: true };
  if (s.outcome === "FAILED") return { label: "Not delivered", dot: "bg-go-danger", text: "text-go-danger-strong", done: true };
  if (s.outcome === "SKIPPED") return { label: "Replanned", dot: "bg-go-divider", text: "text-go-secondary", done: true };
  if (s.outcome === "ARRIVED") return { label: "At the stop", dot: "bg-go-info", text: "text-go-info", done: false };
  if (next?.deliveryId === s.deliveryId && (run.status === "at-risk" || run.status === "late")) return { label: "At risk", dot: "bg-[#c08a3e]", text: "text-go-warning-text", done: false };
  return { label: "Planned", dot: "bg-go-divider", text: "text-go-ink", done: false };
}
