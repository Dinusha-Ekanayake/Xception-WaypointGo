import { request, requestAll } from "@shared/api/client";
import { send, type Command, type CommandAck } from "@shared/api/commands";
import { drain, drainUploads, enqueue, readThrough, saveUpload } from "@shared/offline";
import type {
  CalendarAnswer,
  CatalogueStatusView,
  CustodyChainView,
  DateOutlookView,
  DeliveryDateAnswer,
  DeliveryRecordView,
  RideAlongView,
  HandoverView,
  IssueView,
  OrderView,
  OutletDetailsView,
  OutletView,
  PendingReceiptView,
  ProductView,
  ProfileView,
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
  /** Nearby days a trip already serves the district (R-ORD-13); advice only. */
  rideAlong: (outletId: string, requestedDate: string, signal: AbortSignal) => Promise<RideAlongView>;
  /** How likely each day is to be kept (R-ML-07); advice from the depot's totals. */
  outlook: (outletId: string, from: string, to: string, signal: AbortSignal) => Promise<DateOutlookView>;
  pendingReceipts: (outletId: string, signal: AbortSignal) => Promise<PendingReceiptView[]>;
  receipt: (orderId: string, signal: AbortSignal) => Promise<ReceiptView>;
  /** The receipt beside the loading check of the same order, which shows what the loader kept back. */
  custody: (orderId: string, signal: AbortSignal) => Promise<CustodyChainView>;
  /** Keep a photo of a problem on this device, for the order and the receipt being counted. */
  keepPhoto: (photo: { id: string; orderId: string; receiptId: string | null; blob: Blob }) => Promise<{ durable: boolean; reason?: string }>;
  /** Send the photos kept on this device; any left wait for the next pass. */
  sendPhotos: () => Promise<{ sent: number; remaining: number; heldForReview: number }>;
  /** Where the handover PIN stands, never the PIN (R-RCP-09). 404 when none was issued. */
  handover: (orderId: string, signal: AbortSignal) => Promise<HandoverView>;
  /** What is coming to, or has reached, the outlet on a day (Execution, `delivery:Read`). */
  deliveries: (outletId: string, date: string, signal: AbortSignal) => Promise<DeliveryRecordView[]>;
  /** The signed-in person's own account: name, phone and its version (R-IAM-32). */
  profile: (signal: AbortSignal) => Promise<ProfileView>;
  /** What the store says about itself: its own window and dock, and its contacts (R-REF-01). */
  outletDetails: (outletId: string, signal: AbortSignal) => Promise<OutletDetailsView>;
  /** Issues raised about one order: a loading shortfall, damage, a receipt dispute. */
  issuesFor: (orderId: string, signal: AbortSignal) => Promise<IssueView[]>;
  send: (command: Command) => Promise<CommandAck>;
  /** Keep a write on this device until the connection returns (resilient tier). */
  queue: (command: Command) => Promise<{ durable: boolean; reason?: string }>;
  /** Send what was kept. */
  flush: () => Promise<{ sent: number; remaining: number; heldForReview: number }>;
  /** Sample only: take the warehouse down or bring it back, to show D-G. */
  setWarehouseDown?: (down: boolean) => void;
  /** Sample only: move an on-the-way order to delivered, so it can be received. */
  deliver?: (orderId: string) => void;
  /** Sample only: the driver types the PIN on their phone. */
  confirmHandover?: (orderId: string) => void;
};

function liveGateway(accountId: string): StoreGateway {
  const q = encodeURIComponent;
  // Reads are kept on the device so an offline reload still shows the store's
  // orders, deliveries and receipts (issue #201). Not kept: the delivery date
  // and the handover state, which are the server's rule and the PIN's state
  // now, and would mislead from the device.
  const kept = <T>(key: string, read: () => Promise<T>) => readThrough(accountId, `store:${key}`, read);
  return {
    sample: false,
    // Paths match the controllers in ordering, receipt, warehouse and referencedata.
    outlet: (id, signal) => kept(`outlet:${id}`, () => request(`/api/reference/outlets/${q(id)}`, { signal })),
    // One outlet's orders are a page at a time on the server; the screens group
    // them by day, so they read the whole list.
    orders: (outletId, signal) => kept(`orders:${outletId}`, () => requestAll(`/api/orders?outlet=${q(outletId)}`, { signal }, "cursor")),
    history: (orderId, signal) => kept(`history:${orderId}`, () => request(`/api/orders/${q(orderId)}/timeline`, { signal })),
    // The catalogue is a page at a time on the server (cursor param `after`); the
    // picker filters locally, so it reads the whole brand.
    catalogue: (brand, signal) => kept(`catalogue:${brand}`, () => requestAll(`/api/warehouse/catalogue?brand=${q(brand)}`, { signal })),
    catalogueStatus: (signal) => request(`/api/warehouse/catalogue/status`, { signal }),
    calendar: (date, signal) => kept(`calendar:${date}`, () => request(`/api/reference/calendar/${q(date)}`, { signal })),
    deliveryDate: (outletId, requestedDate, signal) =>
      request(`/api/orders/delivery-date?outlet=${q(outletId)}&requestedDate=${q(requestedDate)}`, { signal }),
    rideAlong: (outletId, requestedDate, signal) =>
      request(`/api/orders/ride-along?outlet=${q(outletId)}&requestedDate=${q(requestedDate)}`, { signal }),
    // Not kept: an outlook from yesterday would mislead; offline, the strip says it is unavailable.
    outlook: (outletId, from, to, signal) =>
      request(`/api/ml/outlook?outlet=${q(outletId)}&from=${q(from)}&to=${q(to)}`, { signal }),
    pendingReceipts: (outletId, signal) => kept(`pending:${outletId}`, () => request(`/api/receipts/pending?outlet=${q(outletId)}`, { signal })),
    receipt: (orderId, signal) => kept(`receipt:${orderId}`, () => request(`/api/receipts/${q(orderId)}`, { signal })),
    handover: (orderId, signal) => request(`/api/receipts/${q(orderId)}/handover`, { signal }),
    custody: (orderId, signal) => kept(`custody:${orderId}`, () => request(`/api/receipts/${q(orderId)}/custody`, { signal })),
    keepPhoto: ({ id, orderId, receiptId, blob }) =>
      saveUpload(accountId, {
        id,
        path: `/api/issues/attachments/${q(id)}?order=${q(orderId)}${receiptId ? `&receipt=${q(receiptId)}` : ""}`,
        subject: orderId,
        contentType: blob.type || "image/jpeg",
        blob,
      }),
    sendPhotos: () => drainUploads(accountId),
    deliveries: (outletId, date, signal) =>
      kept(`deliveries:${outletId}:${date}`, () => request(`/api/execution/deliveries?outlet=${q(outletId)}&date=${q(date)}`, { signal })),
    issuesFor: (orderId, signal) => kept(`issues:${orderId}`, () => request(`/api/issues/by-subject?type=order&id=${q(orderId)}`, { signal })),
    profile: (signal) => kept("profile", () => request(`/api/profile`, { signal })),
    outletDetails: (outletId, signal) => kept(`details:${outletId}`, () => request(`/api/reference/outlets/${q(outletId)}/details`, { signal })),
    send: (command) => send(command),
    queue: (command) => enqueue(accountId, "store_manager", command),
    // Kept writes first, then kept photos: the server links a photo to its issue in either order.
    flush: async () => {
      const report = await drain(accountId);
      await drainUploads(accountId).catch(() => undefined);
      return report;
    },
  };
}

export function sampleDataEnabled(): boolean {
  return process.env.NODE_ENV !== "production" && process.env.NEXT_PUBLIC_STORE_FIXTURES === "1";
}

export function createGateway(accountId: string): StoreGateway {
  return sampleDataEnabled() ? sampleGateway() : liveGateway(accountId);
}
