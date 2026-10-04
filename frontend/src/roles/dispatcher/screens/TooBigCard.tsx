"use client";

import { useState } from "react";
import type { OrderView, PlanView, VehicleView } from "@shared/domain/types";
import { Pill, PrimaryButton, SecondaryButton } from "@shared/ui";
import { ruleLabel } from "@shared/wording";
import { largestVehicleM3, unservable } from "../data/planViews.ts";
import type { PlanActions } from "./planActions.ts";

// Figma "Plan · 1e Decide · too big" and "1p store manager contacted": the orders
// no vehicle of the depot can carry, drawn apart because nobody can place them.
// What a dispatcher can do is tell the store, so it can split the order or pick
// another day, with the dispatcher's own words recorded on the message.

const MIN = 3;

function draftMessage(order: OrderView | undefined): string {
  const ref = order?.orderRef ?? "This order";
  return `${ref} is bigger than any vehicle we have, so we cannot deliver it as it is. Please split it into smaller orders or tell us another day.`;
}

export default function TooBigCard({
  plan,
  orders,
  fleet,
  editable,
  actions,
}: {
  plan: PlanView;
  orders: Map<string, OrderView>;
  fleet: VehicleView[];
  editable: boolean;
  actions: PlanActions;
}): React.JSX.Element | null {
  const rows = unservable(plan, orders);
  const largest = largestVehicleM3(fleet);
  const [writing, setWriting] = useState<string | null>(null);
  const [message, setMessage] = useState("");
  const [told, setTold] = useState<Set<string>>(new Set());
  if (rows.length === 0) return null;

  return (
    <section aria-label="Too big for any vehicle" className="flex flex-col gap-2 rounded-[24px] bg-go-card p-4 shadow-go-card">
      <h2 className="px-1 text-[17px] font-medium text-go-ink">Too big for any vehicle</h2>
      <ul className="flex flex-col gap-2">
        {rows.map(({ allocation, order }) => (
          <li key={allocation.orderId} className="rounded-go-card bg-go-subtle px-4 py-3">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <span className="min-w-[200px] flex-1">
                <span className="flex flex-wrap items-center gap-2 text-[14px] font-medium text-go-ink">
                  {order?.orderRef ?? allocation.orderId}
                  {order && <span className="font-normal">{`${order.outletId} ${order.districtName}`}</span>}
                  {order && <Pill tone="info">{order.brandCode}</Pill>}
                </span>
                <span className="block text-xs text-go-secondary">
                  {order ? `${Number(order.volumeM3).toFixed(1)} m³` : ""}
                  {largest !== null ? ` · largest vehicle ${largest.toFixed(0)} m³` : ""}
                  {allocation.bindingRule ? ` · ${ruleLabel(allocation.bindingRule)}` : ""}
                </span>
              </span>
              {told.has(allocation.orderId) ? (
                <Pill tone="success">Store manager contacted</Pill>
              ) : (
                <SecondaryButton
                  disabled={!editable || actions.busy}
                  onClick={() => {
                    setWriting(allocation.orderId);
                    setMessage(draftMessage(order));
                  }}
                >
                  Contact store manager
                </SecondaryButton>
              )}
            </div>
            {writing === allocation.orderId && (
              <div className="mt-3 flex flex-col gap-2">
                <label className="flex flex-col gap-1 text-[13px] font-medium text-go-ink">
                  Message to the store manager
                  <textarea
                    value={message}
                    maxLength={500}
                    rows={3}
                    onChange={(event) => setMessage(event.target.value)}
                    className="rounded-go-input border border-go-rule bg-white px-3 py-2.5 text-[14px] font-normal outline-none focus:border-go-teal"
                  />
                </label>
                <div className="flex gap-2">
                  <PrimaryButton
                    disabled={actions.busy || message.trim().length < MIN}
                    onClick={() =>
                      void actions.contactStore(allocation.orderId, message.trim()).then((ok) => {
                        if (!ok) return;
                        setTold((now) => new Set([...now, allocation.orderId]));
                        setWriting(null);
                      })
                    }
                  >
                    Send message
                  </PrimaryButton>
                  <SecondaryButton onClick={() => setWriting(null)}>Cancel</SecondaryButton>
                </div>
              </div>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
