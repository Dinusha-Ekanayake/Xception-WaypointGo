// Mirror of com.waypoint.dispatch.messaging.contract (issue #136): a thread per
// trip, its messages, reports and voice notes. The backend is the source of truth.

import type { IsoDate, IsoInstant, Uuid } from "./common.ts";

export const MessageCommandKind = {
  post: "message:Post",
} as const;

/** Who a message is for, besides the dispatcher, who reads everything (R-MSG-01). */
export type MessageAudience = "dispatch" | "driver" | "loader" | "outlet" | "all";

/** How someone belongs to a thread, and so who they may write to (R-MSG-02). */
export type MemberRole = "dispatcher" | "loader" | "driver" | "store_manager";

export type ReportType =
  | "loading_shortfall"
  | "vehicle_fault"
  | "road_disruption"
  | "failed_delivery"
  | "damaged_goods"
  | "receipt_dispute"
  | "stock_discrepancy"
  | "late"
  | "other";

/** GET /api/threads/{id} and /by-subject?type=trip&id= */
export type ThreadView = {
  threadId: Uuid;
  subjectType: "trip";
  subjectId: string;
  depotCode: string;
  vehicleId: string | null;
  serviceDate: IsoDate | null;
  outletIds: string[];
  memberRole: MemberRole;
  /** The reader's own outlets among the trip's, for a store manager. */
  myOutlets: string[];
  /** False once the day after the trip is over: read only (R-MSG-04). */
  open: boolean;
};

export type MessageView = {
  messageId: Uuid;
  threadId: Uuid;
  /** The person, or the role for a report made from an event. */
  authorName: string;
  authorRole: MemberRole;
  kind: "message" | "report";
  reportType: ReportType | null;
  audience: MessageAudience;
  audienceOutlet: string | null;
  body: string;
  /** Played from /api/threads/{threadId}/voice/{voiceNoteId}. */
  voiceNoteId: Uuid | null;
  voiceDurationMs: number | null;
  createdAt: IsoInstant;
  mine: boolean;
};

/** Newest first; nextCursor reads older messages. */
export type MessagePage = { items: MessageView[]; nextCursor: string | null };

/** GET /api/threads/reports?depot=&date=: the warning signs on the dispatcher's timeline. */
export type ReportMarkView = {
  threadId: Uuid;
  tripId: string;
  vehicleId: string | null;
  messageId: Uuid;
  at: IsoInstant;
  reportType: ReportType;
  authorRole: MemberRole;
  excerpt: string;
  voice: boolean;
};

/** GET /api/threads/{id}/members: who the reader may write to. */
export type MemberView = { to: MessageAudience; outletId: string | null; label: string };

/** message:Post. Upload a voice note first to PUT /api/threads/{threadId}/voice/{voiceNoteId}. */
export type PostMessagePayload = {
  threadId: Uuid;
  body: string;
  to: MessageAudience;
  outletId?: string;
  report?: ReportType;
  voiceNoteId?: Uuid;
  clientMessageId?: Uuid;
};
