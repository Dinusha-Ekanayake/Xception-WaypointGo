"use client";

import { useEffect, useMemo, useState } from "react";
import { Pill, Segmented, cx, usePersistentState } from "@shared/ui";
import { businessNow, clock, clockOn, issueText } from "@shared/wording";
import PageHeader from "../PageHeader.tsx";
import { SEVERITY, TYPE, byUrgency } from "../data/issues.ts";
import { useIssues, useSettledIssues } from "../data/useDay.ts";
import IssueDetail from "./IssueDetail.tsx";
import { Retry } from "./Orders.tsx";
import Refusal from "./Refusal.tsx";

// Figma "08 Issues": every open and in-progress issue at the depots in scope,
// most severe first, and one issue at a time with what was reported and what
// can be done about it. Issues are raised by loaders, drivers, store managers
// and the system's own consumers; the dispatcher takes, resolves and closes
// them. Open is nobody's yet, In progress has someone on it. The Issues module
// lists only active issues, so Resolved says what it waits on. Read every 30
// seconds while the tab is visible and online. Goods problems (R-ISS-16) do not
// alert or escalate; they wait in their own tab for the day's end.

type Tab = "open" | "progress" | "goods" | "resolved";

const DOT: Record<string, string> = { CRITICAL: "bg-go-danger", HIGH: "bg-go-danger", MEDIUM: "bg-go-warning", LOW: "bg-go-offline" };

export default function Issues({
  depots,
  scopeLabel,
  userId,
  online,
  focusIssueId = null,
}: {
  depots: string[];
  scopeLabel: string;
  userId: string;
  online: boolean;
  /** An issue another screen opened, such as Live's "Book make-up". */
  focusIssueId?: string | null;
}): React.JSX.Element {
  const issues = useIssues(depots);
  const [tab, setTab] = usePersistentState<Tab>("dispatcher:issues:tab", "open");
  const settled = useSettledIssues(depots, tab === "resolved");
  const [selectedId, setSelectedId] = useState<string | null>(focusIssueId);
  useEffect(() => {
    if (focusIssueId) setSelectedId(focusIssueId);
  }, [focusIssueId]);
  const [now, setNow] = useState(() => businessNow());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(businessNow()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  const all = useMemo(() => byUrgency(issues.data ?? []), [issues.data]);
  const open = all.filter((issue) => issue.status === "OPEN" && !issue.quiet);
  const progress = all.filter((issue) => issue.status === "ASSIGNED" && !issue.quiet);
  const goods = all.filter((issue) => issue.quiet);
  const shown = tab === "open" ? open : tab === "progress" ? progress : tab === "goods" ? goods : (settled.data ?? []);
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
        subtitle={`${issues.data ? `${all.length} active` : "Loading"} · stores, loaders and drivers · ${scopeLabel}`}
        online={online}
        lastSyncedAt={issues.loadedAt}
        onSync={issues.refresh}
        syncing={issues.loading}
      />
      {issues.error && <Refusal error={issues.error} what="the issues" action={<Retry onClick={issues.refresh} />} />}

      <Segmented
        size="md"
        label="Which issues"
        value={tab}
        onChange={(next) => (setTab(next), setSelectedId(null))}
        options={[
          { value: "open", label: `Open (${issues.data ? open.length : "…"})` },
          { value: "progress", label: `In progress (${issues.data ? progress.length : "…"})` },
          { value: "goods", label: `Goods problems (${issues.data ? goods.length : "…"})` },
          { value: "resolved", label: "Resolved" },
        ]}
      />

      <div className="flex min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col">
        <section aria-label="Open issues" className="flex min-w-0 flex-1 flex-col gap-1 self-start rounded-go-panel bg-go-card p-2">
          {tab === "resolved" && settled.error && (
            <Refusal error={settled.error} what="the resolved issues" action={<Retry onClick={settled.refresh} />} />
          )}
          {(tab === "resolved" ? settled.data : issues.data) && shown.length === 0 && (
            <p className="py-8 text-center text-[13px] text-go-secondary">
              {tab === "open"
                ? `No open issue at ${scopeLabel}.`
                : tab === "progress"
                  ? "Nobody is working on an issue right now."
                  : tab === "goods"
                    ? "No missing or damaged goods waiting for the day's end."
                    : "Nothing resolved in the last 7 days."}
            </p>
          )}
          <ul className="flex flex-col gap-1">
            {shown.map((issue) => {
              const active = issue.issueId === selected;
              const where = [issue.outletId].filter(Boolean).join(" · ");
              return (
                <li key={issue.issueId}>
                  <button
                    type="button"
                    aria-pressed={active}
                    onClick={() => setSelectedId(issue.issueId)}
                    className={cx("flex w-full flex-wrap items-center gap-x-4 gap-y-1 rounded-go-card px-4 py-3.5 text-left", active ? "bg-go-success-tint" : "hover:bg-go-subtle")}
                  >
                    <span aria-hidden className={cx("size-2 shrink-0 rounded-full", DOT[issue.severity])} />
                    <span className="min-w-[200px] flex-1">
                      <span className="block text-[15px] font-medium text-go-ink">
                        {TYPE[issue.type]}
                        <span className="sr-only">{`, ${SEVERITY[issue.severity].label}`}</span>
                      </span>
                      <span className="block truncate text-[13px] text-go-secondary">{`${issue.depotCode}${where ? ` · ${where}` : ""}`}</span>
                    </span>
                    <span className="min-w-[160px] text-[13px]">
                      <span className="block truncate text-go-ink">{issueText(issue.description)}</span>
                      <span className="block text-xs text-go-secondary">{`Reported ${clockOn(issue.raisedAt)}`}</span>
                    </span>
                    {issue.status === "RESOLVED" || issue.status === "CLOSED" ? (
                      <Pill tone={issue.status === "RESOLVED" ? "warning" : "neutral"}>{issue.status === "RESOLVED" ? "To close" : "Closed"}</Pill>
                    ) : (
                      <Pill tone={issue.status === "OPEN" ? "danger" : issue.assignee === userId ? "success" : "warning"}>
                        {issue.status === "OPEN" ? "New" : issue.assignee === userId ? "Yours" : "In progress"}
                      </Pill>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        </section>

        <div className="flex w-full flex-col gap-[18px] lg:w-[380px] lg:shrink-0">
          {selected ? (
            <IssueDetail key={selected} issueId={selected} userId={userId} online={online} now={now} onChanged={() => (issues.refresh(), settled.refresh())} />
          ) : shown.length === 0 ? null : (
            <section aria-label="Selected issue" className="rounded-go-panel bg-go-card px-6 py-8 text-center text-[13px] text-go-secondary">
              Choose an issue to see its history and act on it.
            </section>
          )}
        </div>
      </div>
    </>
  );
}
