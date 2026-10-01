import type { Decimal, IsoDate, IsoInstant, IsoTime, Temperature, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.loading.contract.

export type SessionStatus = "NOT_STARTED" | "IN_PROGRESS" | "BLOCKED" | "READY" | "COMPLETED";
export type CheckStatus = "PENDING" | "LOADED" | "SHORT" | "MISSING" | "DAMAGED" | "DOES_NOT_FIT";
export type IssueKind = Extract<CheckStatus, "MISSING" | "DAMAGED" | "DOES_NOT_FIT">;

/** The loader a trip is locked to, one at a time until release or hand back (R-LOD-11). */
export type HolderView = {
  userId: Uuid;
  name: string;
  employeeCode: string | null;
  since: IsoInstant;
};

/**
 * One item line of an order: a product and its unit count. The product id is
 * an inferred candidate, never a verified SKU (AGENTS.md, External Product Catalogue).
 */
export type ItemView = {
  lineNo: number;
  productId: string;
  units: number;
  status: CheckStatus;
  loadedUnits: number;
  attempt: number;
  checkedAt: IsoInstant | null;
  checkedBy: Uuid | null;
};

/** One order on the trip, in loading order: last stop first. */
export type ManifestLineView = {
  loadSequence: number;
  stopSequence: number;
  orderId: Uuid;
  orderRef: string;
  outletId: string;
  districtName: string;
  windowOpen: IsoTime | null;
  windowClose: IsoTime | null;
  plannedArrival: IsoTime | null;
  temperature: Temperature;
  itemCount: number;
  weightKg: Decimal;
  volumeM3: Decimal;
  /** PENDING while any item is unchecked, LOADED when every item is, otherwise the first flag. */
  status: CheckStatus;
  loadedUnits: number;
  attempt: number;
  items: ItemView[];
};

/** Tied to one plan version; a revision rebuilds it and earlier checks stop counting. */
export type ManifestView = {
  tripId: Uuid;
  planId: Uuid;
  planVersion: number;
  depotCode: string;
  serviceDate: IsoDate;
  vehicleId: string;
  tripNumber: number;
  tripsForVehicle: number;
  brandCode: string;
  districtName: string;
  temperature: Temperature;
  plannedDeparture: IsoTime;
  dockCode: string;
  weightCapKg: Decimal;
  volumeCapM3: Decimal;
  status: SessionStatus;
  holder: HolderView | null;
  releasedAt: IsoInstant | null;
  lines: ManifestLineView[];
  rowVersion: number;
};

export type ReadyTripView = {
  tripId: Uuid;
  vehicleId: string;
  tripNumber: number;
  tripsForVehicle: number;
  plannedDeparture: IsoTime;
  status: SessionStatus;
  brandCode: string;
  districtName: string;
  temperature: Temperature;
  dockCode: string;
  stopCount: number;
  orderCount: number;
  weightKg: Decimal;
  volumeM3: Decimal;
  holder: HolderView | null;
  releasedAt: IsoInstant | null;
  rowVersion: number;
};

export type ShortfallView = {
  shortfallId: Uuid;
  tripId: Uuid;
  orderId: Uuid;
  lineNo: number | null;
  /** SHORT only appears on rows written before the issue choices were narrowed. */
  kind: IssueKind | "SHORT";
  missingUnits: number;
  reason: string;
  reportedBy: Uuid;
  reportedAt: IsoInstant;
  resolvedAt: IsoInstant | null;
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
/** Without lineNo, every unchecked item of the order is ticked. PENDING undoes a tick. */
export type RecordCheck = {
  tripId: Uuid;
  orderId: Uuid;
  lineNo: number | null;
  status: Extract<CheckStatus, "LOADED" | "PENDING">;
  loadedUnits: number;
  reason: string | null;
};
/** Without lineNo, the whole order is flagged. A flagged item is not loaded. */
export type FlagShortfall = {
  tripId: Uuid;
  orderId: Uuid;
  lineNo: number | null;
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
