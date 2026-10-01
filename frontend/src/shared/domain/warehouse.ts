import type { Decimal, IsoDate, IsoInstant, Temperature, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.warehouse.contract.

/**
 * A cached catalogue entry. Unit weight and volume are a reconstruction for
 * display only; when `verifiedRealSku` is false the UI must label the product
 * "inferred". There is no stock figure: stock lives at the warehouse.
 */
export type ProductView = {
  productId: string;
  brandCode: string;
  unitWeightKg: Decimal;
  unitVolumeM3: Decimal;
  temperature: Temperature | null;
  verifiedRealSku: boolean;
  basis: string;
};

/** Show as degraded when stale, never as current. */
export type CatalogueStatusView = {
  syncedAt: IsoInstant | null;
  ageSeconds: number;
  stale: boolean;
  productCount: number;
  circuitState: "closed" | "open" | "half_open";
};

/** Per line: requested, and what was available (or locked, for a partial reservation). */
export type LineAvailability = {
  productId: string;
  requested: number;
  available: number;
};

export const WarehouseCommandKind = {
  replayInbound: "warehouse:ReplayInbound",
  discardInbound: "warehouse:DiscardInbound",
  reconcile: "warehouse:Reconcile",
} as const;

export type ReplayInbound = { inboundEventId: Uuid };
export type DiscardInbound = { inboundEventId: Uuid; reason: string };
export type Reconcile = { serviceDate: IsoDate };
