import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

// Live refresh phase 3: the phone watches its vehicle for the day on the tab's one
// connection. While the watch is up the run sheet's 2 s poll steps back to a
// safety net, and a change naming the vehicle reads the run sheet again at once.

test("with its vehicle's watch up, the phone stops polling the run sheet and reads it on a change", async ({ page }) => {
  const server = await serve(page);
  let changed = false;
  const asked: string[] = [];
  // Registered after the mock server, so it answers the tab's connection first.
  await page.route("**/api/live/stream**", async (route) => {
    asked.push(new URL(route.request().url()).search);
    // Each answer ends and the browser reconnects at once, as through a proxy that closes idle streams.
    const body = changed ? 'retry: 300\nevent: change\ndata: {"topic":"delivery"}\n\n' : "retry: 300\nevent: ping\ndata: {}\n\n";
    return route.fulfill({ status: 200, contentType: "text/event-stream", body });
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
  await expect.poll(() => asked.some((q) => q.includes("changes.vehicle=VEH043"))).toBe(true);

  await page.waitForTimeout(2_000);
  const reads = () => server.calls.filter((c) => c.endsWith("/api/execution/run-sheets")).length;
  const before = reads();
  await page.waitForTimeout(6_000);
  expect(reads() - before, "the watch is up, so the 2 s poll is off").toBeLessThanOrEqual(1);

  changed = true;
  await expect.poll(reads, { timeout: 4_000 }).toBeGreaterThan(before);
});
