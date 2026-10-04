import type { OrderStatus, OrderView } from "../../../shared/domain/ordering.ts";

// Which delivery the store manager's Home card shows (Figma "02 Home", 11:112937;
// deadline-day UX plan U3). The booklet: the store manager "needs an expected
// arrival time to schedule staff". Today's delivery on the way comes first, as
// before; with none, the next one ahead on a plan, or confirmed and waiting for
// it, so the card never says "nothing" while a delivery is already planned.

const ON_THE_WAY: OrderStatus[] = ["LOADING", "IN_TRANSIT"];
const AHEAD: OrderStatus[] = ["CONFIRMED", "ALLOCATED", "LOADING", "IN_TRANSIT"];

export type NextDelivery = {
  order: OrderView;
  /** Today's, on the way or arrived; or a later day's, planned or confirmed. */
  when: "today" | "ahead";
  /** Its place among the same day's deliveries, for "1 of 2". */
  position: number;
  ofDay: number;
};

export function nextDelivery(orders: OrderView[], today: string): NextDelivery | null {
  const live = orders.filter((o) => o.status !== "CANCELLED");
  const todays = live.filter((o) => o.deliveryDate === today);
  const coming = todays.find((o) => ON_THE_WAY.includes(o.status)) ?? todays.find((o) => o.status === "DELIVERED");
  if (coming) return { order: coming, when: "today", position: todays.indexOf(coming) + 1, ofDay: todays.length };

  const ahead = live
    .filter((o) => o.deliveryDate > today && AHEAD.includes(o.status))
    .sort((a, b) => a.deliveryDate.localeCompare(b.deliveryDate) || a.orderRef.localeCompare(b.orderRef));
  const first = ahead[0];
  if (!first) return null;
  const sameDay = ahead.filter((o) => o.deliveryDate === first.deliveryDate);
  return { order: first, when: "ahead", position: 1, ofDay: sameDay.length };
}

/** The chip on a delivery ahead: on a published plan, at the dock, or still waiting for the plan. */
export function aheadLabel(status: OrderStatus): string {
  if (status === "IN_TRANSIT") return "On the way";
  if (status === "LOADING") return "Loading";
  if (status === "ALLOCATED") return "Planned";
  return "Waiting for the plan";
}
