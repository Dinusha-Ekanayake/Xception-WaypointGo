"use client";

import { focusScale, refrigeratedNeeded, segments, share, weekDate, weekLabel, type ForecastWeek } from "../data/forecast.ts";

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

/** A segment's colour by its id from segments(): "<brand>-chilled" or "<brand>-ambient". */
export function seriesColour(id: string): string {
  if (id.endsWith("-chilled")) return SERIES.chilled;
  const brand = id.replace(/-ambient$/, "");
  return SERIES[id] ?? SERIES[brand] ?? "#9aa5a3";
}

type Tag = { text: string; ink: string; fill: string };

/** The week's calendar note, as a pill above its bar: festival first, then a short week, then payday. */
function tagFor(w: ForecastWeek): Tag | null {
  if (w.festival) return { text: w.festival, ink: "#c7353a", fill: "#fde8e8" };
  if (w.holidayDays > 0 || w.operatingDays < 6) {
    return { text: `Holiday · ${w.operatingDays} ${w.operatingDays === 1 ? "day" : "days"}`, ink: "#4f5e5b", fill: "#eef2f1" };
  }
  if (w.paydays > 0) return { text: w.paydays > 1 ? `${w.paydays} paydays` : "Payday", ink: "#4f5e5b", fill: "#eef2f1" };
  return null;
}

const W = 660;
const H = 270;
const PLOT_TOP = 52;
const PLOT_BOTTOM = 230;
const LEFT = 40;

export default function ForecastChart({ weeks }: { weeks: ForecastWeek[] }): React.JSX.Element {
  const scale = focusScale(weeks.map((w) => w.total), weeks.map((w) => w.fleetM3), 4);
  const max = scale.top;
  const slot = (W - LEFT) / Math.max(1, weeks.length);
  const barW = Math.min(30, slot * 0.46);
  const y = (v: number) => PLOT_BOTTOM - (Math.min(v, max) / max) * (PLOT_BOTTOM - PLOT_TOP);
  const peak = weeks.reduce<ForecastWeek | null>((a, b) => (!a || b.total > a.total ? b : a), null);
  // The capacity of a full week, labelled once at the right like the design.
  const fleet = weeks.reduce((m, w) => Math.max(m, w.fleetM3), 0);
  const peakUse = peak && peak.fleetM3 > 0 ? Math.round((peak.total / peak.fleetM3) * 100) : null;

  return (
    <figure className="m-0 flex flex-col gap-3">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        role="img"
        aria-label={`Weekly demand for the next ${weeks.length} weeks against fleet capacity${peak ? `; peak ${weekLabel(peak)} at ${Math.round(peak.total)} cubic metres` : ""}`}
        className="h-auto w-full"
      >
        {scale.ticks.map((t) => (
          <g key={t}>
            <line x1={LEFT} x2={W} y1={y(t)} y2={y(t)} stroke="#e3ebe9" />
            <text x={LEFT - 6} y={y(t) + 3} textAnchor="end" className="fill-go-secondary text-[9px]">
              {t.toLocaleString("en-GB")}
            </text>
          </g>
        ))}
        {fleet > 0 && scale.capacityOnScale && (
          <text x={W} y={y(fleet) - 5} textAnchor="end" className="fill-go-secondary text-[8.5px] font-medium">
            {`fleet ≈ ${Math.round(fleet).toLocaleString("en-GB")} m³/wk`}
          </text>
        )}
        {fleet > 0 && !scale.capacityOnScale && (
          // Capacity far above demand: one dashed line on the top edge says so,
          // with how much of it the peak week uses, instead of flattening every bar.
          <g>
            <line x1={LEFT} x2={W} y1={PLOT_TOP - 14} y2={PLOT_TOP - 14} stroke="#7c8a87" strokeDasharray="4 3" />
            <text x={W} y={PLOT_TOP - 19} textAnchor="end" className="fill-go-secondary text-[8.5px] font-medium">
              {`fleet ≈ ${Math.round(fleet).toLocaleString("en-GB")} m³/wk, above the scale${peakUse !== null ? ` · peak week uses ${peakUse}%` : ""}`}
            </text>
          </g>
        )}
        {weeks.map((w, i) => {
          const cx = LEFT + slot * i + slot / 2;
          let base = 0;
          const isPeak = peak?.key === w.key && weeks.length > 1;
          const tag = tagFor(w);
          const valueY = y(w.total) - 5;
          const tagW = tag ? tag.text.length * 4.4 + 12 : 0;
          const tagY = Math.max(2, valueY - 23);
          return (
            <g key={w.key}>
              {w.fleetM3 > 0 && scale.capacityOnScale && (
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
              <text x={cx} y={valueY} textAnchor="middle" className={`text-[9px] font-semibold ${isPeak ? "fill-go-danger" : "fill-go-ink"}`}>
                {Math.round(w.total)}
              </text>
              {tag && (
                <g>
                  <rect x={cx - tagW / 2} y={tagY} width={tagW} height={13} rx={6.5} fill={tag.fill} />
                  <text x={cx} y={tagY + 9.2} textAnchor="middle" fill={tag.ink} className="text-[8px] font-medium">
                    {tag.text}
                  </text>
                </g>
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

const STRIP_H = 96;
const STRIP_TOP = 12;
const NEEDS_ROW = 16;
/** Below this many units a bar cannot hold its label, so the label sits above it. */
const LABEL_INSIDE_MIN = 18;

function loadColour(load: number): string {
  return load >= 0.95 ? "#e5484d" : load >= 0.85 ? "#c08a3e" : "#2f80b7";
}

/**
 * Figma "Chilled vs refrigerated vehicle capacity": each week's chilled demand
 * under its bar above, labelled with its share of refrigerated capacity, and
 * under it the refrigerated vehicles an average day of the week needs, out of
 * those the depot has. Capacity is ticked per week (lower in a short week) when
 * it is near demand, as in the design; when it is far above, the bars follow
 * demand so the weeks can be compared, and the header says so. Amber from 85%,
 * red from 95%.
 */
function ChilledStrip({ weeks }: { weeks: ForecastWeek[] }): React.JSX.Element {
  const slot = (W - LEFT) / Math.max(1, weeks.length);
  const barW = Math.min(30, slot * 0.46);
  const scale = focusScale(weeks.map((w) => w.chilled), weeks.map((w) => w.refrigeratedM3), 4);
  const plot = STRIP_H - STRIP_TOP;
  const y = (v: number) => STRIP_H - (Math.min(v, scale.top) / scale.top) * plot;
  const typical = weeks.reduce<ForecastWeek | null>((a, b) => (!a || b.refrigeratedM3 > a.refrigeratedM3 ? b : a), null);
  const peakShare = Math.max(0, ...weeks.map((w) => share(w.chilled, w.refrigeratedM3)));

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <div>
          <p className="m-0 text-[14px] font-semibold text-go-ink">Chilled vs refrigerated vehicle capacity</p>
          <p className="m-0 text-[11px] text-go-secondary">
            {scale.capacityOnScale
              ? "Share of refrigerated capacity · line = capacity · vehicles needed a day below"
              : `Share of refrigerated capacity, peak ${Math.round(peakShare * 100)}% · vehicles needed a day below`}
          </p>
        </div>
        {typical && typical.refrigeratedM3 > 0 && (
          <p className="m-0 text-[12px] text-go-secondary">
            {`Capacity ${Math.round(typical.refrigeratedM3).toLocaleString("en-GB")} m³/wk · ${typical.refrigeratedVehicles} ${typical.refrigeratedVehicles === 1 ? "vehicle" : "vehicles"}`}
          </p>
        )}
      </div>
      <svg
        viewBox={`0 0 ${W} ${STRIP_H + NEEDS_ROW}`}
        role="img"
        aria-label="Chilled demand as a share of refrigerated vehicle capacity, and the refrigerated vehicles an average day needs, by week"
        className="h-auto w-full"
      >
        <line x1={LEFT} x2={W} y1={STRIP_H - 0.5} y2={STRIP_H - 0.5} stroke="#e3ebe9" />
        <text x={LEFT - 6} y={STRIP_H + 12} textAnchor="end" className="fill-go-secondary text-[8px]">
          per day
        </text>
        {weeks.map((w, i) => {
          const cx = LEFT + slot * i + slot / 2;
          const load = share(w.chilled, w.refrigeratedM3);
          const colour = loadColour(load);
          const top = y(w.chilled);
          const height = STRIP_H - top;
          const inside = height >= LABEL_INSIDE_MIN;
          const label = w.refrigeratedM3 > 0 ? `${Math.round(load * 100)}%` : "No capacity";
          const needed = refrigeratedNeeded(w);
          const short = w.refrigeratedVehicles > 0 && needed > w.refrigeratedVehicles;
          return (
            <g key={w.key}>
              {scale.capacityOnScale && w.refrigeratedM3 > 0 && (
                <line
                  x1={cx - slot * 0.42}
                  x2={cx + slot * 0.42}
                  y1={y(w.refrigeratedM3)}
                  y2={y(w.refrigeratedM3)}
                  stroke="#7c8a87"
                  strokeWidth={2}
                />
              )}
              {height > 0 && <rect x={cx - barW / 2} y={top} width={barW} height={height} rx={4} fill={colour} />}
              <text
                x={cx}
                y={inside ? top + 13 : top - 5}
                textAnchor="middle"
                fill={inside ? "#ffffff" : colour}
                className="text-[9.5px] font-semibold"
              >
                {label}
              </text>
              {w.refrigeratedVehicles > 0 && (
                <text
                  x={cx}
                  y={STRIP_H + 12}
                  textAnchor="middle"
                  className={`text-[8.5px] font-medium ${short ? "fill-go-danger" : "fill-go-secondary"}`}
                >
                  {`${needed} of ${w.refrigeratedVehicles}`}
                </text>
              )}
            </g>
          );
        })}
      </svg>
    </div>
  );
}
