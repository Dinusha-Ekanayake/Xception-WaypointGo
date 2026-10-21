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
  /** MF-06: about four stops in five take between these minutes; null with too little history. */
  serviceLow?: Decimal | null;
  serviceHigh?: Decimal | null;
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
export type BrandVolumeView = {
  brandCode: string;
  totalM3: Decimal;
  chilledM3: Decimal;
  /** MF-06: about four weeks in five the volume ordered falls between these; null with too few finished weeks. */
  lowM3?: Decimal | null;
  highM3?: Decimal | null;
};

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
export type BrandAccuracyView = {
  brandCode: string;
  forecastM3: number | string;
  orderedM3: number | string;
  /** T2-03: the mean weekly volume of the 8 weeks before; null with too little history. */
  baselineM3?: number | string | null;
};
export type WeekAccuracyView = {
  isoYear: number;
  isoWeek: number;
  modelLabel: string;
  forecastM3: number | string;
  orderedM3: number | string;
  /** Empty when nothing was ordered that week. */
  wape: number | string | null;
  brands: BrandAccuracyView[];
  baselineWape?: number | string | null;
};
export type ForecastAccuracyView = {
  depotCode: string;
  weeks: WeekAccuracyView[];
  wape: number | string | null;
  /** T2-03: the forecast's and the 8-week mean's error on the same brand-weeks (those with a baseline). */
  comparedWape?: number | string | null;
  baselineWape?: number | string | null;
};

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

/** Roadmap B-05: the pilot's business KPIs, in screen order. */
export type KpiKey =
  | "fresh_on_time_pct"
  | "fresh_before_8_pct"
  | "deferrals_per_run"
  | "repeat_deferrals"
  | "disputes_per_100"
  | "write_off_lkr"
  | "calls_per_trip"
  | "adoption_pct";

/** A figure the depot recorded: append only, the newest per week and KPI is current. */
export type KpiEntryView = { value: number; note: string | null; enteredBy: Uuid; enteredAt: IsoInstant };

/** A figure the app counted from its own finished stops, and how many it rests on. */
export type KpiCountedView = { value: number; stops: number };

export type KpiWeekView = {
  /** The Monday. */
  weekStart: IsoDate;
  recorded: Partial<Record<KpiKey, KpiEntryView>>;
  counted: Partial<Record<KpiKey, KpiCountedView>>;
};

/** GET /api/ml/kpis?depot=&weeks=: newest week first. */
export type KpiReportView = { depotCode: string; kpis: KpiKey[]; countedByApp: KpiKey[]; weeks: KpiWeekView[] };

/** ml:RecordKpiWeek. values holds the figures to record; a share is 0 to 100. */
export type RecordKpiWeek = { depotCode: string; weekStart: IsoDate; values: Partial<Record<KpiKey, number>>; note?: string };


/**
 * Roadmap AI-J5: the ready replies' order learned from the last four weeks
 * (GET /api/ml/reply-ranking), mirrored from ReplyRankingQuery.ReplyRankingView.
 * `trained` is false below `minPosts`, and the screen keeps the rule order;
 * `unavailable` when Messaging did not answer.
 */
export type ReplyRankingView = {
  trained: boolean;
  unavailable: boolean;
  posts: number;
  minPosts: number;
  topAcceptance: number;
  scores: Record<string, number>;
  placeEffect: number[];
  from: string;
  to: string;
};

/**
 * Roadmap T1-01: a draft's stops likely to be late (GET /api/ml/plans/{id}/risk-review),
 * mirrored from RiskReviewViews. `estimate` stays true until the trust gates pass.
 */
export type RiskReviewView = {
  reviewId: string;
  planId: string;
  orderId: string;
  choice: "KEEP" | "SWAP";
  reason: string;
  decidedBy: string;
  decidedAt: string;
};

export type RiskStopView = {
  orderId: string;
  tripId: string;
  vehicleId: string;
  sequence: number;
  outletId: string;
  percent: number;
  /** Planned this many minutes before the window closes; negative after. */
  minutesToClose: number;
  stopOfTrip: number;
  review: RiskReviewView | null;
};

export type RiskReviewReport = {
  planId: string;
  enabled: boolean;
  estimate: boolean;
  thresholdPercent: number;
  stops: RiskStopView[];
};

/** ml:RecordRiskReview: keep or swap one stop at risk, with a reason (rule 8). */
export type RecordRiskReview = {
  planId: string;
  orderId: string;
  choice: "KEEP" | "SWAP";
  reason: string;
};

// ---- Issue #269, docs/issues/decision-suggestions/PLAN.md: playbooks and suggested decisions ----

/** GET /api/ml/playbooks: one situation's steps and ready message. */
export type PlaybookView = {
  situation: string;
  steps: string[];
  /** Who the ready message is for and its wording with {blanks}; null when there is nobody useful to write to. */
  message: { to: string; template: string } | null;
};

export type DecisionAction =
  | "SKIP_NEXT_DAY"
  | "SKIP_CANCEL"
  | "ASK_RECEIVE_LATER"
  | "WAIT"
  | "CALL_STORE"
  | "END_TRIP"
  | "TELL_STORE"
  | "NOTED";

/** One suggested decision; `chance` 0 to 1, summing to 1 over what is offered. */
export type SuggestionView = {
  action: DecisionAction;
  chance: number;
  warning: string | null;
  /** Once learned: how often this was taken here, of how often it was shown. */
  chosenTimes?: number | null;
  shownTimes?: number | null;
};

/** GET /api/ml/decision-suggestions?deliveryId=&situation=: advice for one stop, most likely first. */
export type SuggestionsView = {
  situation: string;
  deliveryId: string;
  /** "rules" until learned chances exist. */
  basis: string;
  version: number;
  suggestions: SuggestionView[];
  playbook: PlaybookView;
};

export const SuggestionCommandKind = {
  /** The suggestions shown beside a stop and the one taken, or none. */
  record: "ml:RecordSuggestion",
} as const;

export type RecordSuggestionPayload = { deliveryId: string; situation: string; shown: DecisionAction[]; chosen?: DecisionAction };
