"use client";

import type { OrderView, PlanView } from "@shared/domain/types";
import { Pill } from "@shared/ui";
import { dayLabel, ruleLabel } from "@shared/wording";
import { useNextDelivery } from "../data/usePlanReads.ts";

// Figma "Plan · 2 View plan", left column: every order the plan did not carry,
// with why and when it is next offered a vehicle, so the day is judged with the
// orders it leaves out in sight. A card opens that order in the Decide step.

export default function DeferredColumn({
  plan,
  orders,
  onOpen,
}: {
  plan: PlanView;
  orders: Map<string, OrderView>;
  onOpen: (orderId: string) => void;
}): React.JSX.Element {
  const next = useNextDelivery(plan.serviceDate);
  const left = plan.allocations.filter((allocation) => allocation.decision !== "SERVED");

  return (
    <section aria-label="Deferred orders" className="flex w-full flex-col gap-2 rounded-[24px] bg-go-card p-4 shadow-go-card lg:w-[250px] lg:shrink-0">
      <header className="flex items-center justify-between gap-2">
        <h2 className="text-[17px] font-medium text-go-ink">Deferred</h2>
        <Pill tone={left.length > 0 ? "danger" : "muted"}>{`${left.length} ${left.length === 1 ? "order" : "orders"}`}</Pill>
      </header>
      {left.length === 0 ? (
        <p className="text-[13px] text-go-secondary">Every order is on a trip.</p>
      ) : (
        <ul className="flex max-h-[560px] flex-col gap-2 overflow-y-auto">
          {left.map((allocation) => {
            const order = orders.get(allocation.orderId);
            const tooBig = allocation.decision === "UNSERVABLE";
            return (
              <li key={allocation.orderId}>
                <button
                  type="button"
                  onClick={() => onOpen(allocation.orderId)}
                  className="flex w-full flex-col gap-1 rounded-go-card border border-go-rule px-3 py-2 text-left"
                >
                  <span className="flex items-center justify-between gap-2 text-[13px] font-medium text-go-ink">
                    <span className="truncate">{order ? `${order.brandCode} ${order.districtName}` : allocation.orderId}</span>
                    <span className="shrink-0 text-xs font-normal text-go-secondary">{order?.outletId}</span>
                  </span>
                  <span className="text-xs text-go-secondary">
                    {tooBig ? "Cannot be served" : allocation.source === "KEPT" || allocation.source === "MANUAL_DEFER" ? "Kept deferred" : "Deferred"}
                    {!tooBig && next.data ? ` · first on ${dayLabel(next.data).split(" ")[0]}` : ""}
                  </span>
                  <span className="text-[11px] font-medium text-go-secondary">
                    <span className="rounded-full bg-go-surface px-2 py-0.5">{tooBig ? "Too big" : ruleLabel(allocation.bindingRule)}</span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
