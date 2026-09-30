import type { IsoDate, IsoInstant, IsoTime, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.execution.contract.

export type DeliveryOutcome = "PENDING" | "ARRIVED" | "DELIVERED" | "PARTIAL" | "FAILED" | "SKIPPED";

export type RunSheetStopView = {
  deliveryId: Uuid;
  tripId: Uuid;
  sequence: number;
  orderId: Uuid;
  outletId: string;
  plannedArrival: IsoTime;
  windowOpen: IsoTime;
  windowClose: IsoTime;
  arrivedAt: IsoInstant | null;
  completedAt: IsoInstant | null;
  outcome: DeliveryOutcome;
  rowVersion: number;
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
  outcome: DeliveryOutcome;
  arrivedAt: IsoInstant | null;
  serviceStartedAt: IsoInstant | null;
  completedAt: IsoInstant | null;
  /** Waiting for an early window, kept apart from service time. */
  waitMinutes: number | null;
  /** Against the window close, not the plan. */
  lateMinutes: number | null;
  deliveredUnits: number | null;
  failureReason: string | null;
  proofId: Uuid | null;
  /** The device's clock, for forensics only. */
  clientRecordedAt: IsoInstant | null;
  /** The server's clock, which decides. */
  serverRecordedAt: IsoInstant;
  rowVersion: number;
};

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
};
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
