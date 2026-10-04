import type { NotificationView } from "@shared/domain/types";

// The driver's messages on the trip's thread (issue #136). Writing goes through
// the shared queuedSender (shared/messaging/senders.ts), so a typed or spoken
// message with no signal is kept on the phone and sent when it returns.

/** Unread notifications of messages for this driver: the count on the Messages button. */
export function unreadMessages(items: NotificationView[]): NotificationView[] {
  return items.filter((n) => n.eventType === "message.posted" && n.readAt === null);
}
