import type { Decimal, IsoInstant, IsoTime, Temperature, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.loading.contract.

export type SessionStatus = "NOT_STARTED" | "IN_PROGRESS" | "BLOCKED" | "READY" | "COMPLETED";
export type CheckStatus = "PENDING" | "LOADED" | "SHORT" | "MISSING" | "DAMAGED" | "DOES_NOT_FIT";
export type IssueKind = Extract<CheckStatus, "MISSING" | "DAMAGED" | "DOES_NOT_FIT">;

/** In loading order: last stop first. */
export type ManifestLineView = {
  loadSequence: number;
  stopSequence: number;
  orderId: Uuid;
  outletId: string;
  temperature: Temperature;
  itemCount: number;
  weightKg: Decimal;
  volumeM3: Decimal;
  status: CheckStatus;
  loadedUnits: number;
  attempt: number;
};

/** Tied to one plan version; a revision rebuilds it and earlier checks stop counting. */
export type ManifestView = {
  tripId: Uuid;
  planId: Uuid;
  planVersion: number;
  vehicleId: string;
  tripNumber: 1 | 2;
  status: SessionStatus;
  lines: ManifestLineView[];
  rowVersion: number;
};

export type ReadyTripView = {
  tripId: Uuid;
  vehicleId: string;
  tripNumber: 1 | 2;
  plannedDeparture: IsoTime;
  status: SessionStatus;
};

export type ShortfallView = {
  shortfallId: Uuid;
  tripId: Uuid;
  orderId: Uuid;
  kind: IssueKind;
  missingUnits: number;
  reason: string;
  reportedBy: Uuid;
  reportedAt: IsoInstant;
};

export const LoadingCommandKind = {
  start: "loading:Start",
  check: "loading:Check",
  shortfall: "loading:Shortfall",
  requestInterchange: "loading:RequestInterchange",
  release: "loading:Release",
  handBack: "loading:HandBack",
} as const;

export type StartLoading = { tripId: Uuid };
export type RecordCheck = {
  tripId: Uuid;
  orderId: Uuid;
  status: CheckStatus;
  loadedUnits: number;
  reason: string | null;
};
export type FlagShortfall = {
  tripId: Uuid;
  orderId: Uuid;
  kind: IssueKind;
  missingUnits: number;
  reason: string;
  photoAttachmentId: Uuid | null;
};
export type RequestInterchange = { tripId: Uuid; replacementVehicleId: string; reason: string };
export type ReleaseTrip = {
  tripId: Uuid;
  doorsSealed: boolean;
  ordersSecured: boolean;
  driverPresent: boolean;
};
export type HandoverSession = { tripId: Uuid };
