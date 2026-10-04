"use client";

import { useState } from "react";
import type { ApiError } from "@shared/api/problem";
import type { IssueView, OrderView, OutletView } from "@shared/domain/types";
import { Notice, SkeletonRows, cx } from "@shared/ui";
import type { StoreGateway } from "../../data/gateway.ts";
import { addDays, clock, depotToday } from "../../data/format.ts";
import { REPORT_KINDS, isOpenIssue, issueCard, type ReportKind } from "../../data/issues.ts";
import type { useCommands } from "../../data/useCommands.ts";
import { Button, Card, Muted } from "../../ui.tsx";
import ReportIssue from "./ReportIssue.tsx";

// Figma "08 Issues": what was reported about this outlet's deliveries, by the
// loader at the dock or by the store while counting or after unpacking, and a
// way to report something else. The store reads and raises issues; the
// dispatcher resolves them.

const RECEIVED = new Set(["DELIVERED", "PARTIALLY_DELIVERED", "RECEIVED", "UNCONFIRMED"]);
const DOT = { danger: "bg-go-danger", ok: "bg-go-success", muted: "bg-[#a9b5b3]" } as const;
const LABEL = { danger: "text-go-danger-strong", ok: "text-go-success", muted: "text-go-muted" } as const;
const STAMP = { danger: "bg-go-canvas text-black", ok: "bg-[#e3f8ee] text-go-success", muted: "bg-go-canvas text-go-muted" } as const;

export default function Issues({
  gateway,
  issues,
  orders,
  outlet,
  loading,
  error,
  commands,
  onOpenOrder,
  onSent,
  onRetry,
}: {
  gateway: StoreGateway;
  issues: IssueView[];
  orders: OrderView[];
  outlet: OutletView | null;
  loading: boolean;
  error: ApiError | Error | null;
  commands: ReturnType<typeof useCommands>;
  onOpenOrder: (orderId: string) => void;
  /** An issue was raised: read the list again. */
  onSent: () => void;
  /** Read the issues again after a failure. */
  onRetry?: () => void;
}): React.JSX.Element {
  const [reporting, setReporting] = useState<{ preset: ReportKind | null } | null>(null);
  const [earlier, setEarlier] = useState(false);
  const since = addDays(depotToday(), -2);
  const recent = orders.filter((o) => RECEIVED.has(o.status) && o.deliveryDate >= since).sort((a, b) => b.deliveryDate.localeCompare(a.deliveryDate));
  const open = issues.filter(isOpenIssue);
  const closed = issues.filter((i) => !isOpenIssue(i));
  const orderOf = (i: IssueView) => orders.find((o) => i.subjects.some((s) => s.type === "order" && s.id === o.orderId)) ?? null;

  const card = (i: IssueView) => {
    const order = orderOf(i);
    const c = issueCard(i, order, clock);
    return (
      <li key={i.issueId}>
        <article className="flex flex-col gap-1.5 rounded-[26px] bg-white p-5">
          <div className="flex items-center gap-2">
            <span aria-hidden className={cx("size-2 rounded-full", DOT[c.tone])} />
            <span className={cx("flex-1 text-[13px] font-medium", LABEL[c.tone])}>{c.label}</span>
            <span className={cx("rounded-full px-3 py-1 text-[12px] font-medium", STAMP[c.tone])}>{c.stamp}</span>
          </div>
          <h2 className="text-[20px] font-medium text-black">{c.title}</h2>
          <p className="text-[13px] text-go-muted">{c.detail}</p>
          {i.resolutionNote && (
            <p className="rounded-[14px] bg-go-canvas px-3 py-2 text-[13px] text-black">
              <span className="text-go-muted">Dispatcher: </span>
              {i.resolutionNote}
            </p>
          )}
          {order && (
            <button type="button" onClick={() => onOpenOrder(order.orderId)} className="min-h-12 self-start text-[13px] font-medium text-go-teal">
              Open {order.orderRef}
            </button>
          )}
        </article>
      </li>
    );
  };

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-1">
        <h1 className="text-[32px] leading-tight font-medium text-black">Issues</h1>
        <Muted>{outlet ? `Deliveries to ${outlet.outletId}` : "…"}</Muted>
      </div>
      {error && <Notice tone="danger" title="Could not load your issues" onRetry={onRetry}>{error.message}</Notice>}

      <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px] lg:items-start">
        <div className="flex min-w-0 flex-col gap-3">
          {open.length === 0 && (loading ? <SkeletonRows label="Loading…" /> : <Muted>Nothing is open. Problems with a delivery show here.</Muted>)}
          <ul className="flex flex-col gap-3">{open.map(card)}</ul>
          {closed.length > 0 && (
            <>
              <button type="button" onClick={() => setEarlier((v) => !v)} className="min-h-12 self-start text-[14px] font-medium text-go-teal">
                {earlier ? "Hide" : "Show"} earlier issues ({closed.length})
              </button>
              {earlier && <ul className="flex flex-col gap-3">{closed.map(card)}</ul>}
            </>
          )}
        </div>

        <Card label="Something else wrong?">
          <h2 className="text-[18px] font-medium text-black">Something else wrong?</h2>
          <Muted>Deliveries from the last 48 hours</Muted>
          <div className="flex flex-col gap-2">
            {REPORT_KINDS.map((k) => (
              <button
                key={k.label}
                type="button"
                onClick={() => setReporting({ preset: k })}
                className="min-h-12 rounded-[14px] bg-go-canvas px-4 text-left text-[15px] text-black"
              >
                {k.label}
              </button>
            ))}
          </div>
          <Button large onClick={() => setReporting({ preset: null })}>
            Report an issue
          </Button>
        </Card>
      </div>

      {reporting && (
        <ReportIssue
          gateway={gateway}
          orders={recent}
          preset={reporting.preset}
          commands={commands}
          onClose={() => setReporting(null)}
          onSent={() => {
            setReporting(null);
            onSent();
          }}
        />
      )}
    </div>
  );
}
