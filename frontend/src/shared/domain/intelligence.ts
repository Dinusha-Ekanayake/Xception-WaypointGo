import type { Decimal, IsoDate, IsoInstant, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.intelligence.contract.

/** SHADOW: scored beside the active model of its kind, never answering (issue #12, R-ML-40). */
export type ModelStatus = "REGISTERED" | "ACTIVE" | "SHADOW" | "RETIRED";

/** CANDIDATE: retraining produced it. REPLACED: a promotion replaced it, kept as the challenger (R-ML-42). */
export type ShadowRole = "CANDIDATE" | "REPLACED";

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
  shadowRole: ShadowRole | null;
  shadowedAt: IsoInstant | null;
};

/** One night's comparison of the shadow with the model that answered, on the same labelled stops (R-ML-40). */
export type ShadowReviewView = {
  kind: ModelKind;
  modelLabel: string;
  shadowRole: ShadowRole;
  /** The active model's "name@version", or "deterministic". */
  incumbentLabel: string;
  reviewedOn: IsoDate;
  windowFrom: IsoDate;
  windowTo: IsoDate;
  stops: number;
  shadowBrier: Decimal | null;
  shadowLogLoss: Decimal | null;
  incumbentBrier: Decimal | null;
  incumbentLogLoss: Decimal | null;
  verdict: "BETTER" | "WORSE" | "MIXED" | "NOT_ENOUGH";
  /** rolled_back: the system put the replaced model back (R-ML-42). */
  action: "rolled_back" | null;
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

/** One decision's chance, 0 to 1, summing to 1 over the list it is in (R-ML-34). */
export type ChanceView = { action: string; chance: number; warning: string | null };

/**
 * GET /api/ml/trip-outlook?tripId=: one trip's decision outlook (decision
 * suggestions part 2, R-ML-34). The chance of each decision about the trip and
 * of each outcome for every stop still waiting, from its open reports.
 */
export type TripOutlookView = {
  tripId: string;
  version: number;
  /** BREAKDOWN, ROAD_BLOCKED or RUNNING_LATE when one is on the trip. */
  situation: string | null;
  /** END_TRIP, WAIT, CARRY_ON, most likely first; empty with no trip situation. */
  trip: ChanceView[];
  /** What it was made from; `source` is "report" or "message" (read automatically). */
  signals: {
    messageId: string;
    situation: string;
    source: "report" | "message";
    outletId: string | null;
    /** For a message read automatically (R-ML-35): driver or store_manager. */
    authorRole: string | null;
  }[];
  stops: {
    deliveryId: string;
    outletId: string;
    sequence: number;
    rowVersion: number;
    situation: string;
    /** SKIP_NEXT_DAY, SKIP_CANCEL, ASK_RECEIVE_LATER, DELIVER, most likely first. */
    chances: ChanceView[];
    learned: boolean;
    because: string[];
  }[];
  /** Drivers' and stores' own words are read for it (R-ML-35); false, it stands on reports alone. */
  messagesRead: boolean;
  /** Words that could not be read: waiting over a minute, or given up on (rule 9). */
  unreadMessages: number;
  /** The trip's problem room (R-ML-36): each store's row and the one next step; null with no problem open. */
  room: RoomView | null;
  /** R-ML-37: messages read unsure, or from voice notes heard unclearly, for the dispatcher to confirm. */
  toReview: { messageId: string; guess: string | null; unclear: boolean; source: string; authorRole: string; outletId: string | null }[];
};

/** GET /api/ml/messages-to-check?depot=&date=: a message waiting for a dispatcher to check, for "Needs you" (R-ML-37). */
export type MessageCheckView = { tripId: string; message: TripOutlookView["toReview"][number] };

export type RoomState =
  | "NOT_ASKED" | "MALL_CANNOT_WAIT" | "IN_WINDOW" | "ASKED" | "YES" | "NO" | "NO_ANSWER"
  | "DOCK_ASKED" | "DOCK_YES" | "DOCK_NO" | "SKIPPED" | "DELIVERED";

export type RoomStepKind = "ASK" | "END_TRIP" | "SKIP" | "CALL" | "ASK_DOCK" | "TELL_DRIVER_DOCK" | "WAIT" | "DONE";

export type RoomTarget = { deliveryId: string; outletId: string; receiveAt: string | null; rowVersion: number };

/** R-ML-36: the problem, each store's row and the one next step, chosen by rules from the stores' answers. */
export type RoomView = {
  situation: string;
  rows: { deliveryId: string; outletId: string; sequence: number; state: RoomState; at: string | null; because: string | null }[];
  next: {
    kind: RoomStepKind;
    stops: RoomTarget[];
    /** For the dock: the skipped store whose goods are in the way. */
    about: RoomTarget | null;
    /** The fact the step follows from, in plain words. */
    why: string;
    extraMinutes: number | null;
    extras: number[];
    /** A breakdown also offers "the driver can't go on: end trip". */
    endTripToo: boolean;
  };
  /** R-ML-45: what stores wrote in the chat that reads as the answer to their open question. Absent from older servers. */
  heard?: HeardView[];
};

/** A store's chat words read as its answer, for the dispatcher to record or wave off. Names and numbers masked. */
export type HeardView = { requestId: string; outletId: string; messageId: string; words: string; reads: "YES" | "NO" | "LATER"; at: string };

export const SuggestionCommandKind = {
  /** The suggestions shown beside a stop and the one taken, or none. */
  record: "ml:RecordSuggestion",
  /** A message read automatically taken off the trip's Next step card, with a reason (R-ML-35). */
  dismissSignal: "ml:DismissSignal",
  /** What a message read unsure or unclearly reports, confirmed by the dispatcher (R-ML-37). */
  confirmSignal: "ml:ConfirmSignal",
} as const;

export type RecordSuggestionPayload = { deliveryId: string; situation: string; shown: DecisionAction[]; chosen?: DecisionAction };

// ---- AI health (R-ML-27): where the language models and Jev fail ----------------

/** One use of a model on one depot day. */
export type CallDayView = {
  purpose: string;
  day: IsoDate;
  used: number;
  unfaithful: number;
  failed: number;
  capped: number;
  p50Millis: number;
  p95Millis: number;
};

/** A call that was not used, with the masked facts that were sent. */
export type AiFailureView = {
  at: IsoInstant;
  purpose: string;
  outcome: "unfaithful" | "failed" | "capped";
  model: string;
  promptVersion: string | null;
  millis: number;
  sentFacts: string;
};

/** `source` (R-ML-33): what Jev was asked about; absent from a server older than it. */
export type JevVersionView = { promptVersion: string | null; model: string; answered: number; agreed: number; source?: "issue" | "report" };
export type JevHealthView = { pending: number; answered: number; agreed: number; failed: number; byVersion: JevVersionView[] };
export type SuggestionUseView = { situation: string; decided: number; chosen: number; chosenFirst: number };

export type AiHealthView = {
  days: number;
  calls: CallDayView[];
  failures: AiFailureView[];
  jev: JevHealthView;
  suggestions: SuggestionUseView[];
};
