"use client";

import { useState } from "react";
import type { ComparisonView, OrderView, SnapshotView } from "@shared/domain/types";
import { Icon, Menu, Pill, PrimaryButton, SecondaryButton } from "@shared/ui";
import { clock } from "@shared/wording";
import { useComparison } from "../data/usePlanReads.ts";
import Refusal from "./Refusal.tsx";

// Figma "Plan · 4 Compare": two plans of the day side by side, to choose which to
// publish. Each side is the working draft or a saved plan; what differs is what
// the engine and the dispatcher each did to the orders. Choosing a saved plan
// returns the draft to it (the saved plan itself is never edited).

export type Pick = { id: string; label: string; snapshotId: string | null };

export function pickLabel(snapshot: SnapshotView): string {
  return `${snapshot.label} · ${clock(snapshot.createdAt)}`;
}

export default function PlanCompare({
  working,
  snapshots,
  orders,
  editable,
  busy,
  onBack,
  onSave,
  onUse,
}: {
  /** The plan being worked on, drawn as "Working draft". */
  working: { id: string; version: number };
  snapshots: SnapshotView[];
  orders: Map<string, OrderView>;
  editable: boolean;
  busy: boolean;
  /** Back to the plan, where Compare was opened from. */
  onBack: () => void;
  /** Saves the working draft, so there is something to compare it with; absent on a published plan. */
  onSave?: () => void;
  onUse: (snapshotId: string) => void;
}): React.JSX.Element {
  const picks: Pick[] = [
    { id: working.id, label: `Working draft (version ${working.version})`, snapshotId: null },
    ...snapshots.map((s) => ({ id: s.snapshotId, label: pickLabel(s), snapshotId: s.snapshotId })),
  ];
  // An optimised draft opens against the rules plan made beside it (planning v2); otherwise the engine's own plan.
  const auto = snapshots.find((s) => s.kind === "RULES" && s.sourcePlanId === working.id) ?? snapshots.find((s) => s.kind === "AUTO");
  const [aId, setAId] = useState(auto?.snapshotId ?? picks[picks.length - 1]!.id);
  const [bId, setBId] = useState(working.id);
  const a = picks.find((p) => p.id === aId) ?? picks[0]!;
  const b = picks.find((p) => p.id === bId) ?? picks[0]!;
  const comparison = useComparison(a.id === b.id ? null : a.id, a.id === b.id ? null : b.id);

  return (
    <div className="flex w-full gap-[18px] max-lg:flex-col">
      <section aria-label="Compare plans" className="flex min-w-0 flex-1 flex-col gap-4 rounded-go-panel bg-go-card p-5">
        <div className="flex items-center gap-3">
          <h2 className="flex-1 text-[19px] font-medium text-go-ink">Compare plans</h2>
          <SecondaryButton onClick={onBack}>Back to the plan</SecondaryButton>
        </div>
        <div className="flex items-center gap-3">
          <Chooser name="Plan A" dot="bg-go-ink" current={a} picks={picks} onPick={setAId} disabled={picks.length < 2} />
          <span className="text-xs text-go-secondary">vs</span>
          <Chooser name="Plan B" dot="bg-go-teal" current={b} picks={picks} onPick={setBId} disabled={picks.length < 2} />
        </div>

        {a.id === b.id &&
          (snapshots.length === 0 ? (
            <div className="flex flex-wrap items-center gap-3 rounded-go-card bg-go-surface px-4 py-3">
              <p className="min-w-[220px] flex-1 text-[13px] text-go-ink">
                There is only the working draft. Save a snapshot, change the plan, then compare the two.
              </p>
              {onSave && (
                <SecondaryButton disabled={busy} onClick={onSave}>
                  Save snapshot now
                </SecondaryButton>
              )}
            </div>
          ) : (
            <p className="text-[13px] text-go-secondary">Pick two different plans to compare.</p>
          ))}
        {comparison.error && <Refusal error={comparison.error} what="the comparison" />}
        {comparison.loading && !comparison.data && a.id !== b.id && <p className="text-[13px] text-go-secondary">Comparing…</p>}
        {comparison.data && a.id !== b.id && <Metrics view={comparison.data} a={a} b={b} />}

        <div className="mt-auto flex flex-wrap gap-2">
          {[a, b].map((side, index) =>
            side.snapshotId ? (
              <PrimaryButton key={side.id} disabled={!editable || busy} onClick={() => onUse(side.snapshotId!)}>
                {`Use plan ${index === 0 ? "A" : "B"}`}
              </PrimaryButton>
            ) : null,
          )}
        </div>
      </section>

      <section aria-label="Orders that differ" className="flex w-full flex-col gap-2 rounded-go-panel bg-go-card p-5 lg:w-[380px] lg:shrink-0">
        <header className="flex items-baseline justify-between gap-2">
          <h2 className="text-[19px] font-medium text-go-ink">Orders that differ</h2>
          {comparison.data && <span className="text-xs text-go-secondary">{`${comparison.data.changes.length} ${comparison.data.changes.length === 1 ? "order" : "orders"}`}</span>}
        </header>
        {comparison.data && a.id !== b.id ? <Differences view={comparison.data} orders={orders} /> : <p className="text-[13px] text-go-secondary">Nothing to compare yet.</p>}
      </section>
    </div>
  );
}

function Chooser({
  name,
  dot,
  current,
  picks,
  onPick,
  disabled = false,
}: {
  name: string;
  dot: string;
  current: Pick;
  picks: Pick[];
  onPick: (id: string) => void;
  /** Only one plan to pick from. */
  disabled?: boolean;
}): React.JSX.Element {
  return (
    <div className="min-w-0 flex-1">
      <Menu
        label={`${name}: choose a plan`}
        disabled={disabled}
        className="flex w-full items-center gap-3 rounded-go-input border border-go-rule bg-go-card px-3.5 py-2 text-left"
        items={picks.map((p) => ({ id: p.id, label: p.label, selected: p.id === current.id }))}
        onSelect={onPick}
      >
        <span aria-hidden className={`size-2 shrink-0 rounded-full ${dot}`} />
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="text-[10px] font-medium tracking-[0.08em] text-go-secondary uppercase">{name}</span>
          <span className="truncate text-[14px] font-medium text-go-ink">{current.label}</span>
        </span>
        <Icon name="chevron-down" />
      </Menu>
    </div>
  );
}

function Metrics({ view, a, b }: { view: ComparisonView; a: Pick; b: Pick }): React.JSX.Element {
  const short = (p: Pick) => p.label.split(" · ")[0]!.replace(/ \(version \d+\)/, "");
  return (
    <table className="w-full text-left text-[13px]">
      <thead>
        <tr className="text-[10px] tracking-[0.08em] text-go-secondary uppercase">
          <th scope="col" className="w-[40%] pb-2 font-medium" />
          <th scope="col" className="pb-2 font-medium">{`A · ${short(a)}`}</th>
          <th scope="col" className="pb-2 font-medium">{`B · ${short(b)}`}</th>
          <th scope="col" className="pb-2 font-medium">Decision impact</th>
        </tr>
      </thead>
      <tbody>
        <Metric label="Orders served" note="planned onto a trip" a={view.a.served} b={view.b.served} better="more" />
        <Metric label="Orders deferred" note="move to the next run" a={view.a.deferred} b={view.b.deferred} better="fewer" />
        <Metric label="Cannot be served" note="need a new order from the store" a={view.a.unservable} b={view.b.unservable} better="fewer" />
        <Metric label="Trips" note="loaded and driven" a={view.a.trips} b={view.b.trips} />
        <Metric label="Vehicles used" note="out of the depot" a={view.a.vehicles} b={view.b.vehicles} />
      </tbody>
    </table>
  );
}

function Metric({ label, note, a, b, better }: { label: string; note: string; a: number; b: number; better?: "more" | "fewer" }): React.JSX.Element {
  const diff = b - a;
  const good = better !== undefined && diff !== 0 && (better === "more" ? diff > 0 : diff < 0);
  return (
    <tr className="border-t border-go-rule">
      <th scope="row" className="py-3 font-normal">
        <span className="block font-medium text-go-ink">{label}</span>
        <span className="block text-xs text-go-secondary">{note}</span>
      </th>
      <td className="py-3 text-go-ink tabular-nums">{a}</td>
      <td className="py-3 font-semibold text-go-ink tabular-nums">{b}</td>
      <td className="py-3">
        <Pill tone={diff === 0 ? "muted" : good ? "success" : "warning"}>{diff === 0 ? "Same" : `${diff > 0 ? "+" : "-"}${Math.abs(diff)}`}</Pill>
      </td>
    </tr>
  );
}

function Differences({ view, orders }: { view: ComparisonView; orders: Map<string, OrderView> }): React.JSX.Element {
  if (view.changes.length === 0) return <p className="text-[13px] text-go-secondary">The two plans place every order the same way.</p>;
  return (
    <table className="w-full text-left text-[13px]">
      <thead>
        <tr className="text-[10px] tracking-[0.08em] text-go-secondary uppercase">
          <th scope="col" className="pb-2 font-medium">Order</th>
          <th scope="col" className="pb-2 font-medium">A</th>
          <th scope="col" className="pb-2 font-medium">B</th>
        </tr>
      </thead>
      <tbody>
        {view.changes.map((change) => (
          <tr key={change.orderId} className="border-t border-go-rule">
            <th scope="row" className="py-2.5 font-normal">
              <span className="block font-medium text-go-ink">{orders.get(change.orderId)?.orderRef ?? change.orderId}</span>
              <span className="block text-xs text-go-secondary">{change.outletId ?? ""}</span>
            </th>
            <td className={`py-2.5 ${tone(change.before)}`}>{where(change.before)}</td>
            <td className={`py-2.5 ${tone(change.after)}`}>{where(change.after)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function tone(place: { decision: string | null }): string {
  return place.decision === "SERVED" ? "text-go-teal" : "text-go-warning-text";
}

function where(place: { decision: string | null; vehicleId: string | null; tripNumber: number | null }): string {
  if (place.decision === null) return "Not in the plan";
  if (place.decision !== "SERVED") return place.decision === "DEFERRED" ? "Deferred" : "Cannot be served";
  return `${place.vehicleId} Trip ${place.tripNumber}`;
}
