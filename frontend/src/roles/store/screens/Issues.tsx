"use client";

import { useState } from "react";
import type { ApiError } from "@shared/api/problem";
import type { IssueView, OrderView, OutletView } from "@shared/domain/types";
import { Notice, cx } from "@shared/ui";
import { clock, dayLabel, depotToday } from "../data/format.ts";
import { ISSUE_STATUS, ISSUE_TYPE, isOpenIssue } from "../data/issues.ts";
import { Card, Chip, Muted } from "../ui.tsx";

// Figma sidebar "Issues": what was reported about this outlet's deliveries, by
// the loader at the dock, the driver on the road, or the store at receipt. The
// store reads and raises issues; the dispatcher resolves them, so a closed one
// carries the dispatcher's note.

type Range = "open" | "closed";

export default function Issues({
  issues,
  orders,
  outlet,
  loading,
  error,
  focus,
  onOpenOrder,
}: {
  issues: IssueView[];
  orders: OrderView[];
  outlet: OutletView | null;
  loading: boolean;
  error: ApiError | Error | null;
  /** An issue to show first, such as the one just raised at receipt. */
  focus: string | null;
  onOpenOrder: (orderId: string) => void;
}): React.JSX.Element {
  const [range, setRange] = useState<Range>("open");
  const lists: Record<Range, IssueView[]> = { open: issues.filter(isOpenIssue), closed: issues.filter((i) => !isOpenIssue(i)) };
  const shown = [...lists[range]].sort((a, b) => Number(b.issueId === focus) - Number(a.issueId === focus));
  const orderRef = (i: IssueView) => {
    const id = i.subjects.find((s) => s.type === "order")?.id;
    return orders.find((o) => o.orderId === id) ?? null;
  };

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">Issues</h1>
        <Muted>{outlet ? `${outlet.districtName} · ${outlet.outletId}` : "…"}</Muted>
      </div>
      <div role="tablist" aria-label="Status" className="flex gap-1 rounded-full bg-white p-1 lg:w-fit">
        {(["open", "closed"] as const).map((r) => (
          <button
            key={r}
            type="button"
            role="tab"
            aria-selected={r === range}
            onClick={() => setRange(r)}
            className={cx("min-h-12 flex-1 rounded-full px-2 text-[15px] font-medium lg:flex-none lg:px-5", r === range ? "bg-[#031a0c] text-white" : "text-black")}
          >
            {r === "open" ? "Open" : "Closed"} <span className="opacity-60">{lists[r].length}</span>
          </button>
        ))}
      </div>

      {error && <Notice tone="danger" title="Could not load your issues">{error.message}</Notice>}
      {shown.length === 0 && <Muted>{loading ? "Loading…" : range === "open" ? "Nothing is open. Problems with a delivery show here." : "No closed issues in the last two weeks."}</Muted>}

      <ul className="flex flex-col gap-3">
        {shown.map((i) => {
          const order = orderRef(i);
          const s = ISSUE_STATUS[i.status];
          return (
            <li key={i.issueId}>
              <Card className={i.issueId === focus ? "outline-2 outline-[#0f766e]" : undefined}>
                <div className="flex items-center gap-2">
                  <h2 className="flex-1 text-[18px] font-medium text-black">{ISSUE_TYPE[i.type]}</h2>
                  <Chip tone={s.tone}>{s.label}</Chip>
                </div>
                <p className="text-[15px] text-black">{i.description}</p>
                {i.resolutionNote && (
                  <p className="rounded-[16px] bg-go-canvas px-3.5 py-2.5 text-[14px] text-black">
                    <span className="text-go-muted">{i.resolutionAction ?? "Resolved"}: </span>
                    {i.resolutionNote}
                  </p>
                )}
                <div className="flex items-center gap-2 text-[13px] text-go-muted">
                  <span className="flex-1">
                    Raised {dayLabel(depotToday(new Date(i.raisedAt)))} {clock(i.raisedAt)}
                  </span>
                  {order && (
                    <button type="button" onClick={() => onOpenOrder(order.orderId)} className="min-h-12 px-2 font-medium text-go-teal">
                      {order.orderRef}
                    </button>
                  )}
                </div>
              </Card>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
