"use client";

import { useEffect, useMemo, useState } from "react";
import { FilterTabs, KpiCard, Pill } from "@shared/ui";
import PageHeader from "../PageHeader.tsx";
import { SEVERITY, STATUS, TYPE, age, byUrgency, issueCounts, matchesIssue, shortId, type IssueFilter } from "../data/issues.ts";
import { useIssues } from "../data/useDay.ts";
import IssueDetail from "./IssueDetail.tsx";
import { Retry } from "./Orders.tsx";
import Refusal from "./Refusal.tsx";

// The issue inbox: every open and assigned issue at the depots in scope, most
// severe first, and one issue at a time with its history and what can be done
// about it. Issues are raised by loaders, drivers, store managers and the
// system's own consumers; the dispatcher assigns, resolves and closes them.
// Read every 30 seconds while the tab is visible and online.

const FILTERS: Array<{ value: IssueFilter; label: string }> = [
  { value: "all", label: "All open" },
  { value: "urgent", label: "High and critical" },
  { value: "unassigned", label: "Unassigned" },
  { value: "mine", label: "Mine" },
];

export default function Issues({
  depots,
  scopeLabel,
  userId,
  online,
}: {
  depots: string[];
  scopeLabel: string;
  userId: string;
  online: boolean;
}): React.JSX.Element {
  const issues = useIssues(depots);
  const [filter, setFilter] = useState<IssueFilter>("all");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  const all = useMemo(() => byUrgency(issues.data ?? []), [issues.data]);
  const shown = all.filter((issue) => matchesIssue(issue, filter, userId));
  const counts = issueCounts(all, userId);
  const selected = selectedId ?? shown[0]?.issueId ?? null;

  // Hold the first issue once shown, so resolving it, which takes it off the
  // open list, keeps it here to be closed rather than jumping to the next one.
  useEffect(() => {
    if (selectedId === null && shown[0]) setSelectedId(shown[0].issueId);
  }, [selectedId, shown]);

  return (
    <>
      <PageHeader
        title="Issues"
        subtitle={`${issues.data ? `${counts.open} open` : "Loading"} · ${scopeLabel}`}
        online={online}
        lastSyncedAt={issues.loadedAt}
        onSync={issues.refresh}
        syncing={issues.loading}
      />
      {issues.error && <Refusal error={issues.error} what="the issues" action={<Retry onClick={issues.refresh} />} />}

      <div className="flex w-full gap-3.5 max-md:flex-col">
        <KpiCard label="Open" value={issues.data ? counts.open : "…"} note="open or assigned" />
        <KpiCard
          label="High and critical"
          value={issues.data ? counts.urgent : "…"}
          note="escalate when left alone"
          valueClassName={counts.urgent ? "text-go-danger-strong" : "text-go-ink"}
        />
        <KpiCard label="Unassigned" value={issues.data ? counts.unassigned : "…"} note="nobody has taken these" />
        <KpiCard label="Mine" value={issues.data ? counts.mine : "…"} note="assigned to you" />
      </div>

      <div className="flex min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col">
        <section aria-label="Open issues" className="flex min-w-0 flex-1 flex-col gap-2.5 rounded-[24px] bg-white p-4 shadow-go-card">
          <div className="flex flex-wrap items-center justify-between gap-2 px-1">
            <h2 className="text-[17px] font-medium text-go-ink">Open issues</h2>
            <FilterTabs label="Show issues" options={FILTERS} value={filter} onChange={setFilter} />
          </div>
          {issues.data && shown.length === 0 && (
            <p className="py-8 text-center text-[13px] text-go-secondary">
              {all.length === 0 ? "No open issue at " + scopeLabel + "." : "No open issue matches this filter."}
            </p>
          )}
          <ul className="flex flex-col gap-1.5">
            {shown.map((issue) => {
              const active = issue.issueId === selected;
              return (
                <li key={issue.issueId}>
                  <button
                    type="button"
                    aria-pressed={active}
                    onClick={() => setSelectedId(issue.issueId)}
                    className={`flex w-full flex-wrap items-center gap-x-3 gap-y-1 rounded-go-card px-4 py-3 text-left ${active ? "bg-go-success-tint" : "bg-go-subtle"}`}
                  >
                    <span className="min-w-[200px] flex-1">
                      <span className="block text-[15px] font-medium text-go-ink">{TYPE[issue.type]}</span>
                      <span className="block truncate text-xs text-go-secondary">
                        {issue.depotCode}
                        {issue.outletId ? ` · ${issue.outletId}` : ""}
                        {issue.subjects.map((ref) => ` · ${ref.type} ${shortId(ref.id)}`).join("")} · {age(issue.raisedAt, now)} ago
                      </span>
                    </span>
                    <Pill tone={STATUS[issue.status].tone}>{issue.assignee === userId ? "Yours" : STATUS[issue.status].label}</Pill>
                    <Pill tone={SEVERITY[issue.severity].tone}>{SEVERITY[issue.severity].label}</Pill>
                  </button>
                </li>
              );
            })}
          </ul>
        </section>

        <div className="flex w-full flex-col gap-[18px] lg:max-w-[440px]">
          {selected ? (
            <IssueDetail key={selected} issueId={selected} userId={userId} online={online} now={now} onChanged={issues.refresh} />
          ) : (
            <section aria-label="Selected issue" className="rounded-[24px] bg-white px-6 py-8 text-center text-[13px] text-go-secondary shadow-go-card">
              Choose an issue to see its history and act on it.
            </section>
          )}
        </div>
      </div>
    </>
  );
}
