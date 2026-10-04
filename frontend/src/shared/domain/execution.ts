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
  /** The units handed over, once recorded; a partial delivery is a stop total, not per line. */
  deliveredUnits: number | null;
  /** False on a finished stop means proof is still owed. */
  proofCaptured: boolean;
  rowVersion: number;
  /** The order's products, to record a delivery product by product. */
  lines: DeliveryLineView[];
  /** Why the driver moved on before the store answered, when they did (issue #21). */
  storeAnswerWaived: StoreAnswerWaiverReason | null;
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
  /** This stop's place on the trip. */
  stopSequence: number;
  /** How many stops the trip has; null on a record released before it was kept. */
  tripStopCount: number | null;
  /** The plan's time at this stop. */
  plannedArrival: IsoTime;
  /** The planned arrival shifted by the trip's delay, once one is observed (R-EXE-15). */
  expectedArrival: IsoInstant | null;
  /** When the trip left the dock. */
  releasedAt: IsoInstant;
  startedAt: IsoInstant | null;
  /** Filled in on the outlet and single-delivery reads; null when nobody is assigned or Identity could not answer. */
  driver: DriverView | null;
};

/** A name and the badge shown on screen, never an email or a phone number. */
export type DriverView = {
  displayName: string;
  employeeCode: string | null;
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
  recordPositions: "delivery:RecordPositions",
  /** R-EXE-24: the driver is at the depot with the vehicle, ready for it to be loaded. */
  arriveAtDepot: "delivery:ArriveAtDepot",
  /** Move on from a handed-over stop before the store answered, with the reason (issue #21, R-EXE-26). */
  leaveWithoutStoreAnswer: "delivery:LeaveWithoutStoreAnswer",
} as const;

export type ArriveAtDepot = { vehicleId: string };

/** Why the driver moved on before the store answered: the closed list the server keeps. */
export type StoreAnswerWaiverReason = "store_absent" | "no_signal" | "disagree";

export type LeaveWithoutStoreAnswer = { deliveryId: Uuid; reason: StoreAnswerWaiverReason };

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

/**
 * A vehicle's last good GPS fix (R-EXE-19). `offline` means its trip is in
 * progress and no good fix arrived for ten minutes: the point is where it was
 * last seen, never an estimate.
 */
export type VehiclePositionView = {
  vehicleId: string;
  tripId: Uuid | null;
  latitude: number | string;
  longitude: number | string;
  headingDeg: number | string | null;
  accuracyM: number | string | null;
  recordedAt: IsoInstant;
  offline: boolean;
};

/** One point of a trip's recorded trail, oldest first. Low quality is drawn faded. */
export type TrailPointView = {
  recordedAt: IsoInstant;
  latitude: number | string;
  longitude: number | string;
  lowQuality: boolean;
};

/** One phone observation, at most six decimals on the coordinates (R-EXE-18). */
export type PositionPoint = {
  recordedAt: IsoInstant;
  latitude: number;
  longitude: number;
  accuracyM?: number;
  headingDeg?: number;
  speedKmh?: number;
};

/** `delivery:RecordPositions`: 1-100 points, oldest first. Append only, so no expectedVersion. */
export type RecordPositionsPayload = { vehicleId: string; tripId?: Uuid; points: PositionPoint[] };
