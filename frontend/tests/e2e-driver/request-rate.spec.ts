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
