"use client";

import { dayLabel } from "@shared/wording";
import type { DayPoint } from "../data/history.ts";

// Figma "02 Overview", the summary chart: trips per day as bars (the last day
// darker) and the share of delivered stops on time as a line on its own scale
// at the right. A day with no delivered stop has no point, not a zero.

const W = 640;
const H = 230;
const PAD = { left: 34, right: 44, top: 14, bottom: 30 };

export default function TripsChart({ points }: { points: DayPoint[] }): React.JSX.Element {
  const maxTrips = Math.max(3, ...points.map((p) => p.trips));
  const unit = [1, 2, 5, 10, 20, 25, 50, 100].find((u) => u * 3 >= maxTrips) ?? Math.ceil(maxTrips / 3);
  const top = unit * 3;
  const lows = points.map((p) => p.onTime).filter((v): v is number => v !== null);
  const floor = Math.min(80, ...lows.map((v) => Math.floor(v / 10) * 10));
  const innerW = W - PAD.left - PAD.right;
  const innerH = H - PAD.top - PAD.bottom;
  const step = innerW / Math.max(points.length, 1);
  const barW = Math.min(42, step * 0.55);
  const x = (i: number) => PAD.left + step * i + step / 2;
  const yTrips = (v: number) => PAD.top + innerH - (v / top) * innerH;
  const yPct = (v: number) => PAD.top + innerH - ((v - floor) / (100 - floor)) * innerH;
  const ticks = [0, unit, unit * 2, unit * 3];
  const pctTicks = [floor, Math.round((floor + 100) / 2), 100];
  const line = points
    .map((p, i) => (p.onTime === null ? null : `${x(i)},${yPct(p.onTime)}`))
    .filter(Boolean)
    .join(" ");
  const labelEvery = points.length > 10 ? Math.ceil(points.length / 7) : 1;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Trips per day and the share on time" className="h-auto w-full">
      {ticks.map((t) => (
        <g key={`t${t}`}>
          <line x1={PAD.left} x2={W - PAD.right} y1={yTrips(t)} y2={yTrips(t)} className="stroke-go-rule" strokeWidth={1} />
          <text x={PAD.left - 8} y={yTrips(t) + 4} textAnchor="end" className="fill-go-secondary text-[10px]">
            {t}
          </text>
        </g>
      ))}
      {pctTicks.map((t) => (
        <text key={`p${t}`} x={W - PAD.right + 6} y={yPct(t) + 4} className="fill-go-teal text-[10px]">
          {`${t}%`}
        </text>
      ))}
      {points.map((p, i) => (
        <g key={p.date}>
          <rect
            x={x(i) - barW / 2}
            y={yTrips(p.trips)}
            width={barW}
            height={Math.max(0, PAD.top + innerH - yTrips(p.trips))}
            rx={3}
            className={i === points.length - 1 ? "fill-go-teal/60" : "fill-go-mint"}
          >
            <title>{`${dayLabel(p.date)}: ${p.trips} ${p.trips === 1 ? "trip" : "trips"}${p.onTime === null ? "" : ` · ${p.onTime}% on time`}`}</title>
          </rect>
          {i % labelEvery === 0 && (
            <text x={x(i)} y={H - 10} textAnchor="middle" className="fill-go-secondary text-[10px]">
              {dayLabel(p.date).split(" ").slice(0, 2).join(" ")}
            </text>
          )}
        </g>
      ))}
      {line && <polyline points={line} fill="none" className="stroke-go-teal" strokeWidth={2} />}
      {points.map((p, i) =>
        p.onTime === null ? null : <circle key={`c${p.date}`} cx={x(i)} cy={yPct(p.onTime)} r={3.5} className="fill-go-card stroke-go-teal" strokeWidth={2} />,
      )}
    </svg>
  );
}
