import { expect, test, type Page } from "@playwright/test";
import { serve, stop } from "./mocks.ts";

// R-FLT-06 (docs/issues/vehicle-broken/PLAN.md), as in Figma "Driver: Home" and
// "Route: Next stop": before the trip the "Available" switch takes the vehicle
// out of use for today (its trips move to other vehicles in a new plan), asking
// first; once the trip has started, a problem is Report problem, sent by sliding
// a row, and a vehicle problem is only the report: the dispatcher decides the trip.

const SHOTS = process.env.SHOTS_DIR;
const shot = async (page: Page, name: string) => {
  if (SHOTS) await page.screenshot({ path: `${SHOTS}/${name}.png` });
};

test("before the trip the Available switch asks, then takes the vehicle out of use; on again puts it back", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  const toggle = page.getByRole("switch", { name: "Available" });
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await expect(page.getByRole("button", { name: "Report problem" })).toHaveCount(0);
  await shot(page, "1-home-before-trip");

  await toggle.click();
  const sheet = page.getByRole("dialog", { name: "Vehicle status" });
  await expect(sheet.getByRole("heading", { name: "Mark the vehicle not available?" })).toBeVisible();
  await expect(sheet).toContainText("its trips today move to other vehicles in a new plan");
  await page.waitForTimeout(600);
  await shot(page, "2-not-available-sheet");
  await sheet.getByRole("button", { name: "Cancel" }).click();
  await expect(sheet).toBeHidden();
  await expect(toggle).toHaveAttribute("aria-checked", "true");

  const marked = () => server.commands.filter((c) => c.kind === "vehicle:SetDayStatus");
  await toggle.click();
  await page.getByRole("dialog", { name: "Vehicle status" }).getByRole("button", { name: "Yes, not available" }).click();
  await expect.poll(() => marked().length).toBe(1);
  expect(marked()[0]?.payload).toMatchObject({ status: "unavailable" });
  expect(String(marked()[0]?.payload.serviceDate)).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  await shot(page, "3-home-not-available");

  await toggle.click();
  await expect(page.getByRole("dialog", { name: "Vehicle status" }).getByRole("heading", { name: "Mark the vehicle available?" })).toBeVisible();
  await page.getByRole("dialog", { name: "Vehicle status" }).getByRole("button", { name: "Yes, available" }).click();
  await expect.poll(() => marked().length).toBe(2);
  expect(marked()[1]?.payload).toMatchObject({ status: "available" });
  await expect(toggle).toHaveAttribute("aria-checked", "true");
});

test("on the trip a vehicle problem is Report problem, slid across, and only the report", async ({ page }) => {
  const started = { ...stop(1, "OUT0101"), startedAt: new Date().toISOString() };
  const server = await serve(page, [started, stop(2, "OUT0202")]);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Continue trip" })).toBeVisible();
  await expect(page.getByRole("switch", { name: "Available" })).toHaveCount(0);

  await page.getByRole("button", { name: "Report problem" }).first().click();
  const sheet = page.getByRole("dialog", { name: /problem/i });
  const row = sheet.getByRole("slider", { name: "Vehicle problem" });
  await expect(row).toBeVisible();
  await page.waitForTimeout(600);
  await shot(page, "4-on-trip-report-problem");
  const box = (await row.boundingBox())!;
  await page.mouse.move(box.x + 32, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width - 16, box.y + box.height / 2, { steps: 18 });
  await page.mouse.up();
  await expect(page.getByRole("dialog", { name: "Report sent" })).toContainText("Vehicle problem");
  await shot(page, "5-on-trip-report-sent");

  await expect.poll(() => server.commands.some((c) => c.kind === "delivery:ReportFault")).toBe(true);
  await page.waitForTimeout(500);
  expect(server.commands.some((c) => c.kind === "vehicle:SetDayStatus")).toBe(false);
});
