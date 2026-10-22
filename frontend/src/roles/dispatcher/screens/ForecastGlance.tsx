"use client";

import { Card, CardHead, LinkAction, cx } from "@shared/ui";
import { dayLabel } from "@shared/wording";
import { combine, forecastSource, weekAhead, type WeekAhead } from "../data/forecast.ts";
import { useForecast } from "../data/useForecast.ts";

// Task 2A where the dispatcher plans: the next two weeks' forecast against what
// the fleet can carry those weeks (each day's available vehicles and trips),
// overall and in the refrigerated vehicles. The Forecast screen has the ten weeks; this says
// only whether the coming weeks fit, and plainly whether a model or the
// estimate made the numbers.

const AHEAD = 2;

const TONE: Record<WeekAhead["tone"], string> = {
  over: "text-go-danger",
  tight: "text-go-warning-text",
  fine: "text-go-secondary",
};

const percent = (share: number | null) => (share === null ? null : !Number.isFinite(share) ? "no refrigerated vehicle" : `${Math.round(share * 100)}%`);

export default function ForecastGlance({ depots, onOpen }: { depots: string[]; onOpen: () => void }): React.JSX.Element | null {
  const read = useForecast(depots, AHEAD);
  if (read.error || !read.data) return null;
  const forecast = combine(read.data);
  const weeks = forecast.weeks.slice(0, AHEAD).map(weekAhead);
  return (
    <Card label="Demand ahead">
      <CardHead title="Demand ahead" meta={forecastSource(forecast)} action={<LinkAction onClick={onOpen}>Forecast</LinkAction>} />
      {weeks.length === 0 ? (
        <p className="text-[13px] text-go-secondary">No forecast for the coming weeks yet.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {weeks.map((w) => {
            const fleet = percent(w.fleetShare);
            const cold = percent(w.reeferShare);
            return (
              <li key={w.week.key} className="flex flex-col gap-0.5 text-[13px]">
                <span className="font-medium text-go-ink">{`Week from ${dayLabel(w.week.start)} · ${Math.round(w.week.total)} m³`}</span>
                <span className={cx(TONE[w.tone])}>
                  {[
                    fleet ? `${fleet} of the fleet's room` : null,
                    w.week.chilled > 0 ? `chilled ${Math.round(w.week.chilled)} m³${cold ? `, ${cold} of the refrigerated room` : ""}` : null,
                    w.tone === "over" ? "more than it can carry" : w.tone === "tight" ? "close to full" : null,
                  ].filter(Boolean).join(" · ")}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}
