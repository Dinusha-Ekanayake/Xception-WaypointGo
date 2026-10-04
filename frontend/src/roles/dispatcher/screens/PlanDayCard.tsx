"use client";

import { useMemo } from "react";
import type { OrderView } from "@shared/domain/types";
import { Card, CardHead, LinkAction, Pill } from "@shared/ui";
import { addDays, clock, dayLabel } from "@shared/wording";
import { working } from "../data/plan.ts";
import { decidedCount, decisionRows } from "../data/planViews.ts";
import { depotToday } from "../data/scope.ts";
import { useOrders, usePlans } from "../data/useDay.ts";
import Refusal from "./Refusal.tsx";

// The Overview's way into the day's main task: where tomorrow's plan stands for
// each depot in view (none yet, a draft with orders still to decide, or
// published) and a link straight into it.

export default function PlanDayCard({ depots, onOpenPlan }: { depots: string[]; onOpenPlan: (date: string) => void }): React.JSX.Element {
  const day = addDays(depotToday(), 1);
  const plans = usePlans(depots, day);
  const orders = useOrders(depots, day);
  const byId = useMemo(() => new Map<string, OrderView>((orders.data ?? []).map((order) => [order.orderId, order])), [orders.data]);

  return (
    <Card label="Tomorrow's plan">
      <CardHead title="Tomorrow's plan" meta={dayLabel(day)} action={<LinkAction onClick={() => onOpenPlan(day)}>Open plan</LinkAction>} />
      {plans.error && !plans.data && <Refusal error={plans.error} what="tomorrow's plan" />}
      {!plans.data && !plans.error && <p className="text-[13px] text-go-secondary">Reading the plan…</p>}
      <ul className="flex flex-col gap-2">
        {(plans.data ?? []).map((depot) => {
          const state = working(depot.published, depot.draft);
          const open = state.stage === "none" ? 0 : decidedCount(decisionRows(state.plan, byId)).open;
          const waiting = (orders.data ?? []).filter((order) => order.depotCode === depot.depot && (order.status === "CONFIRMED" || order.status === "DEFERRED")).length;
          return (
            <li key={depot.depot}>
              <button
                type="button"
                onClick={() => onOpenPlan(day)}
                className="flex w-full flex-wrap items-center gap-2 rounded-go-card bg-go-surface px-4 py-3 text-left hover:bg-go-subtle"
              >
                <span className="min-w-[110px] flex-1 text-[15px] font-medium text-go-ink">{depot.depot}</span>
                {state.stage === "none" ? (
                  <Pill tone="muted">{waiting ? `No plan yet · ${waiting} ${waiting === 1 ? "order waits" : "orders wait"}` : "No plan yet"}</Pill>
                ) : state.stage === "draft" ? (
                  <>
                    <Pill tone="warning">{state.revises ? "Update not sent" : "Draft"}</Pill>
                    {open > 0 ? <Pill tone="danger">{`${open} ${open === 1 ? "needs" : "need"} a decision`}</Pill> : <Pill tone="success">Ready to publish</Pill>}
                  </>
                ) : (
                  <Pill tone="success">{`Published${state.plan.publishedAt ? ` ${clock(new Date(state.plan.publishedAt))}` : ""}`}</Pill>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
