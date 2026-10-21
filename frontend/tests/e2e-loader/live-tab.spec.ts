import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// Live refresh phase 2: the board's changes and the bell's unread count arrive on
// the tab's one connection, never on the separate endpoints, and a change still
// makes the board read again at once.

const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

test("one live connection carries the board's changes and the bell's count", async ({ page }) => {
  const m = { ...manifest("trip-tab", false, 1), status: "NOT_STARTED" as const, holder: null };
  let arrived = false;
  let signal: () => void = () => {};
  const signalled = new Promise<void>((resolve) => (signal = resolve));
  const asked: string[] = [];
  let tabs = 0;
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const { pathname } = url;
    if (pathname.endsWith("/stream")) asked.push(`${pathname}?${url.searchParams.toString()}`);
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") {
      return route.fulfill(json(board(m).map((t) => ({ ...t, driverAtDepotAt: arrived ? new Date().toISOString() : null }))));
    }
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/notifications/unread-count") return route.fulfill(json({ count: 3 }));
    if (pathname === "/api/notifications") return route.fulfill(json({ items: [], nextCursor: null }));
    if (pathname === "/api/live/stream") {
      tabs++;
      if (tabs > 1) await signalled;
      const body =
        tabs > 1
          ? 'retry: 300\nevent: change\ndata: {"topic":"vehicle","depot":"KDY"}\n\nevent: unread\ndata: {"count":3}\n\n'
          : 'retry: 300\nevent: ping\ndata: {}\n\nevent: unread\ndata: {"count":3}\n\n';
      return route.fulfill({ status: 200, contentType: "text/event-stream", body });
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await expect(page.getByText("No vehicle has arrived yet")).toBeVisible();
  await expect(page.getByRole("button", { name: "Notifications, 3 unread" })).toBeVisible();
  arrived = true;
  signal();
  await expect(page.getByRole("button", { name: "Take trip" })).toBeVisible({ timeout: 4_000 });

  const tab = asked.find((a) => a.startsWith("/api/live/stream"))!;
  expect(tab).toContain("parts=");
  expect(tab).toContain("changes");
  expect(tab).toContain("unread");
  expect(asked.filter((a) => a.startsWith("/api/changes/stream") || a.startsWith("/api/notifications/stream")), "no separate streams").toEqual([]);
});
