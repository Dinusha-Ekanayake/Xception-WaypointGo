"use client";

import { useState } from "react";
import { Card, CardHead, Notice, PrimaryButton, SecondaryButton } from "@shared/ui";
import { friendlyError } from "@shared/api/problem";
import { dayLabel } from "@shared/wording";
import type { CapacityAction, RecordCapacityDecision } from "@shared/domain/types";
import { useCommand } from "../data/useCommand.ts";
import { ACTION_LABEL, ACTIONS, RECORD_CAPACITY_DECISION, decisionSummary, useCapacityDecisions } from "../data/capacityDecisions.ts";
import { weekDate, weekLabel, type ForecastWeek } from "../data/forecast.ts";

// Roadmap T2-01: what the dispatcher decided about a week the capacity plan
// shows short, with a reason (rule 8). Decisions are only ever added; a change
// of mind is a second decision, so the trail keeps both.

const input = "rounded-go-input border border-go-rule bg-white px-3 py-2.5 text-[14px] font-normal outline-none focus:border-go-teal";

export function ForecastDecisions({ depots, weeks }: { depots: string[]; weeks: ForecastWeek[] }): React.JSX.Element {
  const decisions = useCapacityDecisions(depots);
  const { busy, run } = useCommand();
  const [open, setOpen] = useState(false);
  const [depot, setDepot] = useState(depots[0] ?? "");
  const [weekKey, setWeekKey] = useState<number | null>(weeks[0]?.key ?? null);
  const [action, setAction] = useState<CapacityAction>("HIRE_VEHICLES");
  const [vehicles, setVehicles] = useState("1");
  const [refrigerated, setRefrigerated] = useState(true);
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);

  const week = weeks.find((w) => w.key === weekKey) ?? null;
  const hiring = action === "HIRE_VEHICLES";
  const count = Number(vehicles);
  const ready = !!week && !!depot && reason.trim().length >= 3 && (!hiring || (Number.isInteger(count) && count >= 1 && count <= 60));

  const save = async () => {
    if (!week || !ready) return;
    const payload: RecordCapacityDecision = {
      depotCode: depot,
      isoYear: week.isoYear,
      isoWeek: week.isoWeek,
      action,
      ...(hiring || vehicles.trim() ? { vehicles: count } : {}),
      refrigerated,
      reason: reason.trim(),
    };
    const sent = await run(RECORD_CAPACITY_DECISION, payload, null);
    if (!sent.ok) {
      setError(friendlyError(sent.error));
      return;
    }
    setError(null);
    setReason("");
    setOpen(false);
    decisions.refresh();
  };

  const recorded = decisions.data ?? [];
  return (
    <Card label="Decisions">
      <CardHead title="Decisions" meta="What was decided about short weeks, and why" />
      {decisions.error && !decisions.data && <Notice tone="warning" title="Decisions could not be loaded" />}
      {recorded.length === 0 && !decisions.error ? (
        <p className="m-0 text-[13px] text-go-secondary">No decisions recorded for the weeks ahead.</p>
      ) : (
        <ul className="m-0 flex list-none flex-col gap-2 p-0">
          {recorded.slice(0, 6).map((d) => (
            <li key={d.decisionId} className="rounded-go-card-s bg-go-surface px-3.5 py-2.5">
              <p className="m-0 text-[13px] font-semibold">
                {decisionSummary(d)} · week {d.isoWeek}
                {depots.length > 1 ? ` · ${d.depotCode}` : ""}
              </p>
              <p className="m-0 text-[12px] text-go-ink">{d.reason}</p>
              <p className="m-0 text-[11px] text-go-secondary">Recorded {dayLabel(d.decidedAt.slice(0, 10))}</p>
            </li>
          ))}
        </ul>
      )}

      {!open ? (
        <SecondaryButton onClick={() => setOpen(true)} disabled={weeks.length === 0 || depots.length === 0}>
          Record a decision
        </SecondaryButton>
      ) : (
        <form
          className="flex flex-col gap-2.5"
          aria-label="Record a decision"
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          {depots.length > 1 && (
            <label className="flex flex-col gap-1 text-[13px] font-medium">
              Depot
              <select className={input} value={depot} onChange={(e) => setDepot(e.target.value)}>
                {depots.map((d) => (
                  <option key={d} value={d}>
                    {d}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="flex flex-col gap-1 text-[13px] font-medium">
            Week
            <select className={input} value={weekKey ?? ""} onChange={(e) => setWeekKey(Number(e.target.value))}>
              {weeks.map((w) => (
                <option key={w.key} value={w.key}>
                  {`${weekLabel(w)} · from ${weekDate(w)}${w.festival ? ` · ${w.festival}` : ""}`}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-[13px] font-medium">
            Decision
            <select className={input} value={action} onChange={(e) => setAction(e.target.value as CapacityAction)}>
              {ACTIONS.map((a) => (
                <option key={a} value={a}>
                  {ACTION_LABEL[a]}
                </option>
              ))}
            </select>
          </label>
          {hiring && (
            <label className="flex flex-col gap-1 text-[13px] font-medium">
              How many vehicles
              <input className={input} type="number" min={1} max={60} value={vehicles} onChange={(e) => setVehicles(e.target.value)} />
            </label>
          )}
          {hiring && (
            <label className="flex items-center gap-2 text-[13px]">
              <input type="checkbox" checked={refrigerated} onChange={(e) => setRefrigerated(e.target.checked)} />
              Refrigerated
            </label>
          )}
          <label className="flex flex-col gap-1 text-[13px] font-medium">
            Why
            <textarea className={input} rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
          </label>
          {error && <Notice tone="danger" live title={error} />}
          <div className="flex gap-2">
            <PrimaryButton type="submit" busy={busy} disabled={!ready}>
              Record
            </PrimaryButton>
            <SecondaryButton onClick={() => setOpen(false)}>Cancel</SecondaryButton>
          </div>
        </form>
      )}
    </Card>
  );
}
