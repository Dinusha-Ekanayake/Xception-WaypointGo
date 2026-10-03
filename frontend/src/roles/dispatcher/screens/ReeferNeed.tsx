"use client";

import { cx } from "@shared/ui";
import { combine, dayNeeds } from "../data/forecast.ts";
import { useForecast } from "../data/useForecast.ts";

// The Vehicles rail's refrigerated card: today's available reefers against the
// reefers an average operating day of next week needs, from the forecast (#16).

export default function ReeferNeed({
  depots,
  available,
}: {
  depots: string[];
  /** Refrigerated vehicles available today; null while the fleet loads. */
  available: number | null;
}): React.JSX.Element {
  const forecast = useForecast(depots, 1);
  const combined = forecast.data ? combine(forecast.data) : null;
  const need = combined?.status === "READY" ? dayNeeds(combined.weeks, 1)[0] : undefined;

  if (forecast.error && !combined) {
    return <p className="m-0 text-[13px] text-go-secondary">The forecast could not be reached.</p>;
  }
  if (!combined || available === null) {
    return <p className="m-0 text-[13px] text-go-secondary">Loading…</p>;
  }
  if (!need) {
    return <p className="m-0 text-[13px] text-go-secondary">No forecast yet for next week.</p>;
  }
  const short = need.refrigeratedNeeded > available;
  return (
    <div className="flex flex-col gap-1">
      <p className={cx("m-0 text-[22px] font-semibold", short ? "text-go-danger" : "text-go-ink")}>
        {`${available} / ${need.refrigeratedNeeded}`}
      </p>
      <p className="m-0 text-[12px] text-go-secondary">
        {short
          ? `${need.refrigeratedNeeded - available} short for next week's chilled demand`
          : "Enough for next week's chilled demand"}
        {combined.degraded ? " · from recent averages" : ""}
      </p>
    </div>
  );
}
