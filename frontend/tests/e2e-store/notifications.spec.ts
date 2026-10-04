import { expect, test, type Page } from "@playwright/test";
import { ORDER, mockStore } from "./mocks.ts";

// Issue #118: the store manager's notifications, Figma 10 Notifications
// (desktop drawer 11:117503, mobile sheet 11:125924) and the Home card
// (11:117599): a deferral notice and the expected arrival, the booklet's two
// asks of the store (p6). And "Synced" sends and reads again.

const at = (minutesAgo: number) => new Date(Date.now() - minutesAgo * 60_000).toISOString();
const ITEMS = [
  { notificationId: "n-eta", eventType: "trip.released", title: "VEH043 is on the way", body: "You're stop 3 of 7. Expected 05:44.", subjectType: "trip", subjectId: "t1", createdAt: at(3), readAt: null },
  { notificationId: "n-def", eventType: "order.deferred", title: "Order deferred", body: "Your order planned for 2026-10-03 was deferred: no cold space", subjectType: "order", subjectId: ORDER.orderId, createdAt: at(30), readAt: null },
  { notificationId: "n-ok", eventType: "delivery.completed", title: "Delivery recorded", body: "Your delivery was recorded as DELIVERED. Proof is ready to review.", subjectType: "delivery", subjectId: "d1", createdAt: at(300), readAt: at(290) },
];

async function inbox(page: Page) {
  const sent: Array<{ kind: string; payload: Record<string, unknown> }> = [];
  const read = new Map<string, string>();
  const unread = () => ITEMS.filter((n) => n.readAt === null && !read.has(n.notificationId)).length;
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/notifications**", (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname.endsWith("/stream")) return route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: unread\ndata: {"count":${unread()}}\n\n` });
    if (pathname.endsWith("/unread-count")) return route.fulfill(json({ count: unread() }));
    return route.fulfill(json({ items: ITEMS.map((n) => (read.has(n.notificationId) ? { ...n, readAt: read.get(n.notificationId) } : n)), nextCursor: null }));
  });
  await page.route("**/api/commands", async (route) => {
    const body = route.request().postDataJSON() as { commandId: string; kind: string; payload: Record<string, unknown> };
    if (!body.kind.startsWith("notification:")) return route.fallback();
    sent.push(body);
    const ids = body.kind === "notification:MarkAllRead" ? ITEMS.map((n) => n.notificationId) : (body.payload.notificationIds as string[]);
    for (const id of ids) if (!read.has(id)) read.set(id, new Date().toISOString());
    return route.fulfill(json({ commandId: body.commandId, kind: body.kind, replayed: false, result: { marked: ids.length, unread: unread() } }));
  });
  return sent;
}

test("the drawer marks all read", async ({ page }) => {
  await mockStore(page);
  const sent = await inbox(page);
  await page.goto("/");

  await page.getByRole("button", { name: /^Notifications/ }).first().click();
  const drawer = page.getByRole("dialog", { name: "Notifications" });
  await expect(drawer).toContainText("2 new · today");
  await drawer.getByRole("button", { name: "Mark all as read" }).click();
  await expect.poll(() => sent.map((c) => c.kind)).toEqual(["notification:MarkAllRead"]);
  await expect(drawer).toContainText("All caught up");
});

test("a deferral notice opens its order", async ({ page }) => {
  await mockStore(page);
  const sent = await inbox(page);
  await page.goto("/");
  await page.getByRole("button", { name: /^Notifications/ }).first().click();
  await page.getByRole("dialog", { name: "Notifications" }).getByRole("button", { name: /Order deferred\. Order deferred/ }).click();
  await expect.poll(() => sent.map((c) => c.kind)).toEqual(["notification:MarkRead"]);
  expect(sent[0]!.payload).toEqual({ notificationIds: ["n-def"] });
  await expect(page.getByRole("dialog", { name: /ORD/ })).toBeVisible();
});

test("the drawer header Read all button marks all read and removes items from the list", async ({ page }) => {
  await mockStore(page);
  const sent = await inbox(page);
  await page.goto("/");

  await page.getByRole("button", { name: /^Notifications/ }).first().click();
  const drawer = page.getByRole("dialog", { name: "Notifications" });
  await expect(drawer).toContainText("2 new · today");
  await expect(drawer.getByRole("button", { name: /Order deferred/ })).toBeVisible();

  await drawer.getByRole("button", { name: "Read all" }).click();
  await expect.poll(() => sent.map((c) => c.kind)).toEqual(["notification:MarkAllRead"]);
  await expect(drawer).toContainText("All caught up");
  await expect(drawer.getByRole("button", { name: /Order deferred/ })).toHaveCount(0);
});

test("on a phone the bell carries a dot and opens the bottom sheet", async ({ page }) => {
  await page.setViewportSize({ width: 393, height: 852 });
  await mockStore(page);
  await inbox(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Notifications, 2 unread" }).click();
  await expect(page.getByRole("dialog", { name: "Notifications" })).toContainText("VEH043 is on the way");
});

test("tapping Synced reads the orders again", async ({ page }) => {
  await mockStore(page);
  await inbox(page);
  let reads = 0;
  page.on("request", (r) => { if (new URL(r.url()).pathname === "/api/orders") reads++; });
  await page.goto("/");
  const synced = page.getByRole("button", { name: /^Synced .*\. Sync now$/ });
  await expect(synced).toBeVisible();
  const before = reads;
  await synced.click();
  await expect.poll(() => reads).toBeGreaterThan(before);
});
