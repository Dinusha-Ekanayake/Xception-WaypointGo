import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

// R-FLT-06 (docs/issues/vehicle-broken/PLAN.md): the "Vehicle OK" switch marks
// the vehicle broken or OK for today, so Planning moves its trips and the
// dispatcher is told. A tap asks first, so an accidental one sends nothing.

test("the Vehicle OK switch asks before it marks the vehicle broken, and Cancel sends nothing", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  const toggle = page.getByRole("switch", { name: "Vehicle OK" });
  await expect(toggle).toHaveAttribute("aria-checked", "true");

  await toggle.click();
  const sheet = page.getByRole("dialog", { name: "Vehicle status" });
  await expect(sheet.getByRole("heading", { name: "Mark the vehicle broken?" })).toBeVisible();
  await expect(sheet).toContainText("its trips today move to other vehicles in a new plan");
  await sheet.getByRole("button", { name: "Cancel" }).click();
  await expect(sheet).toBeHidden();
  await expect(toggle).toHaveAttribute("aria-checked", "true");

  await toggle.click();
  await page.getByRole("dialog", { name: "Vehicle status" }).getByRole("button", { name: "Yes, it is broken" }).click();
  const marked = () => server.commands.filter((c) => c.kind === "vehicle:SetDayStatus");
  await expect.poll(() => marked().length).toBe(1);
  expect(marked()[0]?.payload).toMatchObject({ status: "unavailable" });
  expect(String(marked()[0]?.payload.serviceDate)).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  await expect(toggle).toHaveAttribute("aria-checked", "false");

  // OK again: back in use at once.
  await toggle.click();
  await expect(page.getByRole("dialog", { name: "Vehicle status" }).getByRole("heading", { name: "Mark the vehicle OK again?" })).toBeVisible();
  await page.getByRole("dialog", { name: "Vehicle status" }).getByRole("button", { name: "Yes, it is OK" }).click();
  await expect.poll(() => marked().length).toBe(2);
  expect(marked()[1]?.payload).toMatchObject({ status: "available" });
});
