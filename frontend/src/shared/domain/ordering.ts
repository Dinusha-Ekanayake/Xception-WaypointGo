import type { Decimal, IsoDate, IsoInstant, Temperature, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.ordering.contract.

export type OrderStatus =
  | "STOCK_UNKNOWN"
  | "PARTIALLY_RESERVED"
  | "CONFIRMED"
  | "ALLOCATED"
  | "DEFERRED"
  | "UNSERVABLE"
  | "LOADING"
  | "IN_TRANSIT"
  | "DELIVERED"
  | "PARTIALLY_DELIVERED"
  | "FAILED"
  | "RECEIVED"
  | "UNCONFIRMED"
  | "CANCELLED";

/** Descriptive only: never summed to decide whether a load fits. */
export type OrderLineView = {
  productId: string;
  quantity: number;
};

export type OrderView = {
  orderId: Uuid;
  orderRef: string;
  outletId: string;
  depotCode: string;
  brandCode: string;
  districtName: string;
  requestedDate: IsoDate;
  /** After rolling past non-operating days; show why when `dateRolled`. */
  deliveryDate: IsoDate;
  dateRolled: boolean;
  temperature: Temperature;
  itemCount: number;
  /** Authoritative, returned by the warehouse at placement. */
  weightKg: Decimal;
  volumeM3: Decimal;
  status: OrderStatus;
  warehouseOrderRef: string | null;
  redeliveryOf: Uuid | null;
  deferralCount: number;
  placedAt: IsoInstant;
  lines: OrderLineView[];
  rowVersion: number;
};

/**
 * What `order:Place` answers with: the order as just created, not the full view.
 * Totals and temperature are the warehouse's, so they are null while stock is
 * unchecked (STOCK_UNKNOWN) and the screen says so rather than showing zero.
 */
export type PlacedOrder = {
  orderId: Uuid;
  orderRef: string;
  status: OrderStatus;
  requestedDate: IsoDate;
  deliveryDate: IsoDate;
  dateRolled: boolean;
  rolledBecause: string[];
  rowVersion: number;
  temperature: Temperature | null;
  itemCount: number | null;
  weightKg: Decimal | null;
  volumeM3: Decimal | null;
  lines: OrderLineView[];
  /** Why stock was not checked, when it was not. */
  degraded?: string;
  shortfall?: Shortfall;
};

/**
 * `GET /api/orders/delivery-date`: the day an order asked for `requested` would
 * arrive, and why it moved (`cutoff`, `closed`, `non_operating`), so the store
 * sees the roll before it sends (D-I).
 */
export type DeliveryDateAnswer = {
  requested: IsoDate;
  delivery: IsoDate;
  reasons: string[];
};

export type StatusChangeView = {
  from: OrderStatus | null;
  to: OrderStatus;
  reason: string;
  actorId: Uuid | null;
  at: IsoInstant;
};

export const OrderCommandKind = {
  place: "order:Place",
  amend: "order:Amend",
  cancel: "order:Cancel",
  closeForDay: "order:CloseForDay",
  acceptShortfall: "order:AcceptShortfall",
} as const;

export type OrderLine = { productId: string; quantity: number };

export type PlaceOrder = { outletId: string; requestedDate: IsoDate; lines: OrderLine[] };
export type AmendOrder = { orderId: Uuid; lines: OrderLine[] };
export type CancelOrder = { orderId: Uuid; reason: string };
/** Accept what a partial reservation locked; refusing it is CancelOrder. */
export type AcceptShortfall = { orderId: Uuid };

/** In the PlaceOrder response when the warehouse could fill only part of the order. */
export type Shortfall = {
  warehouseOrderRef: string;
  expiresAt: IsoInstant;
  lines: { productId: string; requested: number; reserved: number }[];
  otherWarehouse: { productId: string; warehouse: string; available: number }[];
};
export type CloseOrdersForDay = { depotCode: string; serviceDate: IsoDate };
