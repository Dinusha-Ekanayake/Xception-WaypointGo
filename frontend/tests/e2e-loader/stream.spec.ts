import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// F-07b on the loader: the dock board listens on the depot's change stream. A
// driver reporting at the depot is a signal, and the board reads again at once;
// while the stream is up the board does not poll every 2 s.

const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

test("a driver at the depot reaches the board through the stream, with no 2 s polling", async ({ page }) => {
  const m = { ...manifest("trip-stream", false, 1), status: "NOT_STARTED" as const, holder: null };
  let arrived = false;
  let boardReads = 0;
  let signal: () => void = () => {};
  const signalled = new Promise<void>((resolve) => (signal = resolve));
  let streams = 0;
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") {
      boardReads++;
      return route.fulfill(json(board(m).map((t) => ({ ...t, driverAtDepotAt: arrived ? new Date().toISOString() : null }))));
    }
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/changes/stream") {
      streams++;
      // The first answer only says the stream is up; the next one carries the change, once the test sends it.
      if (streams > 1) await signalled;
      const body = streams > 1 ? 'retry: 300\nevent: change\ndata: {"topic":"vehicle","depot":"KDY"}\n\n' : "retry: 300\nevent: ping\ndata: {}\n\n";
      return route.fulfill({ status: 200, contentType: "text/event-stream", body });
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await expect(page.getByText("No vehicle has arrived yet")).toBeVisible();
  const before = boardReads;
  await page.waitForTimeout(5_000);
  expect(boardReads - before, "the stream is up, so the 2 s poll is off").toBeLessThanOrEqual(1);

  arrived = true;
  signal();
  await expect(page.getByRole("button", { name: "Take trip" })).toBeVisible({ timeout: 4_000 });
});
