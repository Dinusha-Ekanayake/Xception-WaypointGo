"use client";

import type { PlanView } from "@shared/domain/types";
import { vehicleLostNote } from "../data/plan.ts";

// R-FLT-06: the draft Planning made when a vehicle went out of use after the
// plan was published (docs/issues/vehicle-broken/PLAN.md). The dispatcher
// reviews it and sends it; nothing reaches the dock or the road before that.

export default function PlanVehicleLost({ plan }: { plan: PlanView | null }): React.JSX.Element | null {
  const note = vehicleLostNote(plan?.supersedes ? plan.revisionReason : null);
  if (!note) return null;
  return (
    <div role="status" className="flex flex-col gap-1 rounded-2xl bg-go-warning-tint px-4 py-3">
      <p className="text-[15px] font-medium text-go-ink">{note.title}</p>
      <p className="text-[13px] text-go-secondary">{note.detail}</p>
    </div>
  );
}
