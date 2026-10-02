import type { Page, Route } from "@playwright/test";
import type { DeliveryRecordView } from "../../src/shared/domain/execution.ts";
import type { IssueView } from "../../src/shared/domain/issues.ts";
import type { OrderView } from "../../src/shared/domain/ordering.ts";
import type { ReceiptView } from "../../src/shared/domain/receipt.ts";

// Responses in the shape the backend serves (ordering, execution, receipt and
// issues contracts), so these mocked browser tests exercise the same contract
// the live app reads. Dates are today in Colombo, as the screens compute them.

export const SESSION = { userId: "store-user", displayName: "Nuwan Perera", roles: ["store_manager"], scope: ["outlet:OUT085"], operator: null };

export const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(new Date());

export const OUTLET = {
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

export const PRODUCTS = [
  ["Basmati rice 5 kg", "ambient"],
  ["Coconut oil 1 L", "ambient"],
  ["Fresh milk 1 L", "chilled"],
].map(([productId, temperature]) => ({
  productId,
  brandCode: "FRESH",
  unitWeightKg: "1.00",
  unitVolumeM3: "0.0013",
  temperature,
  verifiedRealSku: false,
  basis: "reconstructed from order totals",
}));

export const ORDER: OrderView = {
  orderId: "order-1",
  orderRef: "ORD0092336",
  outletId: "OUT085",
  depotCode: "KDY",
  brandCode: "FRESH",
  districtName: "Kadugannawa",
  requestedDate: today,
  deliveryDate: today,
  dateRolled: false,
  temperature: "chilled",
  itemCount: 5,
  weightKg: "12.0",
  volumeM3: "0.05",
  status: "DELIVERED",
  warehouseOrderRef: null,
  redeliveryOf: null,
  deferralCount: 0,
  placedAt: "2026-10-01T06:40:00Z",
  lines: [
    { productId: "Fresh milk 1 L", quantity: 3 },
    { productId: "Butter 200 g", quantity: 2 },
  ],
  rowVersion: 3,
};

export const DELIVERY: DeliveryRecordView = {
  deliveryId: "dlv-1",
  orderId: ORDER.orderId,
  tripId: "trip-1",
  outletId: "OUT085",
  vehicleId: "VEH043",
  serviceDate: today,
  outcome: "ARRIVED",
  arrivedAt: `${today}T00:12:00Z`,
  serviceStartedAt: `${today}T00:12:00Z`,
  completedAt: null,
  waitMinutes: 0,
  lateMinutes: 0,
  lateReason: null,
  timingUncertain: false,
  deliveredUnits: null,
  failureReason: null,
  dispositionNote: null,
  lowEvidence: false,
  proofId: null,
  clientRecordedAt: null,
  serverRecordedAt: `${today}T00:12:00Z`,
  rowVersion: 1,
  lines: ORDER.lines.map((l) => ({ productId: l.productId, orderedUnits: l.quantity, deliveredUnits: null })),
  stopSequence: 3,
  tripStopCount: 7,
  plannedArrival: "05:44:00",
  expectedArrival: null,
  releasedAt: `${today}T00:00:00Z`,
  startedAt: null,
  driver: { displayName: "Rashmika Dilshan", employeeCode: "DRV-00021" },
};

export const RECEIPT: ReceiptView = {
  receiptId: "rcp-1",
  orderId: ORDER.orderId,
  deliveryId: DELIVERY.deliveryId,
  outletId: "OUT085",
  status: "PENDING",
  lines: ORDER.lines.map((l) => ({ productId: l.productId, expectedQuantity: l.quantity, receivedQuantity: null })),
  note: null,
  confirmedBy: null,
  confirmedAt: null,
  rowVersion: 1,
  tripId: "trip-1",
  depotCode: "KDY",
  deliveredAt: `${today}T00:12:00Z`,
  autoClosesAt: `${today}T23:59:00Z`,
  late: false,
};

export const SHORTFALL: IssueView = {
  issueId: "iss-1",
  type: "LOADING_SHORTFALL",
  severity: "MEDIUM",
  status: "OPEN",
  depotCode: "KDY",
  outletId: "OUT085",
  subjects: [{ type: "order", id: ORDER.orderId }],
  description: "1 package short at loading. It comes with the next delivery.",
  assignee: null,
  resolutionAction: null,
  resolutionNote: null,
  raisedBy: "loader-1",
  raisedAt: `${today}T00:05:00Z`,
  resolvedAt: null,
  rowVersion: 1,
};

/** Commands the page sent, in order, for a test to assert on. */
export type Sent = { kind: string; payload: unknown; expectedVersion: number | null }[];

/** Where the handover PIN stands; null until the store answers the receipt. */
export type Handover = { status: "AWAITING" | "CONFIRMED" | "LOCKED" | "EXPIRED"; pin: string; rowVersion: number; confirmedAt: string | null };

/** Routes every call the store makes; a delivered order is waiting to be received. */
export async function mockStore(
  page: Page,
  options: { answered?: Handover | null; loadingShort?: boolean } = {},
): Promise<{ sent: Sent; handover: { current: Handover | null }; uploads: string[] }> {
  const sent: Sent = [];
  /** Photo uploads, as the paths they were PUT to. */
  const uploads: string[] = [];
  const handover: { current: Handover | null } = { current: options.answered ?? null };
  // A receipt the store answered earlier: the order is received and nothing waits to be counted.
  const answered = options.answered !== undefined;
  const order = answered ? { ...ORDER, status: "RECEIVED" as const } : ORDER;
  const receipt = answered ? { ...RECEIPT, status: "CONFIRMED" as const, confirmedAt: `${today}T00:20:00Z`, confirmedBy: "store-user" } : RECEIPT;
  const expires = () => new Date(Date.now() + 15 * 60_000).toISOString();
  await page.route("**/api/**", async (route: Route) => {
    const url = new URL(route.request().url());
    const { pathname } = url;
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/api/session") return json(SESSION);
    if (pathname === "/api/reference/outlets/OUT085") return json(OUTLET);
    if (pathname === "/api/orders") return json({ items: [order], nextCursor: null });
    if (pathname === `/api/orders/${ORDER.orderId}/timeline`) {
      return json([
        { from: null, to: "CONFIRMED", reason: "placed", actorId: null, at: ORDER.placedAt },
        { from: "CONFIRMED", to: "DELIVERED", reason: "driver recorded delivery", actorId: null, at: `${today}T00:12:00Z` },
      ]);
    }
    if (pathname === "/api/receipts/pending") {
      return json(answered ? [] : [{ orderId: ORDER.orderId, deliveryId: DELIVERY.deliveryId, outletId: "OUT085", deliveredAt: DELIVERY.arrivedAt }]);
    }
    if (pathname === `/api/receipts/${ORDER.orderId}`) return json(receipt);
    if (pathname === `/api/receipts/${ORDER.orderId}/custody`) {
      // The loading check of the order: with loadingShort, one butter was kept back at the dock.
      const item = (lineNo: number, productId: string, units: number, short: boolean) => ({
        lineNo, productId, units, status: short ? "SHORT" : "LOADED", loadedUnits: short ? units - 1 : units, attempt: 1, checkedAt: null, checkedBy: null,
      });
      return json({
        orderId: ORDER.orderId,
        receipt,
        delivery: { deliveryId: DELIVERY.deliveryId, tripId: "trip-1", completedAt: DELIVERY.arrivedAt, deliveredUnits: 5, recordedBy: null },
        loadingCheck: options.loadingShort
          ? {
              loadSequence: 0, stopSequence: 3, orderId: ORDER.orderId, orderRef: ORDER.orderRef, outletId: "OUT085", districtName: "Kadugannawa",
              windowOpen: "05:00:00", windowClose: "07:30:00", plannedArrival: "05:44:00", temperature: "chilled", itemCount: 5, weightKg: "12.0",
              volumeM3: "0.05", status: "SHORT", loadedUnits: 4, attempt: 1,
              items: [item(1, "Fresh milk 1 L", 3, false), item(2, "Butter 200 g", 2, true)],
            }
          : null,
        proof: null,
        unavailable: options.loadingShort ? [] : ["loading check: no matching order in the trip manifest"],
      });
    }
    if (route.request().method() === "PUT" && pathname.startsWith("/api/issues/attachments/")) {
      uploads.push(pathname + url.search);
      return json({ attachmentId: pathname.split("/").pop(), sha256: "x", sizeBytes: 100, contentType: "image/jpeg", stored: true });
    }
    if (pathname === `/api/receipts/${ORDER.orderId}/handover`) {
      const h = handover.current;
      if (!h) return json({ type: "about:blank", title: "NOT_FOUND", status: 404, detail: "No handover PIN", code: "NOT_FOUND", violations: [] }, 404);
      return json({ orderId: ORDER.orderId, status: h.status, expiresAt: expires(), attemptsLeft: h.status === "LOCKED" ? 0 : 5, confirmedAt: h.confirmedAt, rowVersion: h.rowVersion });
    }
    if (pathname === "/api/execution/deliveries") return json([DELIVERY]);
    if (pathname === "/api/issues/by-subject") return json([SHORTFALL]);
    if (pathname === "/api/warehouse/catalogue/status") return json({ syncedAt: new Date().toISOString(), ageSeconds: 5, stale: false, productCount: 3, circuitState: "closed" });
    if (pathname === "/api/warehouse/catalogue") return json({ items: PRODUCTS, nextCursor: null });
    if (pathname.startsWith("/api/reference/calendar/")) return json({ date: pathname.split("/").pop(), operating: true, nextOperatingDay: pathname.split("/").pop(), known: true, day: {} });
    if (pathname === "/api/orders/delivery-date") {
      const requested = url.searchParams.get("requestedDate");
      return json({ requested, delivery: requested, reasons: [] });
    }
    if (pathname === "/api/commands" && route.request().method() === "POST") {
      const body = route.request().postDataJSON() as { commandId: string; kind: string; payload: unknown; expectedVersion: number | null };
      sent.push({ kind: body.kind, payload: body.payload, expectedVersion: body.expectedVersion });
      // order:Place answers with the order as created (PlacedOrder); the others need only a row version.
      const result =
        body.kind === "order:Place"
          ? {
              orderId: "order-new",
              orderRef: "ORD0092412",
              status: "CONFIRMED",
              requestedDate: (body.payload as { requestedDate: string }).requestedDate,
              deliveryDate: (body.payload as { requestedDate: string }).requestedDate,
              dateRolled: false,
              rolledBecause: [],
              rowVersion: 1,
              temperature: "ambient",
              itemCount: 2,
              weightKg: "10.4",
              volumeM3: "0.04",
              lines: [],
            }
          : body.kind.startsWith("receipt:Confirm") || body.kind === "receipt:Dispute"
            ? (() => {
                // The answer carries the one-time PIN (R-RCP-09).
                handover.current = { status: "AWAITING", pin: "4827", rowVersion: 1, confirmedAt: null };
                return { orderId: ORDER.orderId, receiptId: RECEIPT.receiptId, status: "CONFIRMED", rowVersion: 2, handoverPin: "4827", handoverExpiresAt: expires() };
              })()
            : body.kind === "receipt:ReissueHandoverPin"
              ? (() => {
                  handover.current = { status: "AWAITING", pin: "0519", rowVersion: (handover.current?.rowVersion ?? 0) + 1, confirmedAt: null };
                  return { orderId: ORDER.orderId, handoverPin: "0519", handoverExpiresAt: expires(), rowVersion: handover.current.rowVersion };
                })()
              : { orderId: ORDER.orderId, rowVersion: 2 };
      return json({ commandId: body.commandId, kind: body.kind, replayed: false, result });
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  return { sent, handover, uploads };
}

/** A real 2x2 PNG, for a photo taken in a test. */
export const PNG = Buffer.from(
  "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8z8DAwMDAxMDAwMDAAAANHQEDasKb6QAAAABJRU5ErkJggg==",
  "base64",
);
