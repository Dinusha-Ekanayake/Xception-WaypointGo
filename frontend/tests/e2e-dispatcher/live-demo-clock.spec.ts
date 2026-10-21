import { expect, test } from "@playwright/test";
import { LIVE_NOW, liveDay, serve } from "./mocks.ts";

// Issue #231: in demo mode a window is measured from the demo clock. The offset
// arrives with the first read of /api/demo, after Live has painted once.

test("a window is measured from the demo clock, not the device's, once the offset arrives", async ({ page }) => {
  const twoDays = 2 * 24 * 3600;
  await page.clock.install({ time: new Date(Date.parse(LIVE_NOW) - twoDays * 1000) });
  await serve(page, liveDay());
  await page.route("**/api/demo", (route) => route.fulfill({
    status: 200, contentType: "application/json",
    body: JSON.stringify({ enabled: true, now: LIVE_NOW, offsetSeconds: twoDays, banner: true, simPointIntervalMs: 2000, positionFlushMs: 5000, speed: 10, rowVersion: 1 }),
  }));
  await page.goto("/#/live");
  // Before the fix the card measured from the device until its next 30 s tick: "2928 min left".
  await expect(page.getByRole("region", { name: "Needs you" }).getByRole("listitem").nth(1)).toContainText("48 min left");
});
