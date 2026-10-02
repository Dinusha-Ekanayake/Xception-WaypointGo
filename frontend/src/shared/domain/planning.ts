import type { Decimal, IsoDate, IsoInstant, IsoTime, Temperature, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.planning.contract.

export type PlanStatus = "DRAFT" | "PUBLISHED" | "SUPERSEDED" | "CANCELLED";
export type AllocationDecision = "SERVED" | "DEFERRED" | "UNSERVABLE";

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
  plannedWithoutPredictor: boolean;
  trips: TripView[];
  allocations: AllocationView[];
  rowVersion: number;
  /** The engine that produced the run, for example "priority-insertion-v1+scarce-replan-v1". */
  engine: string;
  /** What the engine's second pass achieved over its first; null when no second pass ran. */
  improvement: ImprovementView | null;
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
} as const;

export type GenerateDraft = { depotCode: string; serviceDate: IsoDate };
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
