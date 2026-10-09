import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

// Roadmap F-07 exit gate: the driver screen at 60 requests a minute or fewer.
// The run sheet is read every 2 s; the vehicle and its outlets, which almost
// never change in a day, are not read again on every pass.

test("the driver screen stays at 60 requests a minute or fewer", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
  await page.waitForTimeout(3000);
  const start = server.calls.length;
  await page.waitForTimeout(20_000);
  // The live stream is one long-lived connection; the mock answers it at once, so its reconnects are not reads.
  const reads = server.calls.slice(start).filter((c) => !c.endsWith("/notifications/stream"));
  expect(reads.length, reads.join("\n")).toBeLessThanOrEqual(20);
  expect(reads.filter((c) => c.includes("/api/reference/"))).toHaveLength(0);
});

// B-04 baseline, 2026-10-08: a driver with nothing released today measured 85 a
// minute on the dev stack, because the week ahead (8 days, 3 reads each) was
// asked every 30 s on top of the 2 s run read.
test("with nothing today, looking ahead keeps the screen under the same budget", async ({ page }) => {
  const server = await serve(page, []);
  await page.goto("/");
  await expect.poll(() => server.calls.filter((c) => c.includes("/api/plans/published")).length, { timeout: 15_000 }).toBeGreaterThan(0);
  await page.waitForTimeout(3000);
  const start = server.calls.length;
  await page.waitForTimeout(30_000);
  const reads = server.calls.slice(start).filter((c) => !c.endsWith("/notifications/stream"));
  expect(reads.length, reads.join("\n")).toBeLessThanOrEqual(30);
});
