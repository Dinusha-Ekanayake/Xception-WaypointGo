import { expect, test, type Page } from "@playwright/test";
import { serve } from "./mocks.ts";

// Issue #118: the dispatcher's bell, notifications panel (Figma 189:23606), the
// Overview card (189:10739), and "Synced" reading the screen again.

const at = (minutesAgo: number) => new Date(Date.now() - minutesAgo * 60_000).toISOString();
const ITEMS = [
  { notificationId: "n1", eventType: "delivery.failed", title: "Delivery failed at OUT085", body: "Store closed. It needs a decision.", subjectType: "delivery", subjectId: "d1", createdAt: at(2), readAt: null },
  { notificationId: "n2", eventType: "issue.raised", title: "Issue raised: DAMAGED_GOODS", body: "HIGH severity at Kandy.", subjectType: "issue", subjectId: "issue-9", createdAt: at(6), readAt: null },
  { notificationId: "n3", eventType: "loading.shortfall", title: "Loading shortfall", body: "missing: 1 units. Crate gone Departure is blocked.", subjectType: "trip", subjectId: "t1", createdAt: at(12), readAt: at(10) },
];

async function inbox(page: Page) {
  const sent: Array<{ kind: string; payload: Record<string, unknown> }> = [];
  let unread = 2;
  const read = new Map<string, string>();
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  // Registered after serve(), so these answer first.
  await page.route("**/api/notifications**", (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname.endsWith("/stream")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: unread\ndata: {"count":${unread}}\n\n` });
    if (pathname.endsWith("/unread-count")) return route.fulfill(json({ count: unread }));
    return route.fulfill(json({ items: ITEMS.map((n) => (read.has(n.notificationId) ? { ...n, readAt: read.get(n.notificationId) } : n)), nextCursor: null }));
  });
  await page.route("**/api/commands", async (route) => {
    const body = route.request().postDataJSON() as { commandId: string; kind: string; payload: Record<string, unknown> };
    if (!body.kind.startsWith("notification:")) return route.fallback();
    sent.push(body);
    const now = new Date().toISOString();
    const ids = body.kind === "notification:MarkAllRead" ? ITEMS.map((n) => n.notificationId) : (body.payload.notificationIds as string[]);
    for (const id of ids) if (!read.has(id) && ITEMS.find((n) => n.notificationId === id)?.readAt === null) read.set(id, now);
    unread = ITEMS.filter((n) => n.readAt === null && !read.has(n.notificationId)).length;
    return route.fulfill(json({ commandId: body.commandId, kind: body.kind, replayed: false, result: { marked: 1, unread } }));
  });
  return sent;
}

test("the bell names the unread count and opens the panel, newest first, with mark as read", async ({ page }) => {
  await serve(page);
  const sent = await inbox(page);
  await page.goto("/#/orders");

  await expect(page.getByRole("button", { name: "Notifications, 2 unread" })).toHaveText("2");
  await page.getByRole("button", { name: "Notifications, 2 unread" }).click();
  const panel = page.getByRole("dialog", { name: "Notifications" });
  await expect(panel).toContainText("2 new · newest first");
  const rows = panel.getByRole("listitem");
  await expect(rows.first()).toContainText("Delivery failed");
  await expect(rows.first()).toContainText("2 min ago");
  await expect(rows.first()).toContainText("Delivery failed at OUT085");

  await rows.first().getByRole("button", { name: "Mark as read" }).click();
  await expect.poll(() => sent.map((c) => c.kind)).toEqual(["notification:MarkRead"]);
  expect(sent[0]!.payload).toEqual({ notificationIds: ["n1"] });
  await expect(rows.first().getByRole("button", { name: "Mark as read" })).toHaveCount(0);

  await panel.getByRole("button", { name: "Mark all as read" }).click();
  await expect.poll(() => sent.map((c) => c.kind)).toEqual(["notification:MarkRead", "notification:MarkAllRead"]);
  await panel.getByRole("button", { name: "Close", exact: true }).click();
  await expect(panel).toHaveCount(0);
});

test("opening a notification goes to the screen of its subject", async ({ page }) => {
  await serve(page);
  await inbox(page);
  await page.goto("/#/orders");
  await page.getByRole("button", { name: /Notifications/ }).click();
  const issueRow = page.getByRole("dialog", { name: "Notifications" }).getByRole("listitem").nth(1);
  await issueRow.getByRole("button", { name: "Open" }).click();
  await expect(page).toHaveURL(/#\/issues/);
});

test("the Overview card shows the newest three and View all opens the panel", async ({ page }) => {
  await serve(page);
  await inbox(page);
  await page.goto("/#/overview");
  const card = page.getByRole("region", { name: "Recent notifications" });
  await expect(card.getByRole("listitem")).toHaveCount(3);
  await card.getByRole("button", { name: /View all/ }).click();
  await expect(page.getByRole("dialog", { name: "Notifications" })).toBeVisible();
});

test("tapping Synced reads the screen again", async ({ page }) => {
  await serve(page);
  await inbox(page);
  let reads = 0;
  page.on("request", (r) => { if (new URL(r.url()).pathname === "/api/orders/day") reads++; });
  await page.goto("/#/orders");
  await expect(page.getByRole("button", { name: /^Synced .*\. Sync now$/ })).toBeVisible();
  const before = reads;
  await page.getByRole("button", { name: /^Synced .*\. Sync now$/ }).click();
  await expect.poll(() => reads).toBeGreaterThan(before);
});
