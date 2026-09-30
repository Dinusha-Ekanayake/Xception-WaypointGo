import type { Command, CommandAck } from "@shared/api/commands";
import { ApiError } from "@shared/api/problem";
import {
  OrderCommandKind,
  ReceiptCommandKind,
  type AmendOrder,
  type CalendarAnswer,
  type CancelOrder,
  type ConfirmPartialReceipt,
  type DisputeReceipt,
  type LineAvailability,
  type OrderLine,
  type OrderStatus,
  type OrderView,
  type OutletView,
  type PlaceOrder,
  type ProductView,
  type ReceiptView,
  type StatusChangeView,
  type Temperature,
} from "@shared/domain/types";
import { addDays, depotToday } from "./format.ts";
import type { StoreGateway } from "./gateway.ts";

// Sample data for building the store screens before Ordering, Warehouse and
// Receipt serve them. It enforces what the server will, so a screen that works
// here fails the same way there: a stale version is a conflict, a short line
// rejects the whole placement with per-line availability (D-F), and with the
// warehouse down an order is kept as STOCK_UNKNOWN, never confirmed (D-G).

const OUTLET: OutletView = {
  outletId: "OUT085",
  brandCode: "FRESH",
  districtName: "Kadugannawa",
  depotCode: "KDY",
  dockType: "rear",
  parkingConstraint: "none",
  windowOpen: "05:00:00",
  windowClose: "07:30:00",
  effectiveWindowOpen: null,
  effectiveWindowClose: null,
  vanOnly: false,
};

// Names stand in for product ids: the catalogue is a reconstruction and every
// row is unverified, so the screen labels each one inferred.
const CATALOGUE: [string, Temperature, number, number][] = [
  ["Basmati rice 5 kg", "ambient", 5.2, 60],
  ["Coconut oil 1 L", "ambient", 1.0, 40],
  ["Red lentils 1 kg", "ambient", 1.05, 30],
  ["Ceylon tea 400 g", "ambient", 0.45, 25],
  ["White sugar 1 kg", "ambient", 1.02, 50],
  ["Biscuits 200 g", "ambient", 0.22, 8],
  ["Yoghurt 80 g", "chilled", 0.09, 40],
  ["Fresh milk 1 L", "chilled", 1.04, 30],
  ["Butter 200 g", "chilled", 0.21, 6],
  ["Cheese slices 200 g", "chilled", 0.2, 20],
];

const PRODUCTS: ProductView[] = CATALOGUE.map(([id, temperature, kg]) => ({
  productId: id,
  brandCode: "FRESH",
  unitWeightKg: kg.toFixed(2),
  unitVolumeM3: (kg * 0.0013).toFixed(4),
  temperature,
  verifiedRealSku: false,
  basis: "reconstructed from order totals",
}));
const STOCK = new Map(CATALOGUE.map(([id, , , stock]) => [id, stock]));

const problem = (status: number, title: string, detail: string, extensions: Record<string, unknown> = {}, violations: string[] = []) =>
  new ApiError({ type: "about:blank", title, status, detail, instance: "", violations, extensions });

const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const wait = () => new Promise((r) => setTimeout(r, 250));

export function sampleGateway(): StoreGateway {
  const today = depotToday();
  // Two days out is a Poya day, so an order for it rolls forward (D-I).
  const poya = addDays(today, 2);
  let seq = 92400;
  let warehouseDown = false;
  const orders: OrderView[] = [];
  const history = new Map<string, StatusChangeView[]>();
  const receipts = new Map<string, ReceiptView>();
  const kept: Command[] = [];

  const push = (o: OrderView, to: OrderStatus, reason: string, at = new Date().toISOString()) => {
    const h = history.get(o.orderId) ?? [];
    h.push({ from: h.at(-1)?.to ?? null, to, reason, actorId: null, at });
    history.set(o.orderId, h);
    o.status = to;
  };

  const make = (date: string, temperature: Temperature, lines: OrderLine[], status: OrderStatus): OrderView => {
    const kg = lines.reduce((s, l) => s + Number(PRODUCTS.find((p) => p.productId === l.productId)?.unitWeightKg ?? 1) * l.quantity * 6, 0);
    const o: OrderView = {
      orderId: `ord-${++seq}`,
      orderRef: `ORD00${seq}`,
      outletId: OUTLET.outletId,
      depotCode: OUTLET.depotCode,
      brandCode: OUTLET.brandCode,
      districtName: OUTLET.districtName,
      requestedDate: date,
      deliveryDate: date,
      dateRolled: false,
      temperature,
      itemCount: lines.reduce((s, l) => s + l.quantity, 0),
      weightKg: kg.toFixed(1),
      volumeM3: (kg * 0.004).toFixed(2),
      status: "CONFIRMED",
      warehouseOrderRef: null,
      redeliveryOf: null,
      deferralCount: 0,
      placedAt: new Date().toISOString(),
      lines,
      rowVersion: 1,
    };
    history.set(o.orderId, [{ from: null, to: "CONFIRMED", reason: "placed", actorId: null, at: o.placedAt }]);
    if (status !== "CONFIRMED") push(o, status, status === "DEFERRED" ? "no vehicle capacity on the day" : "planned");
    orders.push(o);
    return o;
  };

  const delivered = (o: OrderView) => {
    push(o, "DELIVERED", "driver recorded delivery");
    receipts.set(o.orderId, {
      receiptId: `rcp-${o.orderId}`,
      orderId: o.orderId,
      deliveryId: `dlv-${o.orderId}`,
      outletId: o.outletId,
      status: "PENDING",
      lines: o.lines.map((l) => ({ productId: l.productId, expectedQuantity: l.quantity, receivedQuantity: null })),
      note: null,
      confirmedBy: null,
      confirmedAt: null,
      rowVersion: 1,
    });
  };

  const L = (...pairs: [number, number][]) => pairs.map(([i, quantity]) => ({ productId: CATALOGUE[i]![0], quantity }));
  const past = make(addDays(today, -3), "ambient", L([0, 10], [3, 6]), "IN_TRANSIT");
  push(past, "DELIVERED", "driver recorded delivery");
  push(past, "RECEIVED", "store confirmed receipt");
  delivered(make(addDays(today, -1), "chilled", L([6, 12], [7, 8]), "IN_TRANSIT"));
  make(today, "ambient", L([0, 12], [1, 8], [2, 12], [4, 6], [5, 4]), "IN_TRANSIT");
  make(today, "chilled", L([6, 10], [7, 6], [9, 4]), "LOADING");
  const deferred = make(addDays(today, 1), "chilled", L([8, 4]), "DEFERRED");
  deferred.deferralCount = 1;

  const find = (orderId: string) => {
    const o = orders.find((x) => x.orderId === orderId);
    if (!o) throw problem(404, "NOT_FOUND", "No such order.");
    return o;
  };
  const guard = (actual: number, expected: number | null) => {
    if (expected !== null && expected !== actual) throw problem(409, "VERSION_CONFLICT", "This record changed since you opened it.");
  };
  const checkStock = (lines: OrderLine[]) => {
    const short: LineAvailability[] = lines
      .filter((l) => l.quantity > (STOCK.get(l.productId) ?? 0))
      .map((l) => ({ productId: l.productId, requested: l.quantity, available: STOCK.get(l.productId) ?? 0 }));
    if (short.length > 0) throw problem(409, "INSUFFICIENT_STOCK", "The warehouse cannot supply every line. Nothing was saved.", { availability: short });
  };
  const calendar = (date: string): CalendarAnswer => {
    const operating = date !== poya;
    return {
      date,
      operating,
      nextOperatingDay: operating ? date : addDays(date, 1),
      known: true,
      day: {},
    };
  };

  const run = (command: Command): unknown => {
    const p = command.payload;
    switch (command.kind) {
      case OrderCommandKind.place: {
        const { requestedDate, lines } = p as PlaceOrder;
        if (!warehouseDown) checkStock(lines);
        const temperature = PRODUCTS.find((x) => x.productId === lines[0]?.productId)?.temperature ?? "ambient";
        const o = make(requestedDate, temperature, lines, "CONFIRMED");
        const day = calendar(requestedDate);
        o.deliveryDate = day.nextOperatingDay;
        o.dateRolled = !day.operating;
        if (warehouseDown) {
          history.set(o.orderId, [{ from: null, to: "STOCK_UNKNOWN", reason: "warehouse unreachable", actorId: null, at: o.placedAt }]);
          o.status = "STOCK_UNKNOWN";
        }
        return clone(o);
      }
      case OrderCommandKind.amend: {
        const { orderId, lines } = p as AmendOrder;
        const o = find(orderId);
        guard(o.rowVersion, command.expectedVersion);
        if (o.status !== "CONFIRMED" && o.status !== "STOCK_UNKNOWN") throw problem(422, "ORDER_CLOSED", "This order is already planned and can no longer change.");
        if (!warehouseDown) checkStock(lines);
        o.lines = lines;
        o.itemCount = lines.reduce((s, l) => s + l.quantity, 0);
        o.rowVersion++;
        push(o, o.status, "amended by the store");
        return clone(o);
      }
      case OrderCommandKind.cancel: {
        const { orderId, reason } = p as CancelOrder;
        const o = find(orderId);
        guard(o.rowVersion, command.expectedVersion);
        o.rowVersion++;
        push(o, "CANCELLED", reason);
        return clone(o);
      }
      case ReceiptCommandKind.confirm:
      case ReceiptCommandKind.confirmPartial:
      case ReceiptCommandKind.dispute: {
        const { orderId } = p as { orderId: string };
        const r = receipts.get(orderId);
        if (!r) throw problem(404, "NOT_FOUND", "No delivery to receive for this order.");
        guard(r.rowVersion, command.expectedVersion);
        const counted = command.kind === ReceiptCommandKind.confirm ? [] : (p as ConfirmPartialReceipt | DisputeReceipt).lines;
        r.lines = r.lines.map((l) => ({ ...l, receivedQuantity: counted.find((c) => c.productId === l.productId)?.receivedQuantity ?? l.expectedQuantity }));
        r.status = command.kind === ReceiptCommandKind.confirm ? "CONFIRMED" : command.kind === ReceiptCommandKind.dispute ? "DISPUTED" : "PARTIAL";
        r.note = command.kind === ReceiptCommandKind.dispute ? (p as DisputeReceipt).reason : ((p as ConfirmPartialReceipt).note ?? null);
        r.confirmedAt = new Date().toISOString();
        r.rowVersion++;
        push(find(orderId), "RECEIVED", r.status === "CONFIRMED" ? "store confirmed receipt" : `store recorded ${r.status.toLowerCase()} receipt`);
        return clone(r);
      }
      default:
        throw problem(400, "UNKNOWN_COMMAND", `${command.kind} is not a store command.`);
    }
  };

  return {
    sample: true,
    outlet: async () => clone(OUTLET),
    orders: async () => (await wait(), clone(orders)),
    history: async (id) => clone(history.get(id) ?? []),
    catalogue: async () => clone(PRODUCTS),
    catalogueStatus: async () => ({
      syncedAt: warehouseDown ? new Date(Date.now() - 40 * 60_000).toISOString() : new Date().toISOString(),
      ageSeconds: warehouseDown ? 2400 : 30,
      stale: warehouseDown,
      productCount: PRODUCTS.length,
      circuitState: warehouseDown ? "open" : "closed",
    }),
    calendar: async (date) => calendar(date),
    pendingReceipts: async () =>
      [...receipts.values()]
        .filter((r) => r.status === "PENDING")
        .map((r) => ({ orderId: r.orderId, deliveryId: r.deliveryId, outletId: r.outletId, deliveredAt: history.get(r.orderId)!.at(-1)!.at })),
    receipt: async (orderId) => {
      const r = receipts.get(orderId);
      if (!r) throw problem(404, "NOT_FOUND", "No delivery to receive for this order.");
      return clone(r);
    },
    send: async (command): Promise<CommandAck> => {
      await wait();
      return { commandId: command.commandId, kind: command.kind, replayed: false, result: run(command) };
    },
    // Kept in memory, not on the device: enough to show the path end to end.
    queue: async (command) => (kept.push(command), { durable: true }),
    flush: async () => {
      let sent = 0;
      let heldForReview = 0;
      for (const command of kept.splice(0)) {
        try {
          run(command);
          sent++;
        } catch {
          heldForReview++;
        }
      }
      return { sent, remaining: 0, heldForReview };
    },
    setWarehouseDown: (down) => {
      warehouseDown = down;
    },
    deliver: (orderId) => {
      const o = orders.find((x) => x.orderId === orderId);
      if (o && (o.status === "IN_TRANSIT" || o.status === "LOADING")) delivered(o);
    },
  };
}
