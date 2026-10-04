import type { Decimal, IsoDate, IsoInstant, IsoTime, Temperature, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.planning.contract.

export type PlanStatus = "DRAFT" | "PUBLISHED" | "SUPERSEDED" | "CANCELLED";
export type AllocationDecision = "SERVED" | "DEFERRED" | "UNSERVABLE";
/** Who decided where an order stands: the engine, or a dispatcher's hand decision. */
export type AllocationSource = "ENGINE" | "OVERRIDE" | "SWAP" | "KEPT" | "MANUAL_DEFER" | "RESTORED";

/** One rule's verdict. `ruleId` is a RULES-AND-POLICIES identifier such as R-PLN-06. */
export type ConstraintResultView = {
  ruleId: string;
  passed: boolean;
  reason: string;
  slack: Decimal | null;
};

export type StopView = {
  sequence: number;
  orderId: Uuid;
  outletId: string;
  plannedArrival: IsoTime;
  windowOpen: IsoTime;
  windowClose: IsoTime;
  serviceMinutes: Decimal;
};

/** One vehicle, brand, district and temperature class; trip 1 or 2. */
export type TripView = {
  tripId: Uuid;
  vehicleId: string;
  tripNumber: 1 | 2;
  brandCode: string;
  districtName: string;
  temperature: Temperature;
  weightKg: Decimal;
  volumeM3: Decimal;
  plannedMinutes: Decimal;
  plannedDeparture: IsoTime;
  stops: StopView[];
};

export type AllocationView = {
  orderId: Uuid;
  decision: AllocationDecision;
  tripId: Uuid | null;
  /** The rule that decided a deferral; never a generic message. */
  bindingRule: string | null;
  reason: string;
  checks: ConstraintResultView[];
  source: AllocationSource;
  /** Held on its trip, so a regenerate keeps it there. */
  locked: boolean;
  decidedBy: Uuid | null;
  decidedAt: IsoInstant | null;
  /** The outlet's latest day a published plan served it before this plan's day. */
  lastServedOn: IsoDate | null;
};

/** One place an order could take in its open draft, as a `plan:Override` names it. */
export type PlacementView = {
  vehicleId: string;
  tripNumber: 1 | 2;
  /** True when the trip already exists; false when the order would open it. */
  joins: boolean;
  tripId: Uuid | null;
  feasible: boolean;
  /** The first rule that refuses this place; null when it is feasible. */
  bindingRule: string | null;
  reason: string;
  checks: ConstraintResultView[];
};

export type PlanView = {
  planId: Uuid;
  depotCode: string;
  serviceDate: IsoDate;
  planVersion: number;
  status: PlanStatus;
  referenceVersionId: Uuid;
  ruleSetVersionId: Uuid;
  priorityPolicyVersionId: Uuid;
  supersedes: Uuid | null;
  publishedAt: IsoInstant | null;
  /** When this version was written; for a draft, its last edit. */
  savedAt: IsoInstant;
  plannedWithoutPredictor: boolean;
  trips: TripView[];
  allocations: AllocationView[];
  rowVersion: number;
  /** The engine that produced the run, for example "priority-insertion-v1+scarce-replan-v1". */
  engine: string;
  /** What the engine's second pass achieved over its first; null when no second pass ran. */
  improvement: ImprovementView | null;
  /** Planning v2: the cost stage against the rules plan, or why it did not run; absent on older runs. */
  cost?: CostView | null;
};

/**
 * Planning v2 (R-PLN-38, R-PLN-39): the same orders on fewer vehicles and less
 * fuel. The rules plan's figures against the plan kept, or why the search was skipped.
 */
export type CostView = {
  trigger: "DEFERRALS" | "LOW_UTILISATION" | "SKIPPED_SIMPLE_DAY" | "SKIPPED_KEPT_DECISIONS" | "SKIPPED_DISABLED";
  improved: boolean;
  rulesVehicles: number;
  rulesTrips: number;
  rulesLitres: Decimal;
  vehicles: number;
  trips: number;
  litres: Decimal;
  iterations: number;
  stoppedBy: "NONE" | "CLOCK";
};

/** A queued plan generation (R-PLN-41): Generate answers with one, and the screen follows it. */
export type GenerationJobView = {
  jobId: Uuid;
  depotCode: string;
  serviceDate: IsoDate;
  status: "QUEUED" | "RUNNING" | "DONE" | "FAILED";
  attempts: number;
  planId: Uuid | null;
  error: string | null;
  createdAt: IsoInstant;
  startedAt: IsoInstant | null;
  finishedAt: IsoInstant | null;
  /** When done: the draft's id, version and counts, as Generate used to answer. */
  result: { planId: Uuid; planVersion: number; status: string; rowVersion: number; served: number; deferred: number; unservable: number; partial: boolean } | null;
};

/** GET /api/plans/{published|draft}/summary (issue #177): a plan without its allocations. */
export type PlanSummaryView = {
  planId: Uuid;
  depotCode: string;
  serviceDate: IsoDate;
  planVersion: number;
  status: PlanStatus;
  referenceVersionId: Uuid;
  ruleSetVersionId: Uuid;
  priorityPolicyVersionId: Uuid;
  supersedes: Uuid | null;
  publishedAt: IsoInstant | null;
  rowVersion: number;
  served: number;
  deferred: number;
  unservable: number;
  trips: TripSummaryView[];
};

export type TripSummaryView = Omit<TripView, "stops"> & { stopCount: number };

/** One allocation with where it stops; stop fields are null for an order not served. */
export type AllocationLineView = AllocationView & {
  stopSequence: number | null;
  plannedArrival: IsoTime | null;
};

/** GET /api/plans/{planId}/allocations: a keyset page in order id order. */
export type AllocationPageView = {
  planId: Uuid;
  planVersion: number;
  items: AllocationLineView[];
  nextCursor: string | null;
};

/** Issue #92: the reefers planned again as a whole, kept only when better by rank (R-PLN-32). */
export type ImprovementView = {
  firstPassServed: number;
  firstPassDeferred: number;
  served: number;
  deferred: number;
  improved: boolean;
  chilledVolumeGainedM3: Decimal;
  /** NONE, or NODES or CLOCK when the search stopped early and kept the best it had. */
  stoppedBy: "NONE" | "NODES" | "CLOCK";
  /** Chilled orders the reefers could have taken, and how many the search ranked; the rest were placed one at a time. */
  chilledCandidates: number;
  chilledSearched: number;
};

export type DeferralView = {
  orderId: Uuid;
  outletId: string;
  serviceDate: IsoDate;
  ruleId: string;
  reason: string;
  skipCount: number;
};

/** Monday to Sunday, including return legs. */
export type FuelView = {
  vehicleId: string;
  weekStarting: IsoDate;
  quotaLitres: Decimal;
  usedLitres: Decimal;
  remainingLitres: Decimal;
};

export type InterchangePreview = {
  tripId: Uuid;
  currentVehicleId: string;
  replacementVehicleId: string;
  feasible: boolean;
  checks: ConstraintResultView[];
};

export const PlanCommandKind = {
  generate: "plan:Generate",
  override: "plan:Override",
  defer: "plan:Defer",
  publish: "plan:Publish",
  revise: "plan:Revise",
  replan: "plan:Replan",
  swap: "plan:Swap",
  keepDeferred: "plan:KeepDeferred",
  lock: "plan:Lock",
  unlock: "plan:Unlock",
  reorderStops: "plan:ReorderStops",
  contactStore: "plan:ContactStore",
  saveSnapshot: "plan:SaveSnapshot",
  restoreSnapshot: "plan:RestoreSnapshot",
} as const;

/** `keepDecisions` puts back what the dispatcher placed, locked or kept deferred; absent means start over. */
export type GenerateDraft = { depotCode: string; serviceDate: IsoDate; keepDecisions?: boolean };
export type OverrideAllocation = {
  planId: Uuid;
  orderId: Uuid;
  vehicleId: string;
  tripNumber: 1 | 2;
  reason: string;
};
export type DeferOrder = { planId: Uuid; orderId: Uuid; reason: string };
export type PublishPlan = { planId: Uuid };
export type RevisePlan = { planId: Uuid; reason: string };
export type ReplanTrip = {
  planId: Uuid;
  tripId: Uuid;
  replacementVehicleId: string | null;
  reason: string;
};

/** Trade a served order for a deferred one on its trip; whole or not at all. */
/** `orderIds`, optional: the trip's stop order after the swap, every order once, sent with the swap as one change. */
export type SwapOrders = { planId: Uuid; outOrderId: Uuid; inOrderId: Uuid; reason: string; orderIds?: Uuid[] };

/**
 * A proposed stop order for a trip, as the swap window's "AI order" draws it.
 * No module serves one yet: the read is a placeholder in
 * `roles/dispatcher/data/usePlanReads.ts` (useStopOrderProposal), to be wired
 * when Intelligence proposes stop orders. Applying one is the same as a
 * dispatcher's own order: it goes in `SwapOrders.orderIds` and the server judges it.
 */
export type StopOrderProposal = {
  orderIds: Uuid[];
  /** One line on why, for the banner. */
  summary: string;
  /** Minutes shorter than the order the trip would otherwise run in; null when not known. */
  minutesSaved: number | null;
  /** The model and version that proposed it, for the record. */
  source: string;
};
/** Decide that deferred orders stay deferred. */
export type KeepDeferred = { planId: Uuid; orderIds: Uuid[]; reason: string };
/** Hold a served order on its trip (`plan:Lock`) or let it go (`plan:Unlock`). */
export type LockOrder = { planId: Uuid; orderId: Uuid };
/** Fix the order of one trip's stops: every order of the trip, once. */
export type ReorderStops = { planId: Uuid; tripId: Uuid; orderIds: Uuid[]; reason: string };
/** Tell an outlet's store manager about an order the plan did not serve. Changes no plan. */
export type ContactStore = { planId: Uuid; orderId: Uuid; message: string };
export type SaveSnapshot = { planId: Uuid; label?: string };
export type RestoreSnapshot = { planId: Uuid; snapshotId: Uuid };

/** What a swap or a new stop order would leave: the trip as it would run, and every rule's verdict. */
export type TripPreview = {
  vehicleId: string;
  tripNumber: number;
  feasible: boolean;
  stops: StopView[];
  checks: ConstraintResultView[];
};

/** RULES: the plan the rules made, kept beside an optimised draft for comparison (planning v2). */
export type SnapshotKind = "AUTO" | "MANUAL" | "REGENERATED" | "RULES";

/** A saved plan's header. */
export type SnapshotView = {
  snapshotId: Uuid;
  depotCode: string;
  serviceDate: IsoDate;
  number: number;
  label: string;
  kind: SnapshotKind;
  sourcePlanId: Uuid;
  planVersion: number;
  createdBy: Uuid;
  createdAt: IsoInstant;
};

/** A saved plan with the plan itself, read only. */
export type SnapshotDetailView = { snapshot: SnapshotView; plan: PlanView };

export type ChangeKind = "MOVED" | "ADDED" | "DROPPED";

export type PlaceView = {
  decision: AllocationDecision | null;
  vehicleId: string | null;
  tripNumber: number | null;
};

export type OrderChange = {
  orderId: Uuid;
  outletId: string | null;
  kind: ChangeKind;
  before: PlaceView;
  after: PlaceView;
};

export type PlanSideView = {
  label: string;
  planId: Uuid;
  planVersion: number;
  served: number;
  deferred: number;
  unservable: number;
  trips: number;
  vehicles: number;
};

/** GET /api/plans/compare: two plans of one depot and day, side by side. */
export type ComparisonView = {
  a: PlanSideView;
  b: PlanSideView;
  changes: OrderChange[];
  changedTrips: Uuid[];
  removedTrips: Uuid[];
  affectedOutlets: string[];
};
