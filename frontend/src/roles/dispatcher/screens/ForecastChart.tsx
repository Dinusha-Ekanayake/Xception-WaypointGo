"use client";

import { segments, share, weekDate, weekLabel, type ForecastWeek } from "../data/forecast.ts";

// Figma "Forecast" chart: weekly demand stacked by brand against the fleet's
// weekly capacity, and below it chilled demand against refrigerated capacity.
// Plain SVG, no chart library; a hidden table carries the same numbers for
// screen readers.

const SERIES: Record<string, string> = {
  chilled: "#2f80b7",
  "Fresh-ambient": "#33c98b",
  Style: "#8a4fd8",
  Tech: "#d9622b",
};

export function seriesColour(id: string): string {
  if (id.endsWith("-chilled")) return SERIES.chilled;
  if (id.endsWith("-ambient")) return SERIES[id] ?? "#33c98b";
  return SERIES[id] ?? "#9aa5a3";
}

const W = 660;
const H = 270;
const PLOT_TOP = 34;
const PLOT_BOTTOM = 230;
const LEFT = 40;

export default function ForecastChart({ weeks }: { weeks: ForecastWeek[] }): React.JSX.Element {
  const max = Math.max(1, ...weeks.map((w) => Math.max(w.total, w.fleetM3))) * 1.08;
  const slot = (W - LEFT) / Math.max(1, weeks.length);
  const barW = Math.min(30, slot * 0.46);
  const y = (v: number) => PLOT_BOTTOM - (v / max) * (PLOT_BOTTOM - PLOT_TOP);
  const peak = weeks.reduce<ForecastWeek | null>((a, b) => (!a || b.total > a.total ? b : a), null);
  const ticks = [0, max / 3, (2 * max) / 3].map((t) => Math.round(t / 50) * 50);

  return (
    <figure className="m-0 flex flex-col gap-3">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={`Weekly demand for the next ${weeks.length} weeks against fleet capacity${peak ? `; peak ${weekLabel(peak)} at ${Math.round(peak.total)} cubic metres` : ""}`}
        className="h-auto w-full"
      >
        {ticks.map((t) => (
          <g key={t}>
            <line x1={LEFT} x2={W} y1={y(t)} y2={y(t)} stroke="#e3ebe9" />
            <text x={LEFT - 6} y={y(t) + 3} textAnchor="end" className="fill-go-secondary text-[9px]">
              {t}
            </text>
          </g>
        ))}
        {weeks.map((w, i) => {
          const cx = LEFT + slot * i + slot / 2;
          let base = 0;
          const isPeak = peak?.key === w.key && weeks.length > 1;
          const tag = w.festival
            ? { text: w.festival, tone: "#e5484d" }
            : w.holidayDays > 0 || w.operatingDays < 6
              ? { text: `${w.operatingDays} days`, tone: "#6b7280" }
              : w.paydays > 0
                ? { text: "Payday", tone: "#6b7280" }
                : null;
          return (
            <g key={w.key}>
              {w.fleetM3 > 0 && (
                <line
                  x1={cx - slot * 0.42}
                  x2={cx + slot * 0.42}
                  y1={y(w.fleetM3)}
                  y2={y(w.fleetM3)}
                  stroke="#7c8a87"
                  strokeDasharray="4 3"
                />
              )}
              {segments(w).map((s) => {
                const top = y(base + s.m3);
                const height = y(base) - top;
                base += s.m3;
                return <rect key={s.id} x={cx - barW / 2} y={top} width={barW} height={Math.max(0, height)} fill={seriesColour(s.id)} />;
              })}
              <text x={cx} y={y(w.total) - 4} textAnchor="middle" className={`text-[9px] font-semibold ${isPeak ? "fill-go-danger" : "fill-go-ink"}`}>
                {Math.round(w.total)}
              </text>
              {tag && (
                <text x={cx} y={PLOT_TOP - 16} textAnchor="middle" fill={tag.tone} className="text-[8px]">
                  {tag.text}
                </text>
              )}
              <text x={cx} y={PLOT_BOTTOM + 14} textAnchor="middle" className={`text-[9px] font-medium ${isPeak ? "fill-go-danger" : "fill-go-ink"}`}>
                {weekLabel(w)}
              </text>
              <text x={cx} y={PLOT_BOTTOM + 25} textAnchor="middle" className="fill-go-secondary text-[8px]">
                {weekDate(w)}
              </text>
            </g>
          );
        })}
      </svg>
      <ChilledStrip weeks={weeks} />
      <table className="sr-only">
        <caption>Forecast by week</caption>
        <thead>
          <tr>
            <th scope="col">Week</th>
            <th scope="col">Demand m³</th>
            <th scope="col">Chilled m³</th>
            <th scope="col">Fleet capacity m³</th>
            <th scope="col">Refrigerated capacity m³</th>
          </tr>
        </thead>
        <tbody>
          {weeks.map((w) => (
            <tr key={w.key}>
              <th scope="row">{`${weekLabel(w)} (${weekDate(w)})`}</th>
              <td>{Math.round(w.total)}</td>
              <td>{Math.round(w.chilled)}</td>
              <td>{Math.round(w.fleetM3)}</td>
              <td>{Math.round(w.refrigeratedM3)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </figure>
  );
}

/** Chilled demand as a share of refrigerated capacity: red from 95%, amber from 85%. */
function ChilledStrip({ weeks }: { weeks: ForecastWeek[] }): React.JSX.Element {
  const slot = (W - LEFT) / Math.max(1, weeks.length);
  const barW = Math.min(30, slot * 0.46);
  const h = 70;
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-3">
        <p className="m-0 text-[13px] font-semibold text-go-ink">Chilled against refrigerated vehicle capacity</p>
        <p className="m-0 text-[11px] text-go-secondary">share of the week&apos;s refrigerated capacity</p>
      </div>
      <svg viewBox={`0 0 ${W} ${h + 6}`} role="img" aria-label="Chilled demand as a share of refrigerated capacity, by week" className="h-auto w-full">
        {weeks.map((w, i) => {
          const cx = LEFT + slot * i + slot / 2;
          const load = share(w.chilled, w.refrigeratedM3);
          const shown = Math.min(1, load);
          const colour = load >= 0.95 ? "#e5484d" : load >= 0.85 ? "#b7791f" : "#2f80b7";
          const top = 6 + (1 - shown) * h;
          return (
            <g key={w.key}>
              <line x1={cx - slot * 0.42} x2={cx + slot * 0.42} y1={6} y2={6} stroke="#7c8a87" />
              <rect x={cx - barW / 2} y={top} width={barW} height={h + 6 - top} rx={3} fill={colour} />
              <text x={cx} y={Math.min(top + 12, h)} textAnchor="middle" className="fill-white text-[9px] font-semibold">
                {w.refrigeratedM3 > 0 ? `${Math.round(load * 100)}%` : "-"}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}
