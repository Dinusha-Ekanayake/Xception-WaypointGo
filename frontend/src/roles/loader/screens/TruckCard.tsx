"use client";

import type { ManifestView, SessionStatus } from "@shared/domain/types";
import { Icon, Notice, cx } from "@shared/ui";
import { clockTime, hhmm, kg, m3, timeToDeparture } from "../data/manifest.ts";
import { useT } from "../i18n.tsx";
import { Bar, BigButton, Ring, TempBadge } from "../ui.tsx";

// Figma 05 Load sheet, the truck card: status, the completion ring, volume and
// weight against the vehicle's limits (order-level totals, never product lines),
// who holds the trip, progress against departure, and the two actions.

export default function TruckCard({
  m,
  status,
  percent,
  checked,
  total,
  flagged,
  left,
  pace,
  totals,
  weightCap,
  volumeCap,
  editable,
  busy,
  planChanged,
  waiting,
  sample,
  onStart,
  onHandBack,
  onReport,
  onRelease,
}: {
  m: ManifestView;
  status: SessionStatus;
  percent: number;
  checked: number;
  total: number;
  flagged: number;
  left: number;
  pace: "on" | "behind" | null;
  totals: { weight: number; volume: number };
  weightCap: number;
  volumeCap: number;
  editable: boolean;
  busy: boolean;
  planChanged: boolean;
  waiting: number;
  /** Sample data only: publish a plan change under the loader. */
  sample?: () => void;
  onStart: () => void;
  onHandBack: () => void;
  onReport: () => void;
  /** Opens the release sheet, with the reason it cannot go ahead yet, if any. */
  onRelease: (blockedBy: string | null) => void;
}): React.JSX.Element {
  const tr = useT();
  return (
      <section aria-label="Truck" className="flex flex-col gap-4 rounded-[31px] bg-go-card px-[22px] py-5 shadow-[0_5px_20px_rgba(0,0,0,0.09)] lg:sticky lg:top-[132px]">
        <div className="flex items-center gap-2.5">
          <span className="rounded-full bg-go-action px-3.5 py-1.5 text-[15px] font-medium text-go-on-action">
            {tr(status === "COMPLETED" ? "Released" : status === "NOT_STARTED" ? "Not started" : status === "READY" ? "Ready to release" : "Loading")}
          </span>
          <TempBadge temperature={m.temperature} />
          <span className="flex-1" />
          <span className="text-right text-[13px] text-go-muted md:hidden">
            {m.vehicleId} · {tr("Trip {n}", { n: m.tripNumber })}
            <br />
            {tr("Departs {time}", { time: hhmm(m.plannedDeparture) })}
          </span>
        </div>
        <div className="flex justify-center py-2">
          <Ring percent={percent} />
        </div>
        <Bar label="Volume" value={`${m3(totals.volume)} / ${m3(volumeCap)}`} share={volumeCap ? totals.volume / volumeCap : 0} />
        <Bar label="Weight" value={`${kg(totals.weight)} / ${kg(weightCap)}`} share={weightCap ? totals.weight / weightCap : 0} />
        {editable && (
          <div className="flex items-center gap-2 rounded-[16px] bg-go-canvas py-2 pr-2 pl-4">
            <Icon name="lock" />
            <span className="flex-1 text-[14px] font-medium text-go-success">
              {m.holder ? tr("Locked to you since {time}", { time: clockTime(m.holder.since) }) : tr("Locked to you")}
            </span>
            <button
              type="button"
              onClick={onHandBack}
              className="min-h-12 rounded-full bg-go-card px-4 text-[14px] font-medium text-go-ink"
            >
              {tr("Hand back")}
            </button>
          </div>
        )}
        <div className="flex flex-col gap-1">
          <p className="text-[28px] font-semibold">
            {tr("{a} of {b} orders loaded", { a: checked, b: total })}
          </p>
          <p className={cx("text-[15px] font-medium", pace === "behind" ? "text-go-warning-text" : "text-go-success")}>
            {[
              left === 0 && tr("All loaded"),
              timeToDeparture(m.plannedDeparture) && tr("{n} to departure", { n: timeToDeparture(m.plannedDeparture) }),
              left > 0 && pace && tr(pace === "behind" ? "behind pace" : "on pace"),
            ].filter(Boolean).join(" · ")}
          </p>
          {flagged > 0 && <p className="text-[15px] font-medium text-go-danger-strong">{tr("{n} reported to the dispatcher", { n: flagged })}</p>}
        </div>

        {status === "NOT_STARTED" && (
          <BigButton size="l" onClick={onStart} disabled={busy}>
            {tr("Start loading")}
          </BigButton>
        )}
        {editable && (
          <div className="flex flex-wrap gap-3">
            <BigButton tone="danger" size="l" fit onClick={onReport}>
              {tr("Report issue")}
            </BigButton>
            <BigButton
              tone={left === 0 && !planChanged ? "mint" : "muted"}
              size="l"
              fit
              onClick={() =>
                onRelease(
                  waiting > 0
                    ? tr("Some checks are still saved only on this phone. Release once they are sent.")
                    : planChanged
                      ? tr("The plan changed. Confirm the change and recheck the marked orders first.")
                      : null,
                )
              }
            >
              {left === 0 ? tr("Release vehicle") : tr("Release · {n} left", { n: left })}
            </BigButton>
          </div>
        )}
        {status === "COMPLETED" && (
          <Notice tone="info" title={tr("Vehicle released")}>
            {tr("The driver can depart. Reports made here stay with the trip.")}
          </Notice>
        )}
        {sample && editable && (
          <button
            type="button"
            onClick={sample}
            className="min-h-12 text-[13px] text-go-warning-text underline"
          >
            Sample data: publish a plan change
          </button>
        )}
      </section>
  );
}
