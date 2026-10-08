"use client";

import { useState } from "react";
import { Card, CardHead, Notice, PrimaryButton, SecondaryButton, SkeletonRows } from "@shared/ui";
import { friendlyError } from "@shared/api/problem";
import { dayLabel, depotToday } from "@shared/wording";
import type { KpiKey, KpiWeekView, RecordKpiWeek } from "@shared/domain/types";
import PageHeader from "../PageHeader.tsx";
import Refusal from "./Refusal.tsx";
import { useCommand } from "../data/useCommand.ts";
import { KPI_WEEKS, RECORD_KPI_WEEK, useKpis } from "../data/kpis.ts";
import { KPI_LABEL, KPI_ORDER, KPI_OWNER, kpiInputError, kpiValue, lastMondays } from "../data/kpiWords.ts";

// Roadmap B-05: the pilot's business KPIs, week by week for one depot. The depot
// records what it measured on paper and in spreadsheets; beside it, the figures
// the app counts from its own finished stops. The pilot is judged on the change.

const input = "w-full rounded-go-input border border-go-rule bg-white px-3 py-2 text-[14px] font-normal outline-none focus:border-go-teal";

export default function PilotKpis({ depots, online }: { depots: string[]; online: boolean }): React.JSX.Element {
  const [depot, setDepot] = useState(depots[0] ?? "");
  const report = useKpis(depot || null);
  const weeks = report.data?.weeks ?? [];
  return (
    <>
      <PageHeader
        title="Pilot KPIs"
        subtitle={`Last ${KPI_WEEKS} weeks · ${depot || "no depot"}`}
        online={online}
        lastSyncedAt={report.loadedAt}
        tools={
          depots.length > 1 ? (
            <select aria-label="Depot" className={input} value={depot} onChange={(e) => setDepot(e.target.value)}>
              {depots.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          ) : undefined
        }
      />
      {report.error && !report.data && <Refusal error={report.error} what="the pilot KPIs" />}
      {!report.data && !report.error && <SkeletonRows rows={4} label="Loading the pilot KPIs…" />}
      {report.data && <WeeksTable weeks={weeks} countedByApp={report.data.countedByApp} />}
      {depot && <RecordWeek depot={depot} onRecorded={report.refresh} />}
    </>
  );
}

function WeeksTable({ weeks, countedByApp }: { weeks: KpiWeekView[]; countedByApp: KpiKey[] }): React.JSX.Element {
  return (
    <Card label="Weeks">
      <CardHead title="Week by week" meta="Recorded by the depot · counted by the app below it" />
      <div className="overflow-x-auto">
        <table className="w-full min-w-[880px] border-collapse text-[13px]">
          <thead>
            <tr className="text-left text-go-secondary">
              <th scope="col" className="py-2 pr-3 font-medium">
                Week of
              </th>
              {KPI_ORDER.map((k) => (
                <th key={k} scope="col" className="py-2 pr-3 font-medium" title={`Measured by: ${KPI_OWNER[k]}`}>
                  {KPI_LABEL[k]}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {weeks.map((w) => (
              <tr key={w.weekStart} className="border-t border-go-rule align-top">
                <th scope="row" className="py-2 pr-3 font-medium whitespace-nowrap">
                  {dayLabel(w.weekStart)}
                </th>
                {KPI_ORDER.map((k) => {
                  const recorded = w.recorded[k];
                  const counted = countedByApp.includes(k) ? w.counted[k] : undefined;
                  return (
                    <td key={k} className="py-2 pr-3">
                      {recorded ? (
                        <span className="block font-semibold text-go-ink">{kpiValue(k, recorded.value)}</span>
                      ) : (
                        <span className="block text-go-secondary">Not recorded</span>
                      )}
                      {counted && (
                        <span className="block text-[11px] text-go-secondary">
                          App {kpiValue(k, counted.value)} · {counted.stops} {counted.stops === 1 ? "stop" : "stops"}
                        </span>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function RecordWeek({ depot, onRecorded }: { depot: string; onRecorded: () => void }): React.JSX.Element {
  const mondays = lastMondays(depotToday(), KPI_WEEKS);
  const { busy, run } = useCommand();
  const [open, setOpen] = useState(false);
  const [week, setWeek] = useState(mondays[0]!);
  const [values, setValues] = useState<Partial<Record<KpiKey, string>>>({});
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);

  const problems = KPI_ORDER.map((k) => kpiInputError(k, values[k] ?? "")).filter((p): p is string => p !== null);
  const filled = KPI_ORDER.filter((k) => (values[k] ?? "").trim() !== "");
  const ready = filled.length > 0 && problems.length === 0;

  const save = async () => {
    if (!ready) return;
    const payload: RecordKpiWeek = {
      depotCode: depot,
      weekStart: week,
      values: Object.fromEntries(filled.map((k) => [k, Number(values[k])])),
      ...(note.trim() ? { note: note.trim() } : {}),
    };
    const sent = await run(RECORD_KPI_WEEK, payload, null);
    if (!sent.ok) {
      setError(friendlyError(sent.error));
      return;
    }
    setError(null);
    setValues({});
    setNote("");
    setOpen(false);
    onRecorded();
  };

  return (
    <Card label="Record a week">
      <CardHead title="Record a week" meta="From the run sheets, phone logs and Finance's write-off sheet. A correction is kept beside the first figure." />
      {!open ? (
        <SecondaryButton onClick={() => setOpen(true)}>Record a week</SecondaryButton>
      ) : (
        <form
          className="flex flex-col gap-3"
          aria-label="Record a week"
          onSubmit={(e) => {
            e.preventDefault();
            void save();
          }}
        >
          <label className="flex max-w-xs flex-col gap-1 text-[13px] font-medium">
            Week of
            <select className={input} value={week} onChange={(e) => setWeek(e.target.value)}>
              {mondays.map((m) => (
                <option key={m} value={m}>
                  {dayLabel(m)}
                </option>
              ))}
            </select>
          </label>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {KPI_ORDER.map((k) => (
              <label key={k} className="flex flex-col gap-1 text-[13px] font-medium">
                {KPI_LABEL[k]}
                <input
                  className={input}
                  inputMode="decimal"
                  value={values[k] ?? ""}
                  onChange={(e) => setValues((v) => ({ ...v, [k]: e.target.value }))}
                  aria-invalid={kpiInputError(k, values[k] ?? "") ? true : undefined}
                />
                <span className="text-[11px] font-normal text-go-secondary">{KPI_OWNER[k]}</span>
              </label>
            ))}
          </div>
          <label className="flex flex-col gap-1 text-[13px] font-medium">
            Where the figures come from (optional)
            <textarea className={input} rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
          </label>
          {problems.length > 0 && <Notice tone="warning" live title={problems[0]!} />}
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
