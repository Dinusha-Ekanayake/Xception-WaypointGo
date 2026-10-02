import { request, requestAll } from "@shared/api/client";
import { send, type Command, type CommandAck } from "@shared/api/commands";
import { drain, enqueue } from "@shared/offline";
import type {
  CalendarAnswer,
  CatalogueStatusView,
  DeliveryDateAnswer,
  OrderView,
  OutletView,
  PendingReceiptView,
  ProductView,
  ReceiptView,
  StatusChangeView,
} from "@shared/domain/types";
import { sampleGateway } from "./fixtures.ts";

// Everything the store screens read and write, behind one seam. The real
// gateway talks to Ordering (#8), Warehouse and Receipt (#13); the sample
// gateway runs the same rules in memory so the screens can be built and judged
// before those modules serve them.
//
// Sample data is opt in with NEXT_PUBLIC_STORE_FIXTURES=1, never ships in a
// production build, and the screen says it is showing sample data.

export type StoreGateway = {
  sample: boolean;
  outlet: (outletId: string, signal: AbortSignal) => Promise<OutletView>;
  orders: (outletId: string, signal: AbortSignal) => Promise<OrderView[]>;
  history: (orderId: string, signal: AbortSignal) => Promise<StatusChangeView[]>;
  catalogue: (brandCode: string, signal: AbortSignal) => Promise<ProductView[]>;
  catalogueStatus: (signal: AbortSignal) => Promise<CatalogueStatusView>;
  calendar: (date: string, signal: AbortSignal) => Promise<CalendarAnswer>;
  /** The server's rule for where a requested day lands: cutoff, closures, calendar. */
  deliveryDate: (outletId: string, requestedDate: string, signal: AbortSignal) => Promise<DeliveryDateAnswer>;
  pendingReceipts: (outletId: string, signal: AbortSignal) => Promise<PendingReceiptView[]>;
  receipt: (orderId: string, signal: AbortSignal) => Promise<ReceiptView>;
  send: (command: Command) => Promise<CommandAck>;
  /** Keep a write on this device until the connection returns (resilient tier). */
  queue: (command: Command) => Promise<{ durable: boolean; reason?: string }>;
  /** Send what was kept. */
  flush: () => Promise<{ sent: number; remaining: number; heldForReview: number }>;
  /** Sample only: take the warehouse down or bring it back, to show D-G. */
  setWarehouseDown?: (down: boolean) => void;
  /** Sample only: move an on-the-way order to delivered, so it can be received. */
  deliver?: (orderId: string) => void;
};

function liveGateway(accountId: string): StoreGateway {
  const q = encodeURIComponent;
  return {
    sample: false,
    // Paths match the controllers in ordering, receipt, warehouse and referencedata.
    outlet: (id, signal) => request(`/api/reference/outlets/${q(id)}`, { signal }),
    // One outlet's orders are a page at a time on the server; the screens group
    // them by day, so they read the whole list.
    orders: (outletId, signal) => requestAll(`/api/orders?outlet=${q(outletId)}`, { signal }, "cursor"),
    history: (orderId, signal) => request(`/api/orders/${q(orderId)}/timeline`, { signal }),
    // The catalogue is a page at a time on the server (cursor param `after`); the
    // picker filters locally, so it reads the whole brand.
    catalogue: (brand, signal) => requestAll(`/api/warehouse/catalogue?brand=${q(brand)}`, { signal }),
    catalogueStatus: (signal) => request(`/api/warehouse/catalogue/status`, { signal }),
    calendar: (date, signal) => request(`/api/reference/calendar/${q(date)}`, { signal }),
    deliveryDate: (outletId, requestedDate, signal) =>
      request(`/api/orders/delivery-date?outlet=${q(outletId)}&requestedDate=${q(requestedDate)}`, { signal }),
    pendingReceipts: (outletId, signal) => request(`/api/receipts/pending?outlet=${q(outletId)}`, { signal }),
    receipt: (orderId, signal) => request(`/api/receipts/${q(orderId)}`, { signal }),
    send: (command) => send(command),
    queue: (command) => enqueue(accountId, "store_manager", command),
    flush: () => drain(accountId),
  };
}

export function sampleDataEnabled(): boolean {
  return process.env.NODE_ENV !== "production" && process.env.NEXT_PUBLIC_STORE_FIXTURES === "1";
}

export function createGateway(accountId: string): StoreGateway {
  return sampleDataEnabled() ? sampleGateway() : liveGateway(accountId);
}
