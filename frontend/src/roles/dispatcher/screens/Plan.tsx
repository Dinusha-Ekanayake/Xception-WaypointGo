"use client";

import { useMemo } from "react";
import { dayLabel } from "@shared/wording";
import PageHeader from "../PageHeader.tsx";
import { working } from "../data/plan.ts";
import { useOrders, usePlans } from "../data/useDay.ts";
import DepotPlan from "./DepotPlan.tsx";
import { DayField } from "./PlanTools.tsx";

// Figma "Plan": decide, view plan, publish, and compare. A plan is one depot's
// day, so the screen holds one section per depot in the sidebar's scope: both
// when the sidebar shows both, one when it shows one; nothing asks. The header
// is the one every dispatcher screen has (title, day, sync, bell); each
// section carries its own plan tools, steps and body. No draft id is held:
// every edit replaces the draft with its next version, so the draft is read by
// depot and day and each command names the version it saw (PLAN.md decision
// 4). A saved plan can be looked at, read only, beside the working one.

export default function Plan({
  depots,
  date,
  onDate,
  online,
}: {
  /** The depots in the sidebar's scope, one section each. */
  depots: string[];
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
        tools={<DayField date={date} onDate={onDate} />}
      />
      {depots.map((depot) => (
        <DepotPlan key={`${depot}|${date}`} depot={depot} date={date} onDate={onDate} online={online} titled={depots.length > 1} />
      ))}
    </>
  );
}
