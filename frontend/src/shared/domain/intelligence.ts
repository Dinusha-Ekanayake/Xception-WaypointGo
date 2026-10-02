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
