"use client";

import { useEffect, useMemo, useState } from "react";
import type { OrderView, PlacementView, PlanView, VehicleView } from "@shared/domain/types";
import { Icon, Pill, PrimaryButton, SecondaryButton } from "@shared/ui";
import { temperatureLabel } from "@shared/wording";
import { size } from "../data/orders.ts";
import { decidedCount, lastServedText, type DecisionRow } from "../data/planViews.ts";
import { usePlacements } from "../data/usePlanReads.ts";
import DecisionPanel from "./DecisionPanel.tsx";
import type { PlanActions } from "./planActions.ts";
import ReasonPicker, { reasonReady } from "./ReasonPicker.tsx";
import SwapWindow from "./SwapWindow.tsx";
import TooBigCard from "./TooBigCard.tsx";

// Figma "Plan · 1 Decide": the orders the engine did not place, each with what a
// dispatcher can do, and the orders no vehicle can carry apart from them. A row
// says at a glance where the order could go; the panel beside it says why it was
// not placed and lets the dispatcher place it, swap it or keep it deferred.

export default function PlanDecide({
  plan,
  rows,
  orders,
  fleet,
  editable,
  actions,
  focusId = null,
}: {
  plan: PlanView;
  rows: DecisionRow[];
  orders: Map<string, OrderView>;
  fleet: VehicleView[];
  editable: boolean;
  actions: PlanActions;
  /** An order to open first, as when the Deferred column or the Publish step sends the dispatcher here. */
  focusId?: string | null;
}): React.JSX.Element {
  const [selectedId, setSelectedId] = useState<string | null>(focusId);
  useEffect(() => {
    if (focusId) setSelectedId(focusId);
  }, [focusId]);
  const [swapping, setSwapping] = useState(false);
  const [keeping, setKeeping] = useState(false);
  const [reason, setReason] = useState("");

  const open = useMemo(() => rows.filter((row) => row.state === "open"), [rows]);
  const placeable = useMemo(() => (editable ? open.map((row) => row.allocation.orderId) : []), [editable, open]);
  const places = usePlacements(plan.planId, placeable);
  const { decided, total } = decidedCount(rows);
  const selected = rows.find((row) => row.allocation.orderId === selectedId) ?? rows[0] ?? null;
  const placesFor = (orderId: string): PlacementView[] | null => (places.data ? (places.data[orderId] ?? []) : null);

  if (rows.length === 0) {
    return (
      <div className="flex min-h-0 w-full flex-1 flex-col gap-[18px]">
        <section aria-label="Decisions" className="rounded-[24px] bg-go-card px-6 py-8 text-center shadow-go-card">
          <h2 className="text-[19px] font-medium text-go-ink">Every order is on a trip</h2>
          <p className="mt-1 text-[13px] text-go-secondary">Nothing was deferred. Check the trips, then publish.</p>
        </section>
        <TooBigCard plan={plan} orders={orders} fleet={fleet} editable={editable} actions={actions} />
      </div>
    );
  }

  return (
    <div className="flex min-h-0 w-full flex-1 gap-[18px] max-lg:flex-col">
      <div className="flex min-w-0 flex-1 flex-col gap-[18px]">
        <section aria-label="Orders needing a decision" className="flex flex-col gap-2 rounded-[24px] bg-go-card p-4 shadow-go-card">
          <header className="flex flex-wrap items-center gap-2 px-1 pb-1">
            <h2 className="mr-auto text-[19px] font-medium text-go-ink">
              {open.length > 0 ? `${open.length} ${open.length === 1 ? "order needs" : "orders need"} your decision` : "Every order is decided"}
            </h2>
            <span className="rounded-full bg-go-surface px-2.5 py-1 text-xs text-go-secondary">{`${decided} of ${total} decided`}</span>
            {editable && open.length > 0 && (
              <button
                type="button"
                disabled={actions.busy}
                onClick={() => setKeeping((value) => !value)}
                className="rounded-full bg-go-mint px-[18px] py-2.5 text-sm font-medium text-go-ink disabled:cursor-not-allowed disabled:opacity-40"
              >
                Keep the rest deferred
              </button>
            )}
          </header>
          {keeping && (
            <div className="flex flex-col gap-2 rounded-go-card bg-go-subtle p-3">
              <ReasonPicker label={`Why do the other ${open.length} stay deferred?`} value={reason} onChange={setReason} />
              <div className="flex gap-2">
                <PrimaryButton
                  disabled={actions.busy || !reasonReady(reason)}
                  onClick={() =>
                    void actions.keepDeferred(open.map((row) => row.allocation.orderId), reason.trim()).then((ok) => {
                      if (!ok) return;
                      setKeeping(false);
                      setReason("");
                    })
                  }
                >
                  Keep them deferred
                </PrimaryButton>
                <SecondaryButton onClick={() => setKeeping(false)}>Cancel</SecondaryButton>
              </div>
            </div>
          )}
          <ul className="flex flex-col gap-2">
            {rows.map((row) => (
              <li key={row.allocation.orderId}>
                <DecisionRowButton
                  row={row}
                  serviceDate={plan.serviceDate}
                  active={selected?.allocation.orderId === row.allocation.orderId}
                  places={placesFor(row.allocation.orderId)}
                  onSelect={() => setSelectedId(row.allocation.orderId)}
                />
              </li>
            ))}
          </ul>
        </section>
        <TooBigCard plan={plan} orders={orders} fleet={fleet} editable={editable} actions={actions} />
      </div>

      {selected && (
        <DecisionPanel
          key={`${plan.planId}:${selected.allocation.orderId}`}
          plan={plan}
          row={selected}
          places={placesFor(selected.allocation.orderId)}
          editable={editable}
          actions={actions}
          onSwap={() => setSwapping(true)}
        />
      )}
      {swapping && selected?.order && <SwapWindow plan={plan} incoming={selected.order} orders={orders} actions={actions} onClose={() => setSwapping(false)} />}
    </div>
  );
}

function DecisionRowButton({
  row,
  serviceDate,
  active,
  places,
  onSelect,
}: {
  row: DecisionRow;
  serviceDate: string;
  active: boolean;
  places: PlacementView[] | null;
  onSelect: () => void;
}): React.JSX.Element {
  const { allocation, order } = row;
  const fit = places?.find((place) => place.feasible) ?? null;
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onSelect}
      className={`flex w-full flex-wrap items-center gap-x-3 gap-y-1 rounded-go-card px-4 py-3 text-left ${active ? "bg-go-success-tint" : "bg-go-subtle"}`}
    >
      <span aria-hidden className={`size-2 shrink-0 rounded-full ${row.state === "open" && (order?.deferralCount ?? 0) > 0 ? "bg-go-warning" : "bg-go-placeholder"}`} />
      <span className="min-w-[200px] flex-1">
        <span className="flex items-center gap-2">
          <span className="text-[15px] font-medium text-go-ink">{order?.orderRef ?? allocation.orderId}</span>
          {order && <Pill tone="success">{order.brandCode}</Pill>}
        </span>
        {order && (
          <span className="block text-xs text-go-secondary">
            {`${order.outletId} · ${order.districtName} · ${size(order)} · ${temperatureLabel(order.temperature).toLowerCase()}`}
          </span>
        )}
      </span>
      <span className="text-xs font-medium text-go-warning-text">{lastServedText(allocation.lastServedOn, serviceDate)}</span>
      <span className="flex min-w-[120px] justify-end">
        {row.state === "placed" && <Pill tone="success">{`Placed · ${row.placedOn ?? "on a trip"}`}</Pill>}
        {row.state === "kept" && <Pill tone="muted">Kept deferred</Pill>}
        {row.state === "open" && places !== null && (fit ? <Pill tone="success">{`Add ${fit.vehicleId} · fits`}</Pill> : <Pill tone="muted">No place fits</Pill>)}
      </span>
      <Icon name="chevron-right" />
    </button>
  );
}
