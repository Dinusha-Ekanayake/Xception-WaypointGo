import type { IsoInstant, Uuid } from "./common.ts";

// Mirrors com.waypoint.dispatch.notification.contract.

export type NotificationView = {
  notificationId: Uuid;
  /** The event that produced it, for example "order.deferred". */
  eventType: string;
  title: string;
  body: string;
  subjectType: string | null;
  subjectId: string | null;
  createdAt: IsoInstant;
  readAt: IsoInstant | null;
};

export const NotificationCommandKind = {
  markRead: "notification:MarkRead",
  subscribe: "notification:Subscribe",
  unsubscribe: "notification:Unsubscribe",
} as const;

/** An empty list marks every unread notification read. */
export type MarkRead = { notificationIds: Uuid[] };
export type SubscribePush = {
  deviceId: Uuid;
  endpoint: string;
  p256dhKey: string;
  authSecret: string;
};
export type UnsubscribePush = { endpoint: string };
