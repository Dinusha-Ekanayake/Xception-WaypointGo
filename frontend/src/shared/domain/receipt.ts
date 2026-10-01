import type { IsoInstant, Uuid } from "./common.ts";
import type { DeliveryRecordView } from "./execution.ts";
import type { ManifestLineView } from "./loading.ts";

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
  /** The trip that carried the order, linking the receipt to the loading check. */
  tripId: Uuid | null;
  depotCode: string;
  deliveredAt: IsoInstant;
  /** When silence becomes AUTO_CLOSED (R-RCP-05). */
  autoClosesAt: IsoInstant;
  /** The store answered after auto-close; still accepted (RCP-08). */
  late: boolean;
};

export type PendingReceiptView = {
  orderId: Uuid;
  deliveryId: Uuid;
  outletId: string;
  deliveredAt: IsoInstant;
};

/** What Receipt recorded from delivery.completed. */
export type DeliveryFacts = {
  deliveryId: Uuid;
  tripId: Uuid | null;
  completedAt: IsoInstant;
  deliveredUnits: number | null;
  recordedBy: Uuid | null;
};

/**
 * The loading check, the proof and the receipt side by side (R-RCP-08). A
 * neighbour not deployed yet is named in `unavailable`, never shown as empty.
 */
export type CustodyChainView = {
  orderId: Uuid;
  receipt: ReceiptView;
  delivery: DeliveryFacts;
  loadingCheck: ManifestLineView | null;
  proof: DeliveryRecordView | null;
  unavailable: string[];
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
