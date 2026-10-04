import { expect, test, type Page } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// Issue #118: the loader's bell, inbox and "Synced" button.

const NOW = Date.now();
const iso = (minutesAgo: number) => new Date(NOW - minutesAgo * 60_000).toISOString();
const RELEASED = {
  notificationId: "n-released", eventType: "trip.released", title: "Trip released · VEH044",
  body: "VEH044 left for 2026-10-03 with 3 stops.", subjectType: "trip", subjectId: "t-44", createdAt: iso(2), readAt: null,
};
const PUBLISHED = {
  notificationId: "n-plan", eventType: "plan.published", title: "Plan published for 2026-10-03",
  body: "Version 1 with 6 trips is ready to load.", subjectType: "plan", subjectId: "p-1", createdAt: iso(90), readAt: iso(80),
};

async function serve(page: Page, streamCount: number | null) {
  const commands: Array<{ kind: string; payload: Record<string, unknown> }> = [];
  let tripsReads = 0;
  let unread = 1;
  // Read state as the server keeps it, so a list read again after marking shows it read.
  const read = new Set<string>();
  const current = manifest("trip-n", false, 2);
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") { tripsReads++; return route.fulfill(json(board(current))); }
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/notifications/stream") {
      return streamCount === null
        ? route.fulfill({ status: 503, body: "" })
        : route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: unread\ndata: {"count":${streamCount}}\n\n` });
    }
    if (pathname === "/api/notifications/unread-count") return route.fulfill(json({ count: unread }));
    if (pathname === "/api/notifications") {
      const items = [RELEASED, PUBLISHED].map((n) => (read.has(n.notificationId) ? { ...n, readAt: new Date().toISOString() } : n));
      return route.fulfill(json({ items, nextCursor: null }));
    }
    if (pathname === "/api/commands") {
      const body = route.request().postDataJSON() as { commandId: string; kind: string; payload: Record<string, unknown> };
      commands.push(body);
      read.add(RELEASED.notificationId);
      unread = 0;
      return route.fulfill(json({ commandId: body.commandId, kind: body.kind, replayed: false, result: { marked: 1, unread: 0 } }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  return { commands, tripsReads: () => tripsReads };
}

for (const viewport of [{ width: 393, height: 852 }, { width: 1280, height: 800 }]) {
  test(`at ${viewport.width}px the bell shows unread, the inbox lists the depot's news, and read state is sent`, async ({ page }) => {
    await page.setViewportSize(viewport);
    const server = await serve(page, 1);
    await page.goto("/");
    const bell = page.getByRole("button", { name: "Notifications, 1 unread" });
    await expect(bell).toBeVisible();

    await bell.click();
    const sheet = page.getByRole("dialog", { name: "Notifications" });
    await expect(sheet).toContainText("1 new");
    await expect(sheet.getByRole("button", { name: /Unread\. Vehicle left\. Trip released · VEH044/ })).toBeVisible();
    await expect(sheet).toContainText("Plan published for 2026-10-03");

    await sheet.getByRole("button", { name: /Trip released · VEH044/ }).click();
    await expect.poll(() => server.commands.map((c) => c.kind)).toEqual(["notification:MarkRead"]);
    expect(server.commands[0]!.payload).toEqual({ notificationIds: ["n-released"] });
    await expect(sheet).toContainText("All caught up");
  });
}

test("mark all as read sends when the loader looked, and the badge clears", async ({ page }) => {
  const server = await serve(page, 1);
  await page.goto("/");
  await page.getByRole("button", { name: "Notifications, 1 unread" }).click();
  const sheet = page.getByRole("dialog", { name: "Notifications" });
  await sheet.getByRole("button", { name: "Mark all as read" }).click();
  await expect.poll(() => server.commands.map((c) => c.kind)).toEqual(["notification:MarkAllRead"]);
  expect(typeof server.commands[0]!.payload.upTo).toBe("string");
  await sheet.getByRole("button", { name: "Close" }).click();
  await expect(page.getByRole("button", { name: "Notifications", exact: true })).toBeVisible();
});

test("with the stream down the count still arrives by polling", async ({ page }) => {
  await serve(page, null);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Notifications, 1 unread" })).toBeVisible();
});

test("tapping Synced reads the board again", async ({ page }) => {
  const server = await serve(page, 0);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
  const before = server.tripsReads();
  await page.getByRole("button", { name: /Synced .*\. Sync now/ }).click();
  await expect.poll(() => server.tripsReads()).toBeGreaterThan(before);
});

test("Settings says plainly when this server cannot send alerts", async ({ page }) => {
  await serve(page, 0);
  await page.route("**/api/notifications/push-config", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ enabled: false, publicKey: null, reason: "push is not configured on this server" }) }));
  await page.goto("/");
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await expect(page.getByText("Alerts are not set up on this server")).toBeVisible();
  await expect(page.getByRole("button", { name: "On", exact: true })).toHaveCount(0);
});
