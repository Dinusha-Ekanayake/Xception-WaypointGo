import type { Decimal, IsoDate, IsoInstant, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.intelligence.contract.

export type ModelStatus = "REGISTERED" | "ACTIVE" | "RETIRED";

/** delivery_risk: service minutes and P(late) per stop. demand_forecast: weekly m3 per depot and brand. */
export type ModelKind = "delivery_risk" | "demand_forecast";

export type ModelVersionView = {
  name: string;
  version: string;
  kind: ModelKind;
  status: ModelStatus;
  /** Validation figures as registered, for example late_logloss. */
  metrics: Record<string, Decimal>;
  trainedFrom: IsoDate | null;
  trainedTo: IsoDate | null;
  registeredAt: IsoInstant;
  activatedAt: IsoInstant | null;
  retiredReason: string | null;
  rowVersion: number;
};

/** Chilled volume is zero for Style and Tech. degraded: the deterministic forecast answered. */
export type DemandForecast = {
  depotCode: string;
  brandCode: string;
  isoYear: number;
  isoWeek: number;
  totalVolumeM3: Decimal;
  chilledVolumeM3: Decimal;
  /** "name@version", or "deterministic". */
  modelVersion: string;
  degraded: boolean;
};

export type ScoringStatus = "PENDING" | "SCORED" | "DEGRADED";

/** How a published plan's stops were scored. Show the reason when no model answered (rule 9). */
export type PlanScoringView = {
  planId: Uuid;
  depotCode: string;
  serviceDate: IsoDate;
  status: ScoringStatus;
  modelLabel: string | null;
  /** "fallback" when the service date had no road conditions. */
  roadConditions: "used" | "fallback" | null;
  reason: string | null;
  scoredAt: IsoInstant | null;
};

export type StopPredictionView = {
  orderId: Uuid;
  tripId: Uuid;
  sequence: number;
  outletId: string;
  serviceMinutes: Decimal;
  lateProbability: Decimal;
  modelLabel: string;
  degraded: boolean;
};

/** GET /api/ml/plans/{planId}/predictions */
export type PlanPredictionsView = { scoring: PlanScoringView; stops: StopPredictionView[] };

/** GET /api/ml/orders/{orderId}/supply-probability (R-RCP-06). */
export type SupplyProbabilityView = {
  orderId: Uuid;
  scheduledDate: IsoDate;
  probability: Decimal;
  basis: "planned" | "deferred" | "deferral_rate" | "cancelled" | "delivered";
  modelLabel: string;
  degraded: boolean;
};

/** How likely a delivery day is to be kept (issue #224, R-ML-07). */
export type OutlookStatus = "ON_TRACK" | "BUSY" | "AT_RISK" | "TOO_EARLY" | "CLOSED";

export type DayOutlookView = { date: IsoDate; status: OutlookStatus; load: Decimal | null; reason: string };

/** GET /api/ml/outlook?outlet=&from=&to= : advice from the depot's totals, never a promise. */
export type DateOutlookView = {
  outletId: string;
  from: IsoDate;
  to: IsoDate;
  days: DayOutlookView[];
  forecast: boolean;
  modelLabel: string;
  degraded: boolean;
};

export const ModelCommandKind = {
  register: "ml:RegisterModel",
  activate: "ml:ActivateModel",
  retire: "ml:RetireModel",
} as const;

export type RegisterModel = {
  name: string;
  version: string;
  kind: ModelKind;
  trainedFrom: IsoDate | null;
  trainedTo: IsoDate | null;
  metrics: Record<string, number>;
};
/** expectedVersion is the model's rowVersion. */
export type ActivateModel = { name: string; version: string };
export type RetireModel = { name: string; version: string; reason: string };

/** GET /api/ml/forecast/overview: NONE until the first run (the job catches up hourly). */
export type OverviewStatus = "READY" | "NONE";

/** One brand's demand in a week; chilled is zero for Style and Tech. */
export type BrandVolumeView = { brandCode: string; totalM3: Decimal; chilledM3: Decimal };

/** The depot's reference fleet under each day's effective trip limit (A-40). An upper bound. */
export type WeekCapacityView = {
  vehicles: number;
  refrigeratedVehicles: number;
  fleetM3: Decimal;
  refrigeratedM3: Decimal;
};

export type ForecastWeekView = {
  isoYear: number;
  isoWeek: number;
  weekStart: IsoDate;
  operatingDays: number;
  holidayDays: number;
  paydays: number;
  /** The festival the week ramps towards. */
  festival: string | null;
  /** Days past the supplied calendar (R-CAL-03). */
  generatedDays: number;
  brands: BrandVolumeView[];
  totalM3: Decimal;
  chilledM3: Decimal;
  capacity: WeekCapacityView;
};

export type ForecastOverviewView = {
  depotCode: string;
  status: OverviewStatus;
  /** "name@version", "deterministic", "mixed", or null with no run. */
  modelLabel: string | null;
  degraded: boolean;
  generatedAt: IsoInstant | null;
  weeks: ForecastWeekView[];
  /** When the forecast job next runs, as things stand (P-29): hourly in depot time, only when a run is owed. */
  nextRunAt: IsoInstant;
};
