"use client";

import { useState } from "react";
import type { ComparisonView, OrderView, SnapshotView } from "@shared/domain/types";
import { Menu, Pill, PrimaryButton } from "@shared/ui";
import { clock } from "@shared/wording";
import { useComparison } from "../data/usePlanReads.ts";
import Refusal from "./Refusal.tsx";

// Figma "Plan · 4 Compare": two plans of the day side by side, to choose which to
// publish. Each side is the working draft or a saved plan; what differs is what
// the engine and the dispatcher each did to the orders. Choosing a saved plan
// returns the draft to it (the saved plan itself is never edited).

export type Pick = { id: string; label: string; snapshotId: string | null };

const SIDE_TEXT: Record<"MOVED" | "ADDED" | "DROPPED", string> = {
  MOVED: "Moved",
  ADDED: "Added",
  DROPPED: "Dropped",
};

export function pickLabel(snapshot: SnapshotView): string {
  return `${snapshot.label} · ${clock(snapshot.createdAt)}`;
}

export default function PlanCompare({
  working,
  snapshots,
  orders,
  editable,
  busy,
  onUse,
}: {
  /** The plan being worked on, drawn as "Working draft". */
  working: { id: string; version: number };
  snapshots: SnapshotView[];
  orders: Map<string, OrderView>;
  editable: boolean;
  busy: boolean;
  onUse: (snapshotId: string) => void;
}): React.JSX.Element {
  const picks: Pick[] = [
    { id: working.id, label: `Working draft (version ${working.version})`, snapshotId: null },
    ...snapshots.map((s) => ({ id: s.snapshotId, label: pickLabel(s), snapshotId: s.snapshotId })),
  ];
  const auto = snapshots.find((s) => s.kind === "AUTO");
  const [aId, setAId] = useState(auto?.snapshotId ?? picks[picks.length - 1]!.id);
  const [bId, setBId] = useState(working.id);
  const a = picks.find((p) => p.id === aId) ?? picks[0]!;
  const b = picks.find((p) => p.id === bId) ?? picks[0]!;
  const comparison = useComparison(a.id === b.id ? null : a.id, a.id === b.id ? null : b.id);

  return (
    <section aria-label="Compare plans" className="flex w-full flex-col gap-4 rounded-[24px] bg-go-card p-6 shadow-go-card">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="mr-auto text-[19px] font-medium text-go-ink">Compare plans</h2>
        <Chooser name="Plan A" current={a} picks={picks} onPick={setAId} />
        <Chooser name="Plan B" current={b} picks={picks} onPick={setBId} />
      </div>

      {a.id === b.id && <p className="text-[13px] text-go-secondary">Pick two different plans to compare.</p>}
      {comparison.error && <Refusal error={comparison.error} what="the comparison" />}
      {comparison.loading && !comparison.data && a.id !== b.id && <p className="text-[13px] text-go-secondary">Comparing…</p>}
      {comparison.data && a.id !== b.id && <Result view={comparison.data} orders={orders} />}

      <div className="flex flex-wrap gap-2">
        {[a, b].map((side, index) =>
          side.snapshotId ? (
            <PrimaryButton key={side.id} disabled={!editable || busy} onClick={() => onUse(side.snapshotId!)}>
              {`Use plan ${index === 0 ? "A" : "B"}`}
            </PrimaryButton>
          ) : null,
        )}
      </div>
    </section>
  );
}

function Chooser({ name, current, picks, onPick }: { name: string; current: Pick; picks: Pick[]; onPick: (id: string) => void }): React.JSX.Element {
  return (
    <Menu
      label={`${name}: choose a plan`}
      align="right"
      className="flex flex-col rounded-go-card bg-go-surface px-3.5 py-1.5 text-left"
      items={picks.map((p) => ({ id: p.id, label: p.label, selected: p.id === current.id }))}
      onSelect={onPick}
    >
      <span className="text-[11px] text-go-secondary">{name}</span>
      <span className="max-w-[220px] truncate text-[13px] font-medium text-go-ink">{current.label}</span>
    </Menu>
  );
}

function Result({ view, orders }: { view: ComparisonView; orders: Map<string, OrderView> }): React.JSX.Element {
  return (
    <>
      <div className="grid grid-cols-[1fr_auto_auto] items-center gap-x-6 gap-y-1 text-[13px] text-go-ink max-sm:grid-cols-[1fr_auto_auto]">
        <span />
        <span className="text-xs font-medium text-go-secondary">Plan A</span>
        <span className="text-xs font-medium text-go-secondary">Plan B</span>
        <Row label="Orders planned" a={view.a.served} b={view.b.served} better="more" />
        <Row label="Deferred" a={view.a.deferred} b={view.b.deferred} better="fewer" />
        <Row label="Cannot be served" a={view.a.unservable} b={view.b.unservable} better="fewer" />
        <Row label="Trips" a={view.a.trips} b={view.b.trips} />
        <Row label="Vehicles in use" a={view.a.vehicles} b={view.b.vehicles} />
      </div>

      <div className="flex flex-col gap-1.5">
        <h3 className="text-[15px] font-medium text-go-ink">{view.changes.length === 0 ? "The two plans place every order the same way" : `${view.changes.length} ${view.changes.length === 1 ? "order differs" : "orders differ"}`}</h3>
        <ul className="flex flex-col">
          {view.changes.map((change) => (
            <li key={change.orderId} className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-go-rule py-2 text-[13px]">
              <Pill tone={change.kind === "DROPPED" ? "warning" : change.kind === "ADDED" ? "success" : "info"}>{SIDE_TEXT[change.kind]}</Pill>
              <span className="min-w-[110px] font-medium text-go-ink">{orders.get(change.orderId)?.orderRef ?? change.orderId}</span>
              <span className="text-go-secondary">{change.outletId ?? ""}</span>
              <span className="ml-auto text-go-ink">{`${where(change.before)} to ${where(change.after)}`}</span>
            </li>
          ))}
        </ul>
        {view.changedTrips.length > 0 && <p className="text-xs text-go-secondary">{`Plan B changes ${view.changedTrips.length} ${view.changedTrips.length === 1 ? "trip" : "trips"} a driver would see differently.`}</p>}
      </div>
    </>
  );
}

function where(place: { decision: string | null; vehicleId: string | null; tripNumber: number | null }): string {
  if (place.decision === null) return "not in the plan";
  if (place.decision !== "SERVED") return place.decision === "DEFERRED" ? "deferred" : "cannot be served";
  return `${place.vehicleId} Trip ${place.tripNumber}`;
}

function Row({ label, a, b, better }: { label: string; a: number; b: number; better?: "more" | "fewer" }): React.JSX.Element {
  const good = (n: number, other: number) => better !== undefined && n !== other && (better === "more" ? n > other : n < other);
  return (
    <>
      <span className="text-go-secondary">{label}</span>
      <span className={`text-right tabular-nums ${good(a, b) ? "font-medium text-go-success" : ""}`}>{a}</span>
      <span className={`text-right tabular-nums ${good(b, a) ? "font-medium text-go-success" : ""}`}>{b}</span>
    </>
  );
}
