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
  /** MF-08: why it is at risk, strongest first, at most three; empty or absent when nothing stands out. */
  reasons?: LateReasonView[];
};

/** One reason a stop is at risk (R-ML-15): the words come from the code and its numbers. */
export type LateReasonView = { code: "TIGHT_WINDOW" | "LATE_IN_ROUTE" | "OUTLET_HISTORY" | string; value: number; of: number };

/** GET /api/ml/plans/{planId}/predictions */
export type PlanPredictionsView = {
  scoring: PlanScoringView;
  stops: StopPredictionView[];
  /** MF-07: the last day road conditions are known; later days are scored without them. */
  roadConditionsUntil?: IsoDate | null;
};

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

/** Each day's own fleet (workshop and unavailable vehicles carry nothing that day) under its trip limit; counts are the fewest available on any operating day (A-40, T2-01). */
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

/** Roadmap T2-01: what the dispatcher decided about a week the capacity plan shows short. */
export type CapacityAction = "HIRE_VEHICLES" | "MOVE_WORKSHOP" | "ADVISE_STORES" | "ACCEPT_SHORTFALL" | "OTHER";

/** Append only: a change of mind is a second decision, so the trail keeps both. */
export type CapacityDecisionView = {
  decisionId: Uuid;
  depotCode: string;
  isoYear: number;
  isoWeek: number;
  action: CapacityAction;
  vehicles: number | null;
  refrigerated: boolean;
  reason: string;
  decidedBy: Uuid;
  decidedAt: IsoInstant;
};

/** ml:RecordCapacityDecision. vehicles is required for HIRE_VEHICLES; reason is 3 to 500 characters. */
export type RecordCapacityDecision = {
  depotCode: string;
  isoYear: number;
  isoWeek: number;
  action: CapacityAction;
  vehicles?: number;
  refrigerated: boolean;
  reason: string;
};

/** GET /api/ml/forecast/accuracy (roadmap T2-03): the last finished weeks, as forecast before each began. */
export type BrandAccuracyView = { brandCode: string; forecastM3: number | string; orderedM3: number | string };
export type WeekAccuracyView = {
  isoYear: number;
  isoWeek: number;
  modelLabel: string;
  forecastM3: number | string;
  orderedM3: number | string;
  /** Empty when nothing was ordered that week. */
  wape: number | string | null;
  brands: BrandAccuracyView[];
};
export type ForecastAccuracyView = { depotCode: string; weeks: WeekAccuracyView[]; wape: number | string | null };

/** Roadmap MF-04, R-ML-16: one finished week of a model's backtest. */
export type BacktestWeekView = { isoYear: number; isoWeek: number; stops: number; labelled: number; brier: number | string | null };
export type BacktestVerdict = "BETTER" | "WORSE" | "MIXED" | "NOT_ENOUGH" | "BASELINE";
/** How one model did on what it predicted; {@code modelLabel} is name@version, or "deterministic" for the built-in estimate. */
export type ModelBacktestView = {
  modelLabel: string;
  stops: number;
  labelled: number;
  coverage: number | string;
  brier: number | string | null;
  logLoss: number | string | null;
  calibrationSlope: number | string | null;
  calibrationIntercept: number | string | null;
  verdict: BacktestVerdict;
  weeks: BacktestWeekView[];
};
export type BacktestView = { from: string; to: string; minLabels: number; models: ModelBacktestView[] };
