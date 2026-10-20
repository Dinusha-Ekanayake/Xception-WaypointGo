"use client";

import { useState } from "react";
import type { ComparisonView, OrderView, PlanView } from "@shared/domain/types";
import { Segmented } from "@shared/ui";
import { clockOn, dayLabel, hhmm, ruleLabel, temperatureLabel } from "@shared/wording";
import { useNextDelivery } from "../data/usePlanReads.ts";

// Figma "Plan · 3 Publish", right card: who is told what when the plan goes
// out. Outlets whose order waits get the reason the dispatcher recorded in
// Decide, drivers get their run sheets and the loaders the dock board. A
// revision tells only the trips and outlets it changes (R-NOT-12). The message
// shown is an example built from the plan; the notification module words the
// one that is sent.

type Tab = "outlets" | "drivers" | "loaders";

/** The outlets a first publish tells: each on a trip (R-NOT-15) and each whose order waits. */
export function toldOutlets(plan: Pick<PlanView, "trips">, waiting: Array<string | undefined>): string[] {
  const onTrips = plan.trips.flatMap((t) => t.stops.map((s) => s.outletId));
  return [...new Set([...onTrips, ...waiting.filter((id): id is string => Boolean(id))])];
}

export default function PublishAudience({
  plan,
  orders,
  changes,
}: {
  plan: PlanView;
  orders: Map<string, OrderView>;
  /** A revision's difference from the published plan; null for a first publish. */
  changes: ComparisonView | null;
}): React.JSX.Element {
  const [tab, setTab] = useState<Tab>("outlets");
  const next = useNextDelivery(plan.serviceDate);
  const deferred = plan.allocations.filter((a) => a.decision === "DEFERRED");
  const waiting = [...new Set(deferred.map((a) => orders.get(a.orderId)?.outletId).filter((id): id is string => Boolean(id)))];
  // Every outlet on a trip is told its delivery is planned (R-NOT-15).
  const planned = [...new Set(plan.trips.flatMap((t) => t.stops.map((s) => s.outletId)))].filter((id) => !waiting.includes(id));
  const outlets = changes ? changes.affectedOutlets : toldOutlets(plan, waiting);
  // Outlets are named as everywhere else: the id with its district.
  const districtOf = new Map([...orders.values()].map((order) => [order.outletId, order.districtName] as const));
  const trips = changes ? plan.trips.filter((t) => changes.changedTrips.includes(t.tripId)) : plan.trips;
  const vehicles = [...new Set(trips.map((t) => t.vehicleId))];
  const first = deferred[0];
  const firstOrder = first ? orders.get(first.orderId) : undefined;

  return (
    <section aria-label="What people will receive" className="flex w-full flex-col gap-3 rounded-go-panel bg-go-card p-5 lg:w-[380px] lg:shrink-0">
      <h2 className="text-[19px] font-medium text-go-ink">What people will receive</h2>
      <Segmented
        label="Who is told"
        value={tab}
        onChange={setTab}
        options={[
          { value: "outlets", label: `Outlets (${outlets.length})` },
          { value: "drivers", label: `Drivers (${vehicles.length})` },
          { value: "loaders", label: "Loaders" },
        ]}
      />

      {tab === "outlets" &&
        (outlets.length === 0 ? (
          <p className="text-[13px] text-go-secondary">{changes ? "No outlet is affected by this change." : "No outlet is on this plan."}</p>
        ) : (
          <>
            {!changes && planned.length > 0 && (
              <div>
                <p className="pb-1 text-[13px] font-medium text-go-ink">Told their delivery is planned</p>
                <ul className="flex flex-col gap-1 text-[13px] text-go-ink">
                  {planned.map((outlet) => (
                    <li key={outlet}>{`· ${outlet}${districtOf.get(outlet) ? ` ${districtOf.get(outlet)}` : ""}`}</li>
                  ))}
                </ul>
              </div>
            )}
            {firstOrder && first && !changes && (
              <div className="rounded-go-card bg-go-surface p-3">
                <p className="pb-2 text-xs text-go-secondary">{`GO · to ${firstOrder.outletId} ${firstOrder.districtName}`}</p>
                <div className="flex flex-col gap-2 rounded-go-input bg-go-card p-3 text-[13px] text-go-ink">
                  <p>{`Hi, your ${temperatureLabel(firstOrder.temperature).toLowerCase()} order ${firstOrder.orderRef} can't go on ${dayLabel(plan.serviceDate)}. ${ruleLabel(first.bindingRule)}.`}</p>
                  {next.data && <p className="font-medium">{`It will be first on ${dayLabel(next.data)}.`}</p>}
                  <p className="text-go-secondary">Questions? Call dispatch.</p>
                </div>
              </div>
            )}
            {(changes || waiting.length > 0) && (
              <>
                <p className="text-xs text-go-secondary">{changes ? "Outlets whose delivery changes" : "One message per outlet whose order waits · reason from Decide · an example, the notification service words it"}</p>
                {(changes ? outlets : waiting.slice(1)).length > 0 && (
                  <div>
                    <p className="pb-1 text-[13px] font-medium text-go-ink">{changes ? "Sent to" : "Also told their order waits"}</p>
                    <ul className="flex flex-col gap-1 text-[13px] text-go-ink">
                      {(changes ? outlets : waiting.slice(1)).map((outlet) => (
                        <li key={outlet}>{`· ${outlet}${districtOf.get(outlet) ? ` ${districtOf.get(outlet)}` : ""}`}</li>
                      ))}
                    </ul>
                  </div>
                )}
              </>
            )}
          </>
        ))}

      {tab === "drivers" && (
        <ul className="flex flex-col gap-1.5 text-[13px] text-go-ink">
          {vehicles.length === 0 && <li className="text-go-secondary">No driver is affected.</li>}
          {vehicles.map((vehicleId) => {
            const own = trips.filter((t) => t.vehicleId === vehicleId);
            return (
              <li key={vehicleId} className="flex justify-between gap-2 rounded-go-input bg-go-surface px-3 py-2">
                <span className="font-medium">{vehicleId}</span>
                <span className="text-go-secondary">{`${own.length} ${own.length === 1 ? "trip" : "trips"} · departs ${clockOn(own[0]!.plannedDeparture, plan.serviceDate)}`}</span>
              </li>
            );
          })}
          <li className="pt-1 text-xs text-go-secondary">Each driver gets the run sheet on the phone; an offline phone gets it on reconnect.</li>
        </ul>
      )}

      {tab === "loaders" && (
        <p className="rounded-go-input bg-go-surface px-3 py-2.5 text-[13px] text-go-ink">
          {`Dock board at ${plan.depotCode}: ${trips.length} ${trips.length === 1 ? "trip" : "trips"} to load${trips[0] ? `, first departs ${clockOn([...trips].sort((a, b) => a.plannedDeparture.localeCompare(b.plannedDeparture))[0]!.plannedDeparture, plan.serviceDate)}` : ""}.`}
        </p>
      )}
    </section>
  );
}
