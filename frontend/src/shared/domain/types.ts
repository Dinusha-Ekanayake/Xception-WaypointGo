// The frontend's view of every backend contract, in one import:
//   import type { OrderView, PlanView } from "@shared/domain/types";
// Each module's file mirrors that module's Java contract package; the backend
// is the source of truth.

export type * from "./common.ts";
export type * from "./referencedata.ts";
export type * from "./ordering.ts";
export type * from "./planning.ts";
export type * from "./loading.ts";
export type * from "./execution.ts";
export type * from "./receipt.ts";
export type * from "./issues.ts";
export type * from "./notification.ts";
export type * from "./sync.ts";
export type * from "./warehouse.ts";
export type * from "./intelligence.ts";
export type * from "./events.ts";

export { VehicleCommandKind } from "./referencedata.ts";
export { OrderCommandKind } from "./ordering.ts";
export { PlanCommandKind } from "./planning.ts";
export { LoadingCommandKind } from "./loading.ts";
export { ExecutionCommandKind } from "./execution.ts";
export { ReceiptCommandKind } from "./receipt.ts";
export { IssueCommandKind } from "./issues.ts";
export { NotificationCommandKind } from "./notification.ts";
export { SyncCommandKind } from "./sync.ts";
export { WarehouseCommandKind } from "./warehouse.ts";
export { ModelCommandKind } from "./intelligence.ts";
