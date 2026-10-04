"use client";

import { SkeletonRows, cx } from "@shared/ui";
import { combine, dayNeeds, weekLabel } from "../data/forecast.ts";
import { useForecast } from "../data/useForecast.ts";

// Figma "06 Vehicles", the refrigerated card: refrigerated vehicles available
// today against what an average operating day needs, week by week from the
// forecast (#16). The design draws days; the forecast is weekly, so the bars
// are weeks and the card says so. A week that needs more than are available
// is drawn red and named under the chart.

const WEEKS = 4;
const H = 96;

export default function ReeferNeed({
  depots,
  available,
}: {
  depots: string[];
  /** Refrigerated vehicles available today; null while the fleet loads. */
  available: number | null;
}): React.JSX.Element {
  const forecast = useForecast(depots, WEEKS);
  const combined = forecast.data ? combine(forecast.data) : null;
  const needs = combined?.status === "READY" ? [...dayNeeds(combined.weeks, WEEKS)].sort((a, b) => a.week.key - b.week.key) : [];

  if (forecast.error && !combined) return <p className="text-[13px] text-go-secondary">The forecast could not be reached.</p>;
  if (!combined || available === null) return <SkeletonRows rows={2} label="Loading…" />;
  if (needs.length === 0) return <p className="text-[13px] text-go-secondary">No forecast yet for the coming weeks. The weekly run makes one; Forecast shows when.</p>;

  const top = Math.max(available, ...needs.map((n) => n.refrigeratedNeeded), 1);
  const short = needs.find((n) => n.refrigeratedNeeded > available);

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-end gap-3" style={{ height: H + 34 }}>
        <div aria-hidden className="relative w-5 shrink-0 text-[10px] text-go-secondary" style={{ height: H, marginBottom: 20 }}>
          {[0, Math.round(top / 2), top].map((tick) => (
            <span key={tick} className="absolute right-0 translate-y-1/2" style={{ bottom: `${(tick / top) * 100}%` }}>
              {tick}
            </span>
          ))}
        </div>
        {needs.map((n) => {
          const over = n.refrigeratedNeeded > available;
          return (
            <div
              key={n.week.key}
              aria-label={`${weekLabel(n.week)}: ${available} available, ${n.refrigeratedNeeded} needed a day`}
              className="flex flex-1 flex-col items-center gap-1"
            >
              <div className="relative w-full max-w-9" style={{ height: H }}>
                <span className={cx("absolute inset-x-0 bottom-0 flex justify-center rounded-t-[4px] pt-0.5 text-[10px] font-semibold text-white", over ? "bg-go-danger" : "bg-go-teal/80")} style={{ height: `${(available / top) * 100}%` }}>
                  {available}
                </span>
                <span aria-hidden className="absolute -inset-x-1 h-0.5 bg-go-ink" style={{ bottom: `${(n.refrigeratedNeeded / top) * 100}%` }}>
                  <span className={cx("absolute -top-4 right-0 text-[10px] font-semibold", over ? "text-go-danger-strong" : "text-go-ink")}>{n.refrigeratedNeeded}</span>
                </span>
              </div>
              <span className="text-xs text-go-secondary">{weekLabel(n.week)}</span>
            </div>
          );
        })}
      </div>
      <div className="flex flex-wrap gap-3 text-xs text-go-secondary">
        <span className="flex items-center gap-1.5">
          <span aria-hidden className="size-2.5 rounded-[2px] bg-go-teal/80" /> Available today
        </span>
        <span className="flex items-center gap-1.5">
          <span aria-hidden className="h-0.5 w-3 bg-go-ink" /> Needed a day
        </span>
        <span className="flex items-center gap-1.5">
          <span aria-hidden className="size-2.5 rounded-[2px] bg-go-danger" /> Short
        </span>
      </div>
      {short ? (
        <p className="rounded-go-input bg-go-danger-tint px-3 py-2 text-[13px] font-medium text-go-danger-strong">
          {`Short by ${short.refrigeratedNeeded - available} refrigerated ${short.refrigeratedNeeded - available === 1 ? "vehicle" : "vehicles"} · ${weekLabel(short.week)}`}
        </p>
      ) : (
        <p className="text-xs text-go-secondary">{`Enough for the next ${needs.length} weeks' chilled demand${combined.degraded ? " · from recent averages" : ""}`}</p>
      )}
    </div>
  );
}
