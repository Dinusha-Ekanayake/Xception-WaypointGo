"use client";

import { cx } from "@shared/ui";
import { litres } from "../data/fleet.ts";
import { formatDay } from "../data/scope.ts";
import { useFuel } from "../data/useDay.ts";
import Refusal from "./Refusal.tsx";

// A vehicle's weekly fuel, Monday to Sunday with the return legs (R-PLN-23,
// D-K), as Planning counts it from published plans. It is what the plans will
// burn, not a pump reading: no module records fuel bought.

export default function FuelWeek({ vehicleId, date }: { vehicleId: string; date: string }): React.JSX.Element {
  const fuel = useFuel(vehicleId, date);
  if (fuel.error) return <Refusal error={fuel.error} what="this vehicle's fuel" />;
  if (!fuel.data) return <p className="text-[13px] text-go-secondary">Reading the week's plans…</p>;

  const quota = Number(fuel.data.quotaLitres);
  const used = Number(fuel.data.usedLitres);
  const remaining = Number(fuel.data.remainingLitres);
  const share = quota > 0 ? Math.min(1, used / quota) : 0;
  const over = remaining < 0;
  return (
    <div className="flex flex-col gap-1.5" aria-label="Weekly fuel">
      <div
        role="meter"
        aria-label={`${litres(used)} of ${litres(quota)} planned`}
        aria-valuemin={0}
        aria-valuemax={quota}
        aria-valuenow={used}
        className="h-2 overflow-hidden rounded-full bg-go-surface"
      >
        <div className={cx("h-full rounded-full", over ? "bg-go-danger" : share > 0.85 ? "bg-go-warning" : "bg-go-signal")} style={{ width: `${share * 100}%` }} />
      </div>
      <p className="text-[13px] text-go-ink">
        <span className="font-medium">{litres(used)}</span> planned of {litres(quota)} ·{" "}
        <span className={over ? "font-medium text-go-danger-strong" : "text-go-secondary"}>
          {over ? `${litres(-remaining)} over` : `${litres(remaining)} left`}
        </span>
      </p>
      <p className="text-[11px] text-go-secondary">Week from {formatDay(fuel.data.weekStarting)}, published plans only</p>
    </div>
  );
}
