// Mirror of com.waypoint.dispatch.referencedata.contract.ReferenceViews and the
// reads on /api/reference. The backend is the source of truth.
//
// Jackson has no BigDecimal configuration yet, so decimals arrive as JSON
// numbers, not the decimal strings common.ts asks for. They are typed as either
// so the day that is fixed breaks nothing; the UI only formats them for display
// and never makes a capacity decision from them.

import type { Decimal, IsoDate, IsoInstant, IsoTime } from "./common.ts";

type WireDecimal = Decimal | number;

export type VehicleView = {
  vehicleId: string;
  /** "truck" or "van", from vehicles.csv. */
  vehicleType: string;
  /** "reefer" or "ambient". A reefer carries chilled or ambient, one class per trip (D-J). */
  temperatureCapability: string;
  weightCapKg: WireDecimal;
  volumeCapM3: WireDecimal;
  kmPerL: WireDecimal;
  weeklyFuelQuotaL: WireDecimal;
  depotCode: string;
  refrigerated: boolean;
  van: boolean;
};

export type OutletView = {
  outletId: string;
  brandCode: string;
  districtName: string;
  depotCode: string;
  dockType: string;
  parkingConstraint: string;
  windowOpen: IsoTime;
  windowClose: IsoTime;
  effectiveWindowOpen: IsoTime | null;
  effectiveWindowClose: IsoTime | null;
  vanOnly: boolean;
  location?: GeoPoint | null;
};

export type CalendarDayView = {
  date: IsoDate;
  operating: boolean;
  holiday: boolean;
  payday: boolean;
  monsoon: boolean;
  festival: string | null;
  festivalRamp: WireDecimal;
  isoYear: number;
  isoWeek: number;
  /** True past the end of the supplied calendar, where policy made the day up (R-CAL-03). */
  generated: boolean;
};

/** GET /api/reference/calendar/{date}. `day` is empty when the date is unknown. */
export type CalendarAnswer = {
  date: IsoDate;
  operating: boolean;
  nextOperatingDay: IsoDate;
  known: boolean;
  day: CalendarDayView | Record<string, never>;
};

export const VehicleCommandKind = {
  setDayStatus: "vehicle:SetDayStatus",
} as const;

export type VehicleDayStatus = "available" | "in_workshop" | "unavailable";

/**
 * vehicle:SetDayStatus. Takes effect on the next planning run, never
 * retroactively (R-FLT-04). The day status has no row version yet, so the
 * command is sent with a null expectedVersion.
 */
export type SetVehicleDayStatus = {
  vehicleId: string;
  status: VehicleDayStatus;
  serviceDate: IsoDate;
  reason: string | null;
};

export type SetVehicleDayStatusResult = {
  vehicleId: string;
  serviceDate: IsoDate;
  status: VehicleDayStatus;
};

/**
 * GET /api/reference/outlets/{outletId}/details: what a store says about
 * itself (R-REF-01). A null window or dock means the published one stands; the
 * outlet read already shows the window and dock in force. `rowVersion` is 0
 * until the store first saves, and is what a change sends as expectedVersion.
 */
export type OutletDetailsView = {
  outletId: string;
  windowOpen: IsoTime | null;
  windowClose: IsoTime | null;
  dockType: string | null;
  contactName: string | null;
  /** May be a person's number: shown to the outlet's own users, never logged. */
  contactPhone: string | null;
  receivingNotes: string | null;
  rowVersion: number;
  updatedAt: IsoInstant | null;
};

export const OutletCommandKind = {
  updateDetails: "reference:UpdateOutletDetails",
} as const;

/**
 * reference:UpdateOutletDetails. The details as they should be: a blank window
 * or dock goes back to the published one, a blank contact clears it. A mall bay
 * cannot be chosen or left, and a mall outlet's window must still overlap the
 * mall's (R-PLN-29). The next plan uses the change; a published plan does not.
 */
export type UpdateOutletDetails = {
  outletId: string;
  /** "hh:mm". */
  windowOpen: string | null;
  windowClose: string | null;
  /** "rear_dock" or "street". */
  dockType: string | null;
  contactName: string | null;
  contactPhone: string | null;
  receivingNotes: string | null;
};

/** Versions published before geo import may have no location. */
export type GeoPoint = {
  latitude: WireDecimal;
  longitude: WireDecimal;
  precision: "exact" | "approximate" | "centroid" | "district";
};

export type DepotView = { depotCode: string; displayName: string; location: GeoPoint | null };
export type DistrictView = { districtName: string; depotCode: string; location: GeoPoint | null };
