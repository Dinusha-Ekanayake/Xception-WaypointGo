import type { IsoDate, IsoInstant, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.issues.contract.

export type IssueType =
  | "LOADING_SHORTFALL"
  | "DAMAGED_GOODS"
  | "FAILED_DELIVERY"
  | "LATE_DELIVERY"
  | "VEHICLE_FAULT"
  | "ROAD_DISRUPTION"
  | "RECEIPT_DISPUTE"
  | "STOCK_DISCREPANCY"
  | "OTHER";

export type IssueSeverity = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";
export type IssueStatus = "OPEN" | "ASSIGNED" | "RESOLVED" | "CLOSED" | "CANCELLED";

export type SubjectRef = {
  type: "order" | "trip" | "delivery" | "receipt" | "shortfall" | "vehicle";
  id: string;
};

export type IssueView = {
  issueId: Uuid;
  type: IssueType;
  severity: IssueSeverity;
  status: IssueStatus;
  depotCode: string;
  outletId: string | null;
  subjects: SubjectRef[];
  description: string;
  assignee: Uuid | null;
  resolutionAction: string | null;
  resolutionNote: string | null;
  raisedBy: Uuid;
  raisedAt: IsoInstant;
  resolvedAt: IsoInstant | null;
  rowVersion: number;
};

export const IssueCommandKind = {
  raise: "issue:Raise",
  assign: "issue:Assign",
  resolve: "issue:Resolve",
  recordReplacement: "issue:RecordReplacement",
  scheduleRedelivery: "issue:ScheduleRedelivery",
  close: "issue:Close",
  cancel: "issue:Cancel",
} as const;

export type RaiseIssue = {
  type: IssueType;
  severity: IssueSeverity;
  depotCode: string;
  outletId: string | null;
  subjects: SubjectRef[];
  description: string;
};
export type AssignIssue = { issueId: Uuid; assigneeUserId: Uuid };
export type ResolveIssue = { issueId: Uuid; action: string; note: string };
export type RecordReplacement = { issueId: Uuid; tripId: Uuid; orderId: Uuid; note: string };
export type ScheduleRedelivery = {
  issueId: Uuid;
  orderId: Uuid;
  requestedDate: IsoDate;
  note: string;
};
export type CloseIssue = { issueId: Uuid };
export type CancelIssue = { issueId: Uuid; reason: string };
