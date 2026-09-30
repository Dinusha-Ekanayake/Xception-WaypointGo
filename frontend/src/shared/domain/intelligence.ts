import type { Decimal, IsoDate, IsoInstant } from "./common.ts";

// Mirrors com.waypoint.dispatch.intelligence.contract.

export type ModelStatus = "REGISTERED" | "ACTIVE" | "RETIRED";

export type ModelVersionView = {
  name: string;
  version: string;
  kind: "service_time" | "lateness" | "demand";
  status: ModelStatus;
  trainedFrom: IsoDate | null;
  trainedTo: IsoDate | null;
  registeredAt: IsoInstant;
};

/** Chilled volume is zero for Style and Tech. */
export type DemandForecast = {
  depotCode: string;
  brandCode: string;
  isoYear: number;
  isoWeek: number;
  totalVolumeM3: Decimal;
  chilledVolumeM3: Decimal;
  modelVersion: string;
};

export const ModelCommandKind = {
  register: "ml:RegisterModel",
  activate: "ml:ActivateModel",
  retire: "ml:RetireModel",
} as const;

export type RegisterModel = {
  name: string;
  version: string;
  kind: ModelVersionView["kind"];
  trainedFrom: IsoDate;
  trainedTo: IsoDate;
};
export type ActivateModel = { name: string; version: string };
export type RetireModel = { name: string; version: string; reason: string };
