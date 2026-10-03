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
  /** The values title and body were filled from, for a translated message. Empty on older ones. */
  facts?: Record<string, string>;
};

/** GET /api/notifications/unread-count, and each `unread` event of /api/notifications/stream. */
export type UnreadCountView = { count: number };

/** GET /api/notifications/push-config. Push off is a state to show, with its reason. */
export type PushConfigView = {
  enabled: boolean;
  /** The VAPID public key, base64url, when push is on. */
  publicKey: string | null;
  reason: string | null;
};

export const NotificationCommandKind = {
  markRead: "notification:MarkRead",
  markAllRead: "notification:MarkAllRead",
  subscribe: "notification:Subscribe",
  unsubscribe: "notification:Unsubscribe",
} as const;

/**
 * At least one id. Read state is set-once, so these take no expectedVersion.
 * Use MarkAllRead for everything.
 */
export type MarkRead = { notificationIds: Uuid[] };
/** Everything created at or before upTo (when the person last looked); later arrivals stay unread. */
export type MarkAllRead = { upTo: IsoInstant };
export type SubscribePush = {
  deviceId: Uuid;
  endpoint: string;
  p256dhKey: string;
  authSecret: string;
};
export type UnsubscribePush = { endpoint: string };
