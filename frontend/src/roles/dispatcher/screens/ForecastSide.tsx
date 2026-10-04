"use client";

import { Card, CardHead, cx } from "@shared/ui";
import { weekLabel, type Action, type DayNeed } from "../data/forecast.ts";

// The Forecast screen's right column: what an operating day of the heaviest
// weeks needs from the fleet, and what the numbers suggest doing. Both are
// estimates from the weekly forecast (A-40), and say so.

export function DayNeeds({ rows }: { rows: DayNeed[] }): React.JSX.Element {
  return (
    <Card label="What the busiest days need">
      <CardHead title="What the busiest days need" meta="An average operating day of the heaviest weeks" />
      {rows.length === 0 ? (
        <p className="m-0 text-[13px] text-go-secondary">No operating days in the forecast.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[320px] border-collapse text-[13px]">
            <thead>
              <tr className="text-left text-[11px] tracking-wide text-go-secondary uppercase">
                <th scope="col" className="py-1.5 font-medium">Week</th>
                <th scope="col" className="py-1.5 font-medium">m³ / day</th>
                <th scope="col" className="py-1.5 font-medium">Vehicles</th>
                <th scope="col" className="py-1.5 font-medium">Refrig.</th>
                <th scope="col" className="py-1.5 font-medium">Drivers</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const short = r.vehiclesNeeded > r.vehicles;
                const reeferShort = r.refrigeratedNeeded > r.refrigeratedVehicles;
                return (
                  <tr key={r.week.key} className="border-t border-go-surface">
                    <th scope="row" className="py-2 text-left font-medium text-go-ink">
                      {weekLabel(r.week)}
                      {r.week.festival ? <span className="font-normal text-go-secondary"> {r.week.festival}</span> : null}
                    </th>
                    <td className="py-2">{Math.round(r.m3)}</td>
                    <td className={cx("py-2", short && "font-semibold text-go-danger")}>{`${r.vehiclesNeeded} / ${r.vehicles}`}</td>
                    <td className={cx("py-2", reeferShort && "font-semibold text-go-danger")}>
                      {`${r.refrigeratedNeeded} / ${r.refrigeratedVehicles}`}
                    </td>
                    <td className="py-2">{r.drivers}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      <p className="m-0 text-[11px] text-go-secondary">
        Needed against the depot&apos;s fleet under the planning rule for each day. Estimates from the weekly forecast, not a plan.
      </p>
    </Card>
  );
}

const TONES: Record<Action["tone"], string> = {
  danger: "bg-go-danger-tint text-go-danger",
  warning: "bg-go-warning-tint text-go-warning",
  success: "bg-go-success-tint text-go-success",
};

export function ForecastActions({ actions }: { actions: Action[] }): React.JSX.Element {
  return (
    <Card label="Suggested actions">
      <CardHead title="Suggested actions" />
      {actions.length === 0 ? (
        <p className="m-0 text-[13px] text-go-secondary">Nothing stands out: every week fits the fleet.</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2.5 p-0">
          {actions.map((a) => (
            <li key={a.title} className={cx("rounded-go-card-s px-3.5 py-2.5", TONES[a.tone])}>
              <p className="m-0 text-[13px] font-semibold">{a.title}</p>
              <p className="m-0 text-[12px] text-go-ink">{a.detail}</p>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
