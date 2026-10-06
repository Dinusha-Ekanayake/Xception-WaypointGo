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
  /** Photos of the problem that have reached the server, oldest first. Absent from a server before photos. */
  attachments?: AttachmentView[];
};

/**
 * A photo of a delivery problem. Its bytes are read from
 * `/api/issues/{issueId}/attachments/{attachmentId}/content` by someone who can
 * see the issue; one past its retention is no longer listed.
 */
export type AttachmentView = { attachmentId: Uuid; contentType: string };

/** One change to an issue, with who made it, what they did and why (rule 8). */
export type IssueHistoryView = {
  from: IssueStatus | null;
  to: IssueStatus;
  action: string;
  reason: string;
  actorId: Uuid | null;
  at: IsoInstant;
};

export const IssueCommandKind = {
  raise: "issue:Raise",
  assign: "issue:Assign",
  resolve: "issue:Resolve",
  recordReplacement: "issue:RecordReplacement",
  scheduleRedelivery: "issue:ScheduleRedelivery",
  close: "issue:Close",
  cancel: "issue:Cancel",
  /** FF-03: Operations records what an inquiry found, with any write-off value and photos. */
  recordInquiry: "issue:RecordInquiry",
  /** FF-03: Operations approves or rejects a recorded write-off (P-DG-1). */
  approveWriteOff: "issue:ApproveWriteOff",
} as const;

/** `issue:RecordInquiry`. `writeOffLkr` is rupees as text, for exact decimals; absent when nothing is written off. */
export type RecordInquiry = { issueId: Uuid; outcome: string; writeOffLkr?: string; attachmentIds?: Uuid[] };

/** `issue:ApproveWriteOff`; expectedVersion is the write-off's rowVersion. */
export type ApproveWriteOff = { writeOffId: Uuid; approve: boolean; note: string };

/**
 * A write-off recorded by an inquiry (FF-03), from `GET /api/issues/{id}/write-offs`.
 * `needsHead`: above the depot limit, or a Tech line, so the Head of Distribution decides.
 */
export type WriteOffView = {
  writeOffId: Uuid;
  issueId: Uuid;
  outcome: string;
  valueLkr: string;
  techLine: boolean;
  needsHead: boolean;
  attachmentIds: Uuid[];
  recordedBy: Uuid;
  recordedAt: IsoInstant;
  status: "recorded" | "approved" | "rejected";
  decidedBy: Uuid | null;
  decidedAt: IsoInstant | null;
  decisionNote: string | null;
  rowVersion: number;
};

export type RaiseIssue = {
  type: IssueType;
  severity: IssueSeverity;
  depotCode: string;
  outletId: string | null;
  subjects: SubjectRef[];
  description: string;
  /**
   * Photos uploaded for the problem (`PUT /api/issues/attachments/{id}`, action
   * `issue:AttachPhoto`), by id. One still on the phone links when it arrives.
   */
  attachmentIds?: Uuid[];
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
