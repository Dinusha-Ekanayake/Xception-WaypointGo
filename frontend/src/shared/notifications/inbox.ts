import type { NotificationView } from "../domain/notification.ts";

// The pure half of a role's inbox (issue #118): what to call each kind of
// notification, how loud it is, how old it reads, and when the live count is
// stale. Each role draws its own inbox from these and useInbox (no shared
// notification component, as decided on #14).

export type Tone = "urgent" | "warning" | "good" | "info";

/** The short label Figma shows above each notification, by the event that raised it. */
const KINDS: Record<string, { label: string; tone: Tone }> = {
  "order.deferred": { label: "Order deferred", tone: "warning" },
  "order.auto_deferred": { label: "Order moved", tone: "warning" },
  "order.unservable": { label: "Cannot deliver", tone: "urgent" },
  "warehouse.order_status_changed": { label: "Stock problem", tone: "warning" },
  "plan.published": { label: "Plan published", tone: "info" },
  "plan.revised": { label: "Plan revised", tone: "warning" },
  "trip.released": { label: "Vehicle left", tone: "good" },
  "loading.shortfall": { label: "Loading shortfall", tone: "urgent" },
  "delivery.started": { label: "Delivery arriving", tone: "info" },
  "delivery.arrived": { label: "Delivery arrived", tone: "good" },
  "vehicle.at_depot": { label: "Driver at the dock", tone: "info" },
  "delivery.completed": { label: "Delivery recorded", tone: "good" },
  "delivery.failed": { label: "Delivery failed", tone: "urgent" },
  "eta.changed": { label: "Arrival changed", tone: "warning" },
  "issue.raised": { label: "Issue raised", tone: "urgent" },
  "issue.escalated": { label: "Issue escalated", tone: "urgent" },
  "issue.resolved": { label: "Issue resolved", tone: "good" },
  "vehicle.fault_reported": { label: "Vehicle fault", tone: "urgent" },
  "road.disruption_reported": { label: "Road disruption", tone: "warning" },
  "receipt.disputed": { label: "Receipt disputed", tone: "warning" },
  "vehicle.status_changed": { label: "Vehicle status", tone: "info" },
  "message.posted": { label: "Message", tone: "info" },
  "message.unseen": { label: "Message not seen", tone: "warning" },
};

export function kindOf(eventType: string): { label: string; tone: Tone } {
  return KINDS[eventType] ?? { label: "Notification", tone: "info" };
}

/**
 * How loud a notification is. Anything about an open issue is urgent, whatever
 * event raised it, so an issue reads red until it is resolved; the rest take
 * their event's tone.
 */
export function toneOf(n: Pick<NotificationView, "eventType" | "subjectType"> & { title?: string }): Tone {
  // A resolved issue is good news (R-NOT-19), the one issue event that is not an alarm.
  if (n.eventType === "issue.resolved") return "good";
  if (n.subjectType === "issue" || n.eventType.startsWith("issue.")) return "urgent";
  // A report on a trip's thread is titled "<Role> report · VEH" (R-NOT-14).
  if (n.eventType === "message.posted" && / report\b/.test(n.title ?? "")) return "urgent";
  return kindOf(n.eventType).tone;
}


/** The bell's count: nothing at zero or unknown, then the number, capped at "99+". */
export function badgeText(count: number | null | undefined): string | null {
  if (!count || count <= 0) return null;
  return count > 99 ? "99+" : String(count);
}

export const isUnread = (n: Pick<NotificationView, "readAt">): boolean => n.readAt === null;

/** "Just now", "2 min ago", "3 h ago", then the day and time. */
export function ago(createdAt: string, now: Date): string {
  const at = new Date(createdAt);
  const minutes = Math.floor((now.getTime() - at.getTime()) / 60_000);
  if (Number.isNaN(minutes)) return "";
  if (minutes < 1) return "Just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return at.toLocaleString("en-GB", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
}

/**
 * The server sends the unread count every 25 seconds on the stream. Heard
 * nothing for longer than this, the count is stale: say live updates are
 * paused and poll instead (#14 decision 8).
 */
export const STALE_AFTER_MS = 40_000;
export const POLL_MS = 30_000;

export function isStale(lastHeardAt: number | null, now: number): boolean {
  return lastHeardAt === null || now - lastHeardAt > STALE_AFTER_MS;
}

/** Marks read on this device as soon as the server agreed, without waiting for the next list. */
export function markedRead(items: NotificationView[], ids: Set<string>, at: string): NotificationView[] {
  return items.map((n) => (ids.has(n.notificationId) && n.readAt === null ? { ...n, readAt: at } : n));
}

/** Everything created at or before `upTo` reads as read; later arrivals stay unread (NOT-08). */
export function markedAllRead(items: NotificationView[], upTo: string): NotificationView[] {
  const limit = Date.parse(upTo);
  return items.map((n) => (n.readAt === null && Date.parse(n.createdAt) <= limit ? { ...n, readAt: upTo } : n));
}

/** Newer pages arrive first; a later page is appended without duplicates. */
export function appended(items: NotificationView[], more: NotificationView[]): NotificationView[] {
  const seen = new Set(items.map((n) => n.notificationId));
  return [...items, ...more.filter((n) => !seen.has(n.notificationId))];
}

/**
 * The one colour of a notification's dot and label (Figma notifications):
 * the accent while unread, red only for an alarm while unread, grey once read.
 * Every kind looks the same otherwise; the card is never tinted. A theme
 * variable, so night mode takes its own shade.
 */
export function noteColor(n: Pick<NotificationView, "eventType" | "subjectType" | "readAt"> & { title?: string }): string {
  if (n.readAt !== null) return "var(--color-go-note-read)";
  return toneOf(n) === "urgent" ? "var(--color-go-note-alarm)" : "var(--color-go-note-accent)";
}
