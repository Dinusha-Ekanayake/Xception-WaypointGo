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
