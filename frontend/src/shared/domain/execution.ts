import type { IsoDate, IsoInstant, IsoTime, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.execution.contract.

export type DeliveryOutcome = "PENDING" | "ARRIVED" | "DELIVERED" | "PARTIAL" | "FAILED" | "SKIPPED";

/**
 * One product of the order and what arrived of it. The product id is the
 * warehouse's inferred candidate: never show it as a verified SKU.
 */
export type DeliveryLineView = {
  productId: string;
  orderedUnits: number;
  /** Null until the delivery is recorded product by product. */
  deliveredUnits: number | null;
};

export type RunSheetStopView = {
  deliveryId: Uuid;
  tripId: Uuid;
  sequence: number;
  orderId: Uuid;
  outletId: string;
  itemCount: number;
  /** Takes goods only inside its window: a late arrival is recorded as failed. */
  mallOutlet: boolean;
  plannedArrival: IsoTime;
  windowOpen: IsoTime;
  windowClose: IsoTime;
  /** The planned arrival shifted by the trip's delay, once one is observed. */
  expectedArrival: IsoInstant | null;
  startedAt: IsoInstant | null;
  arrivedAt: IsoInstant | null;
  completedAt: IsoInstant | null;
  waitMinutes: number | null;
  lateMinutes: number | null;
  outcome: DeliveryOutcome;
  /** False on a finished stop means proof is still owed. */
  proofCaptured: boolean;
  rowVersion: number;
  /** The order's products, to record a delivery product by product. */
  lines: DeliveryLineView[];
};

export type RunSheetView = {
  vehicleId: string;
  serviceDate: IsoDate;
  stops: RunSheetStopView[];
};

export type DeliveryRecordView = {
  deliveryId: Uuid;
  orderId: Uuid;
  tripId: Uuid;
  outletId: string;
  vehicleId: string;
  serviceDate: IsoDate;
  outcome: DeliveryOutcome;
  arrivedAt: IsoInstant | null;
  serviceStartedAt: IsoInstant | null;
  completedAt: IsoInstant | null;
  /** Waiting for an early window, kept apart from service time. */
  waitMinutes: number | null;
  /** Against the window close, not the plan. */
  lateMinutes: number | null;
  lateReason: string | null;
  /** Device and server disagreed about the time; usually recorded offline. */
  timingUncertain: boolean;
  deliveredUnits: number | null;
  failureReason: string | null;
  dispositionNote: string | null;
  /** Completed with neither photo nor signature. */
  lowEvidence: boolean;
  proofId: Uuid | null;
  /** The device's clock, for forensics only. */
  clientRecordedAt: IsoInstant | null;
  /** The server's clock, which decides. */
  serverRecordedAt: IsoInstant;
  rowVersion: number;
  lines: DeliveryLineView[];
};

/** The links are signed and stop working at linksExpireAt. */
export type ProofView = {
  proofId: Uuid;
  deliveryId: Uuid;
  recipientName: string | null;
  fallbackReason: string | null;
  lowEvidence: boolean;
  capturedAt: IsoInstant;
  photoUrl: string | null;
  /** Named by the proof but not uploaded yet. */
  photoPending: boolean;
  signatureUrl: string | null;
  signaturePending: boolean;
  linksExpireAt: IsoInstant;
};

export const FailureReasons = [
  "outlet_closed",
  "refused",
  "mall_window_closed",
  "access_blocked",
  "vehicle_breakdown",
  "goods_damaged",
  "other",
] as const;
export type FailureReason = (typeof FailureReasons)[number];

export const VehicleStatuses = ["available", "on_trip", "at_workshop", "fault"] as const;
export type ReportedVehicleStatus = (typeof VehicleStatuses)[number];

export const ExecutionCommandKind = {
  startStop: "delivery:Start",
  recordArrival: "delivery:RecordArrival",
  record: "delivery:Record",
  captureProof: "delivery:CaptureProof",
  reportVehicleStatus: "delivery:ReportVehicleStatus",
  reportFault: "delivery:ReportFault",
} as const;

export type StartStop = { deliveryId: Uuid };
export type RecordArrival = { deliveryId: Uuid; deviceArrivedAt: IsoInstant | null };
export type RecordDelivery = {
  deliveryId: Uuid;
  outcome: "DELIVERED" | "PARTIAL" | "FAILED";
  deliveredUnits: number | null;
  reason: string | null;
  /** What happened to undelivered goods; there is no returns workflow. */
  dispositionNote: string | null;
  /**
   * What arrived of each product, to record the delivery product by product.
   * Name every product of the order. When the products add up to the order's
   * unit count, deliveredUnits follows from them and may be left null.
   */
  lines?: DeliveredLine[];
};
export type DeliveredLine = { productId: string; units: number };
/** With no photo or signature, record why and carry on. */
export type CaptureProof = {
  deliveryId: Uuid;
  photoAttachmentId: Uuid | null;
  signatureAttachmentId: Uuid | null;
  recipientName: string | null;
  fallbackReason: string | null;
};
export type ReportVehicleStatus = { vehicleId: string; status: string; note: string | null };
export type ReportFault = {
  vehicleId: string;
  deliveryId: Uuid | null;
  kind: "vehicle" | "road";
  description: string;
};
