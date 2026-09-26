import type { Order, PlannedRoute } from "./types.ts";

export function sortDeliveryStops(orders: Order[], routes: PlannedRoute[]): Order[] {
  const starts = new Map(routes.map(route => [route.id, route.start]));
  return [...orders].sort((a, b) =>
    (starts.get(a.route_id || "") ?? Number.MAX_SAFE_INTEGER) -
      (starts.get(b.route_id || "") ?? Number.MAX_SAFE_INTEGER) ||
    (a.route_id || "").localeCompare(b.route_id || "") ||
    (a.sequence ?? 0) - (b.sequence ?? 0),
  );
}
