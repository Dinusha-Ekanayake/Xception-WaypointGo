"use client";

import { useMemo } from "react";
import { dayLabel } from "@shared/wording";
import PageHeader from "../PageHeader.tsx";
import { working } from "../data/plan.ts";
import { useOrders, usePlans } from "../data/useDay.ts";
import DepotPlan from "./DepotPlan.tsx";
import { DayField } from "./PlanTools.tsx";

// Figma "Plan": decide, view plan, publish, and compare, as one plan view for
// the depots in scope: "Both" shows every depot's trips and orders together,
// and the depot switch in the header narrows it (the shell's one depot scope).
// The header is otherwise the one every dispatcher screen has (title, day,
// sync, bell); the plan view (DepotPlan) carries its tools, steps and body.

export default function Plan({
  depots,
  scope,
  depotFilter,
  onDepotFilter,
  date,
  onDate,
  online,
}: {
  /** The depots in view, one section each. */
  depots: string[];
  /** Every depot the session can see, for the switch. */
  scope: string[];
  /** The shared depot scope, the sidebar's: the header switch reads and sets the same one. */
  depotFilter: string;
  onDepotFilter: (filter: string) => void;
  date: string;
  onDate: (date: string) => void;
  online: boolean;
}): React.JSX.Element {
  const plans = usePlans(depots, date);
  const orders = useOrders(depots, date);
  const totals = useMemo(() => {
    const all = (plans.data ?? []).flatMap((p) => {
      const state = working(p.published, p.draft);
      return state.stage === "none" ? [] : [state.plan];
    });
    const served = all.reduce((n, p) => n + p.allocations.filter((a) => a.decision === "SERVED").length, 0);
    const placed = all.reduce((n, p) => n + p.allocations.length, 0);
    return { plans: all.length, served, placed };
  }, [plans.data]);
  const subtitle = !plans.data
    ? `${depots.join(" + ")} · Loading`
    : totals.plans === 0
      ? `${depots.join(" + ")} · No plan yet`
      : `${depots.join(" + ")} · ${totals.served} of ${totals.placed} fit`;

  return (
    <>
      <PageHeader
        title={`Plan ${dayLabel(date)}`}
        subtitle={subtitle}
        online={online}
        lastSyncedAt={plans.loadedAt}
        onSync={() => (plans.refresh(), orders.refresh())}
        syncing={plans.loading}
        tools={
          <span className="flex flex-wrap items-center gap-2.5">
            <DayField date={date} onDate={onDate} />
          </span>
        }
      />
      <DepotPlan key={`${depots.join(",")}|${date}`} depots={depots} date={date} onDate={onDate} online={online} />
    </>
  );
}
