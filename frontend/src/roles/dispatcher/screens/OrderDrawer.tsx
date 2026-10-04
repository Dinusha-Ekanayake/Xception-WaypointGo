"use client";

import { useRef } from "react";
import type { IssueView, OrderStatus } from "@shared/domain/types";
import { Icon, Pill, SecondaryButton, cx, useDialogFocus } from "@shared/ui";
import { clock, dayLabel, hhmm, units } from "@shared/wording";
import { TYPE } from "../data/issues.ts";
import { STATUS, size } from "../data/orders.ts";
import type { OrderLine } from "./OrdersTable.tsx";

// Figma "03d Orders: order details": the order opened beside the table, with
// its facts and its day as a timeline: placed, planned, loaded, delivered and
// confirmed by the store, each with the time a module recorded. A step with no
// time yet is shown as still to come, never as done. Open issues on the order
// close the panel in red.

const PLANNED: OrderStatus[] = ["ALLOCATED", "LOADING", "IN_TRANSIT", "DELIVERED", "PARTIALLY_DELIVERED", "FAILED", "RECEIVED", "UNCONFIRMED"];
const LOADED: OrderStatus[] = ["IN_TRANSIT", "DELIVERED", "PARTIALLY_DELIVERED", "FAILED", "RECEIVED", "UNCONFIRMED"];
const DELIVERED: OrderStatus[] = ["DELIVERED", "PARTIALLY_DELIVERED", "RECEIVED", "UNCONFIRMED"];

type Step = { title: string; note: string; state: "done" | "warn" | "fail" | "todo" };

export default function OrderDrawer({
  line,
  issues,
  onClose,
  onOpenPlan,
}: {
  line: OrderLine;
  issues: IssueView[];
  onClose: () => void;
  /** Opens the plan of the order's day, where an order that waits for a decision is decided. */
  onOpenPlan?: (date: string) => void;
}): React.JSX.Element {
  const { order, ride, stop } = line;
  const state = STATUS[order.status];
  const panel = useRef<HTMLElement>(null);
  useDialogFocus(panel, onClose);
  const decidable = order.status === "CONFIRMED" || order.status === "DEFERRED" || order.status === "ALLOCATED" || order.status === "UNSERVABLE";

  const steps: Step[] = [
    { title: "Placed", note: `${dayLabel(order.placedAt.slice(0, 10))} ${clock(order.placedAt)}`, state: "done" },
    {
      title: "Planned",
      note: ride ? `${ride}${stop ? ` · stop ${stop.sequence}` : ""}` : order.status === "DEFERRED" ? `Deferred ${order.deferralCount}×` : "Not on a published plan yet",
      state: PLANNED.includes(order.status) ? "done" : order.status === "DEFERRED" || order.status === "UNSERVABLE" ? "warn" : "todo",
    },
    { title: "Loaded", note: stop?.startedAt ? `Left the depot ${clock(stop.startedAt)}` : "At the dock", state: LOADED.includes(order.status) ? "done" : "todo" },
    {
      title: order.status === "FAILED" ? "Not delivered" : "Delivered",
      note: stop?.completedAt
        ? `${clock(stop.completedAt)}${stop.lateMinutes ? ` · ${stop.lateMinutes} min late` : " · on time"}`
        : stop?.expectedArrival
          ? `Expected ${clock(stop.expectedArrival)}`
          : stop
            ? `Planned ${hhmm(stop.plannedArrival)}`
            : "Not yet",
      state: order.status === "FAILED" ? "fail" : DELIVERED.includes(order.status) ? (order.status === "PARTIALLY_DELIVERED" ? "warn" : "done") : "todo",
    },
    {
      title: "Store confirmed",
      note: order.status === "RECEIVED" ? "Signed by the store manager" : order.status === "UNCONFIRMED" ? "The store manager did not confirm" : "Waiting for the store manager",
      state: order.status === "RECEIVED" ? "done" : order.status === "UNCONFIRMED" ? "fail" : "todo",
    },
  ];

  return (
    <aside
      ref={panel}
      tabIndex={-1}
      aria-modal="true"
      role="dialog"
      aria-label={`Order ${order.orderRef}`}
      className="fixed inset-y-0 right-0 z-40 flex w-full max-w-[420px] animate-slide-in-end flex-col gap-4 overflow-y-auto overscroll-contain rounded-l-go-panel bg-go-card p-6 shadow-go-float"
    >
      <header className="flex items-start gap-3">
        <div className="min-w-0 flex-1">
          <h2 className="text-[24px] font-medium text-go-ink">{order.orderRef}</h2>
          <p className="text-[13px] text-go-secondary">{`${order.brandCode} · ${order.outletId} ${order.districtName}${stop ? ` · ${hhmm(stop.windowOpen)}-${hhmm(stop.windowClose)}` : ""}`}</p>
        </div>
        <button type="button" onClick={onClose} aria-label="Close" className="rounded-full bg-go-surface p-2.5">
          <Icon name="close" />
        </button>
      </header>
      <div className="flex flex-wrap gap-1.5">
        <Pill tone={order.temperature === "chilled" ? "info" : "muted"}>{order.temperature === "chilled" ? "Chilled" : "Ambient"}</Pill>
        <Pill tone={state.tone}>{state.label}</Pill>
      </div>
      <dl className="grid grid-cols-4 gap-2">
        <Fact label="Units" value={units(order.itemCount)} />
        <Fact label="Size" value={size(order).split(" · ")[0]!} />
        <Fact label="Weight" value={size(order).split(" · ")[1]!} />
        <Fact label="Vehicle" value={ride?.split(" · ")[0] ?? "-"} />
      </dl>

      <div>
        <h3 className="pb-2 text-[15px] font-medium text-go-ink">Timeline</h3>
        <ol className="relative flex flex-col gap-3 before:absolute before:top-2 before:bottom-2 before:left-[5px] before:w-0.5 before:bg-go-rule">
          {steps.map((step) => (
            <li key={step.title} className="relative flex gap-3">
              <span
                aria-hidden
                className={cx(
                  "relative z-10 mt-1 size-3 shrink-0 rounded-full",
                  step.state === "done" ? "bg-go-teal" : step.state === "warn" ? "bg-go-warning" : step.state === "fail" ? "bg-go-danger" : "border-2 border-go-rule bg-go-card",
                )}
              />
              <span>
                <span className={cx("block text-[14px] font-medium", step.state === "todo" ? "text-go-secondary" : "text-go-ink")}>{step.title}</span>
                <span className="block text-xs text-go-secondary">{step.note}</span>
              </span>
            </li>
          ))}
        </ol>
      </div>

      {onOpenPlan && decidable && (
        <SecondaryButton onClick={() => onOpenPlan(order.deliveryDate)}>
          {order.status === "ALLOCATED" ? "See it on the plan" : "Decide it in Plan"}
        </SecondaryButton>
      )}

      {issues.map((issue) => (
        <div key={issue.issueId} className="rounded-go-card bg-go-danger-tint px-4 py-3">
          <p className="text-[14px] font-medium text-go-danger-strong">{`${TYPE[issue.type]} · ${issue.status === "OPEN" ? "open" : "being handled"}`}</p>
          <p className="text-xs text-go-ink">{issue.description}</p>
        </div>
      ))}
    </aside>
  );
}

function Fact({ label, value }: { label: string; value: string }): React.JSX.Element {
  return (
    <div className="rounded-go-input bg-go-surface px-2.5 py-2">
      <dt className="text-[11px] text-go-secondary">{label}</dt>
      <dd className="truncate text-[14px] font-medium text-go-ink">{value}</dd>
    </div>
  );
}
