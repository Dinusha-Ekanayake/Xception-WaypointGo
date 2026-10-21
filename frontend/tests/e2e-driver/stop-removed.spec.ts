import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

// R-EXE-31: when the dispatcher takes a stop off the trip, the driver hears it by
// push. The phone watches no change stream, so the push itself reads the run
// sheet again at once and says the run sheet changed, as for a revised plan.

test("a stop the dispatcher removed reads the run sheet again and says the route changed", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Fuel QR" })).toBeVisible();
  const reads = () => server.calls.filter((c) => c.startsWith("GET /api/execution/run-sheets")).length;
  const before = reads();

  server.notifications.push({
    notificationId: "n-skip", eventType: "delivery.skipped", title: "Stop removed · OUT052",
    body: "The dispatcher took OUT052 off your route. Reason: Store closed", subjectType: "delivery", subjectId: "delivery-2",
    createdAt: new Date().toISOString(), readAt: null,
  } as (typeof server.notifications)[number]);

  await expect(page.getByRole("dialog", { name: "Run sheet changed" })).toBeVisible({ timeout: 20_000 });
  expect(reads(), "the run sheet is read again at once").toBeGreaterThan(before);
});
