"use client";

import Driver from "@roles/driver/Driver";
import Loader from "@roles/loader/Loader";
import type {
  Command,
  FieldWorkProps,
  Order,
  OrderStatus,
  QueuedCommand,
} from "@shared/domain/types";

const LOCAL_STATUS: Partial<Record<Command["kind"], OrderStatus | null>> = {
  load: "loaded",
  shortfall: "shortfall",
  depart: "departed",
  arrive: "arrived",
  deliver: null,
};

/** Overlay queued (unsynced) commands onto orders so the UI stays truthful offline. */
function withLocalQueue(orders: Order[], queue: QueuedCommand[]): (Order & { local?: boolean })[] {
  return orders.map((order) => {
    const pending = queue.filter(
      (q) => (q.command.order_id as string) === order.id && !q.error,
    );
    if (!pending.length) return order;
    const item: Order & { local?: boolean } = { ...order, local: true };
    for (const q of pending) {
      const kind = q.command.kind as Command["kind"];
      const mapped = LOCAL_STATUS[kind];
      item.status =
        (mapped as OrderStatus) ??
        ((q.command.outcome as OrderStatus) || item.status);
      item.version += 1;
    }
    return item;
  });
}

export default function FieldWork({ state, act, busy, onDetail, queue }: FieldWorkProps): React.JSX.Element {
  const routes = state.plans
    .filter((p) => p.published)
    .flatMap((p) => p.routes.map((r) => ({ ...r, day: p.day })));
  const orders = withLocalQueue(
    state.orders.filter((o) => o.route_id),
    queue,
  );

  async function doAction(
    kind: Command["kind"],
    order: Order,
    data: Record<string, unknown> = {},
  ): Promise<boolean> {
    return act(kind, { order_id: order.id, version: order.version, ...data });
  }

  const shared = { state, act, busy, onDetail, queue, orders, routes, doAction };
  return state.user.role === "driver" ? <Driver {...shared} /> : <Loader {...shared} />;
}
