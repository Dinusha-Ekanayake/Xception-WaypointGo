import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

// The "Available" switch reports the vehicle to the dispatcher, who stops
// planning it while it is in the workshop. A tap asks first, so an accidental
// one sends nothing.

test("the Available switch asks before it reports, and Cancel sends nothing", async ({ page }) => {
  const server = await serve(page);
  await page.goto("/");
  const toggle = page.getByRole("switch", { name: "Available" });
  await expect(toggle).toHaveAttribute("aria-checked", "true");

  await toggle.click();
  const sheet = page.getByRole("dialog", { name: "Vehicle status" });
  await expect(sheet.getByRole("heading", { name: "Mark the vehicle in the workshop?" })).toBeVisible();
  await sheet.getByRole("button", { name: "Cancel" }).click();
  await expect(sheet).toBeHidden();
  await expect(toggle).toHaveAttribute("aria-checked", "true");

  await toggle.click();
  await page.getByRole("dialog", { name: "Vehicle status" }).getByRole("button", { name: "Yes, in the workshop" }).click();
  await expect.poll(() => server.commands.filter((c) => c.kind === "delivery:ReportVehicleStatus").length).toBe(1);
  expect(server.commands.filter((c) => c.kind === "delivery:ReportVehicleStatus")[0]?.payload).toMatchObject({ status: "at_workshop" });
});
