// The frontend's view of every backend contract, in one import:
//   import type { OrderView, PlanView } from "@shared/domain/types";
// Each module's file mirrors that module's Java contract package; the backend
// is the source of truth.

export type * from "./common.ts";
export type * from "./identity.ts";
export type * from "./referencedata.ts";
export type * from "./ordering.ts";
export type * from "./planning.ts";
export type * from "./loading.ts";
export type * from "./execution.ts";
export type * from "./receipt.ts";
export type * from "./demo.ts";
export type * from "./issues.ts";
export type * from "./notification.ts";
export type * from "./sync.ts";
export type * from "./warehouse.ts";
export type * from "./intelligence.ts";
export type * from "./messaging.ts";
export type * from "./events.ts";

export { OutletCommandKind, VehicleCommandKind } from "./referencedata.ts";
export { IdentityCommandKind, McpCommandKind, McpSwitchPolicy, PolicyCommandKind } from "./identity.ts";
export { ORDER_CUTOFF, OrderCommandKind } from "./ordering.ts";
export { PlanCommandKind } from "./planning.ts";
export { LoadingCommandKind } from "./loading.ts";
export { ExecutionCommandKind, FailureReasons, VehicleStatuses } from "./execution.ts";
export { ReceiptCommandKind } from "./receipt.ts";
export { IssueCommandKind } from "./issues.ts";
export { NotificationCommandKind } from "./notification.ts";
export { SyncCommandKind } from "./sync.ts";
export { MessageCommandKind } from "./messaging.ts";
export { WarehouseCommandKind } from "./warehouse.ts";
export { ModelCommandKind } from "./intelligence.ts";
export { DemoCommandKind } from "./demo.ts";
