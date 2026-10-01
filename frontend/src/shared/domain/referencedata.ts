// Mirror of com.waypoint.dispatch.referencedata.contract.ReferenceViews and the
// reads on /api/reference. The backend is the source of truth.
//
// Jackson has no BigDecimal configuration yet, so decimals arrive as JSON
// numbers, not the decimal strings common.ts asks for. They are typed as either
// so the day that is fixed breaks nothing; the UI only formats them for display
// and never makes a capacity decision from them.

import type { Decimal, IsoDate, IsoTime } from "./common.ts";

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
