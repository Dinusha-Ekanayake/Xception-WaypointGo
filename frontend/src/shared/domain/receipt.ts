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
  verifyHandover: "receipt:VerifyHandover",
  reissueHandoverPin: "receipt:ReissueHandoverPin",
} as const;

/** Past its time reads as EXPIRED though the row still says awaiting. */
export type HandoverStatus = "AWAITING" | "CONFIRMED" | "LOCKED" | "EXPIRED";

/**
 * Where the handover PIN stands (R-RCP-09), for the store's screen. The PIN is
 * never here: it is returned once, as the answer to the command that issued it.
 * Evidence of presence, never a gate: nothing waits for it.
 */
export type HandoverView = {
  orderId: Uuid;
  status: HandoverStatus;
  expiresAt: IsoInstant;
  /** Wrong entries the driver may still make. */
  attemptsLeft: number;
  confirmedAt: IsoInstant | null;
  /** The version a reissue is made against. */
  rowVersion: number;
};

/**
 * The store's answer as the driver sees it (issue #21, store-led handover):
 * GET /api/receipts/{orderId}/answer. What the store counted per product, its
 * note, and where the PIN stands. Only the driver of the vehicle on that date
 * reads it, and only once the store answered: before that it is a 404.
 */
export type ReceiptAnswerView = {
  orderId: Uuid;
  status: ReceiptStatus;
  lines: ReceiptLineView[];
  note: string | null;
  /** When the store answered. */
  answeredAt: IsoInstant | null;
  handover: HandoverView;
};

/** What a receipt answer adds to its result when a PIN was issued; absent when none could be. */
export type HandoverIssued = { handoverPin?: string; handoverExpiresAt?: string };

/** What the driver's entry answers. A wrong or late entry is an answer, not an error. */
export type HandoverEntryResult = {
  orderId: Uuid;
  verified: boolean;
  outcome: "VERIFIED" | "ALREADY_CONFIRMED" | "WRONG" | "LOCKED" | "EXPIRED";
  attemptsLeft: number;
  rowVersion: number;
};

export type ReceivedLine = { productId: string; receivedQuantity: number };

export type ConfirmReceipt = { orderId: Uuid };
export type ConfirmPartialReceipt = { orderId: Uuid; lines: ReceivedLine[]; note: string | null };
export type DisputeReceipt = { orderId: Uuid; reason: string; lines: ReceivedLine[] };
export type VerifyHandover = { orderId: Uuid; pin: string };
export type ReissueHandoverPin = { orderId: Uuid };
