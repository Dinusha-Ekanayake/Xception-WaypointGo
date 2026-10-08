import { request } from "../../../shared/api/client.ts";
import type { WhatIfVehicleView, WhatIfView } from "../../../shared/domain/planning.ts";

// Roadmap AI-1: what one vehicle back from the workshop would change. The
// engine runs the plan's day twice on the server; nothing here is worked out,
// only put into words, and nothing is stored or changed.

export function fetchWhatIfVehicles(planId: string, signal?: AbortSignal) {
  return request<WhatIfVehicleView[]>(`/api/plans/${encodeURIComponent(planId)}/what-if/vehicles`, { signal });
}

export function fetchWhatIf(planId: string, vehicleId: string, signal?: AbortSignal) {
  return request<WhatIfView>(`/api/plans/${encodeURIComponent(planId)}/what-if?vehicle=${encodeURIComponent(vehicleId)}`, { signal });
}

/** "VEH001 · truck · refrigerated · 26.4 m³", as a choice reads. */
export function vehicleChoice(v: WhatIfVehicleView): string {
  return `${v.vehicleId} · ${v.van ? "van" : "truck"}${v.refrigerated ? " · refrigerated" : ""} · ${Number(v.volumeCapM3)} m³`;
}

const m3 = (v: number | string) => `${Number(v).toFixed(1)} m³`;
const many = (n: number, one: string, more: string) => `${n} ${n === 1 ? one : more}`;

/** The answer in words, and the facts a model may reword (the plan kind's allow-list). */
export function whatIfWords(w: WhatIfView): { headline: string; whatIf: string; nowServed: string[]; nowDeferred: string[]; next: string } {
  const gained = w.servedAfter - w.servedBefore;
  const line = (o: WhatIfView["nowServed"][number]) => `${o.orderRef} for ${o.outletId} (${o.brand}${o.temperature === "chilled" ? ", chilled" : ""}, ${m3(o.volumeM3)})`;
  return {
    headline: `With ${w.vehicleId} available, the same orders would be planned again.`,
    whatIf:
      gained > 0
        ? `${many(w.servedAfter, "order is", "orders are")} served instead of ${w.servedBefore}, and ${m3(w.chilledM3After)} of chilled goods instead of ${m3(w.chilledM3Before)}, on ${many(w.vehiclesAfter, "vehicle", "vehicles")} instead of ${w.vehiclesBefore}.`
        : gained === 0 && w.nowServed.length === 0
          ? `It changes nothing on this day: the same ${many(w.servedBefore, "order is", "orders are")} served.`
          : `${many(w.servedAfter, "order is", "orders are")} served instead of ${w.servedBefore}.`,
    nowServed: w.nowServed.map(line),
    nowDeferred: w.nowDeferred.map(line),
    next: gained > 0
      ? "If the vehicle can come back for the day, set it available for that day and generate the plan again; the plan is not changed here."
      : "Keeping it in the workshop costs nothing on this day.",
  };
}
