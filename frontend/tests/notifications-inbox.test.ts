import assert from "node:assert/strict";
import test from "node:test";
import type { NotificationView } from "../src/shared/domain/notification.ts";
import { ago, appended, isStale, kindOf, markedAllRead, markedRead, STALE_AFTER_MS } from "../src/shared/notifications/inbox.ts";

// Issue #118: what each role's inbox shows and when its live count is stale.

const n = (id: string, createdAt: string, readAt: string | null = null, eventType = "order.deferred"): NotificationView => ({
  notificationId: id, eventType, title: "t", body: "b", subjectType: null, subjectId: null, createdAt, readAt,
});

test("every routed event has a label and a tone, and the urgent ones are loud", () => {
  assert.deepEqual(kindOf("order.deferred"), { label: "Order deferred", tone: "warning" });
  assert.deepEqual(kindOf("trip.released"), { label: "Vehicle left", tone: "good" });
  for (const urgent of ["order.unservable", "loading.shortfall", "delivery.failed", "issue.escalated", "vehicle.fault_reported"]) {
    assert.equal(kindOf(urgent).tone, "urgent", urgent);
  }
  assert.deepEqual(kindOf("something.new"), { label: "Notification", tone: "info" }, "an event added later still shows");
});

test("times read as the designs write them", () => {
  const now = new Date("2026-10-03T10:00:00Z");
  assert.equal(ago("2026-10-03T09:59:40Z", now), "Just now");
  assert.equal(ago("2026-10-03T09:58:00Z", now), "2 min ago");
  assert.equal(ago("2026-10-03T07:00:00Z", now), "3 h ago");
  assert.match(ago("2026-10-01T07:00:00Z", now), /Thu|1 Oct/);
});

test("the live count is stale after 40 s of silence, the stream's 25 s beat plus slack", () => {
  assert.equal(isStale(null, 1_000), true, "never heard");
  assert.equal(isStale(1_000, 1_000 + 25_000), false);
  assert.equal(isStale(1_000, 1_000 + STALE_AFTER_MS + 1), true);
});

test("marking read changes only what was unread and named", () => {
  const items = [n("a", "2026-10-03T09:00:00Z"), n("b", "2026-10-03T09:01:00Z", "2026-10-03T09:02:00Z"), n("c", "2026-10-03T09:03:00Z")];
  const after = markedRead(items, new Set(["a", "b"]), "2026-10-03T10:00:00Z");
  assert.equal(after[0]!.readAt, "2026-10-03T10:00:00Z");
  assert.equal(after[1]!.readAt, "2026-10-03T09:02:00Z", "read state is set once");
  assert.equal(after[2]!.readAt, null);
});

test("mark all read stops at when the person looked; later arrivals stay unread", () => {
  const items = [n("old", "2026-10-03T09:00:00Z"), n("new", "2026-10-03T09:30:00Z")];
  const after = markedAllRead(items, "2026-10-03T09:10:00Z");
  assert.equal(after[0]!.readAt, "2026-10-03T09:10:00Z");
  assert.equal(after[1]!.readAt, null);
});

test("a later page adds what is new and never repeats a notification", () => {
  const merged = appended([n("a", "t"), n("b", "t")], [n("b", "t"), n("c", "t")]);
  assert.deepEqual(merged.map((x) => x.notificationId), ["a", "b", "c"]);
});
