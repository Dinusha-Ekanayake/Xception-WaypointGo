"use client";

import { Card, CardHead, LinkAction } from "@shared/ui";
import { dayLabel } from "@shared/wording";
import type { KpiKey, KpiWeekView } from "@shared/domain/types";
import { useKpis } from "../data/kpis.ts";
import { KPI_LABEL, kpiValue } from "../data/kpiWords.ts";
import type { ViewId } from "../navigation.ts";

// Roadmap B-05 on Overview: the pilot's latest week at a glance, the depot's
// figure beside what the app counted. Recording a week and the weeks before it
// are on the full page, a click away; it is a weekly task, not daily work.

const HEADLINE: KpiKey[] = ["fresh_on_time_pct", "adoption_pct", "calls_per_trip", "write_off_lkr"];

export default function PilotKpisCard({ depots, onNavigate }: { depots: string[]; onNavigate: (view: ViewId) => void }): React.JSX.Element | null {
  const depot = depots[0] ?? null;
  const report = useKpis(depot);
  if (!depot) return null;
  const weeks = report.data?.weeks ?? [];
  const latest: KpiWeekView | null = weeks.reduce<KpiWeekView | null>((a, w) => (a === null || w.weekStart > a.weekStart ? w : a), null);
  return (
    <Card label="Pilot KPIs">
      <CardHead
        title="Pilot KPIs"
        meta={latest ? `Week of ${dayLabel(latest.weekStart)} · ${depot}` : depot}
        action={<LinkAction onClick={() => onNavigate("kpis")}>Record or view all weeks</LinkAction>}
      />
      {report.error && !report.data ? (
        <p className="text-xs text-go-secondary">The pilot KPIs could not be read just now.</p>
      ) : !report.data ? (
        <p className="text-xs text-go-secondary">Loading…</p>
      ) : (
        <dl className="grid grid-cols-2 gap-2">
          {HEADLINE.map((k) => {
            const recorded = latest?.recorded[k];
            const counted = report.data!.countedByApp.includes(k) ? latest?.counted[k] : undefined;
            return (
              <div key={k} className="flex min-h-[74px] flex-col gap-1 rounded-go-card-s bg-go-surface px-3 py-2">
                <dt className="text-[11px] text-go-secondary">{KPI_LABEL[k]}</dt>
                <dd className={recorded ? "text-lg font-medium text-go-teal" : "text-[13px] text-go-secondary"}>
                  {recorded ? kpiValue(k, recorded.value) : "Not recorded"}
                </dd>
                {counted && <dd className="text-[11px] text-go-secondary">{`App ${kpiValue(k, counted.value)}`}</dd>}
              </div>
            );
          })}
        </dl>
      )}
    </Card>
  );
}
