import type { IsoInstant, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.receipt.contract.

export type ReceiptStatus = "PENDING" | "CONFIRMED" | "PARTIAL" | "DISPUTED" | "AUTO_CLOSED";

export type ReceiptLineView = {
  productId: string;
  expectedQuantity: number;
  receivedQuantity: number | null;
};

export type ReceiptView = {
  receiptId: Uuid;
  orderId: Uuid;
  deliveryId: Uuid;
  outletId: string;
  status: ReceiptStatus;
  lines: ReceiptLineView[];
  note: string | null;
  confirmedBy: Uuid | null;
  confirmedAt: IsoInstant | null;
  rowVersion: number;
};

export type PendingReceiptView = {
  orderId: Uuid;
  deliveryId: Uuid;
  outletId: string;
  deliveredAt: IsoInstant;
};

export const ReceiptCommandKind = {
  confirm: "receipt:Confirm",
  confirmPartial: "receipt:ConfirmPartial",
  dispute: "receipt:Dispute",
} as const;

export type ReceivedLine = { productId: string; receivedQuantity: number };

export type ConfirmReceipt = { orderId: Uuid };
export type ConfirmPartialReceipt = { orderId: Uuid; lines: ReceivedLine[]; note: string | null };
export type DisputeReceipt = { orderId: Uuid; reason: string; lines: ReceivedLine[] };
