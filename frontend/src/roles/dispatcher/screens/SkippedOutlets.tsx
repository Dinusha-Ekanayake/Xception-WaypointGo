"use client";

import { Card, CardHead, LinkAction, Pill } from "@shared/ui";
import { useDeferrals } from "../data/useDay.ts";
import type { ViewId } from "../navigation.ts";
import Refusal from "./Refusal.tsx";

// Outlets the day's published plans left out, most skipped first. An outlet
// skipped before is served first by the next plan (R-PLN-20), so a count of
// two or more is the one to watch: the priority is not getting it delivered.

export default function SkippedOutlets({
  depots,
  date,
  onNavigate,
}: {
  depots: string[];
  date: string;
  onNavigate: (view: ViewId) => void;
}): React.JSX.Element {
  const deferrals = useDeferrals(depots, date);
  const rows = deferrals.data ?? [];
  return (
    <Card label="Skipped outlets" className="flex-1">
      <CardHead
        title="Skipped outlets"
        meta="Left out of today's published plans"
        action={<LinkAction onClick={() => onNavigate("plan")}>Open plan</LinkAction>}
      />
      {deferrals.error && !deferrals.data ? (
        <Refusal error={deferrals.error} what="the skipped outlets" />
      ) : !deferrals.data ? (
        <p className="text-[13px] text-go-secondary">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="text-[13px] text-go-secondary">No outlet was skipped today, or no plan is published yet.</p>
      ) : (
        <ul aria-label="Skipped outlets" className="flex flex-col">
          {rows.map((row) => (
            <li key={row.orderId} className="flex flex-wrap items-center gap-2 border-t border-go-rule py-2 text-[13px] text-go-ink first:border-t-0">
              <span className="min-w-0 flex-1">
                <span className="font-medium">{row.outletId}</span>
                <span className="text-go-secondary">
                  {" "}
                  · {row.ruleId} · {row.reason}
                </span>
              </span>
              <Pill tone={row.skipCount >= 2 ? "danger" : "warning"}>Skipped {row.skipCount}×</Pill>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
