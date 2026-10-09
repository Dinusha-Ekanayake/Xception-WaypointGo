import { expect, test } from "@playwright/test";
import { forecast, serve } from "./mocks.ts";

// Roadmap T2-02: booking a vehicle into the workshop on a day of a week the
// forecast already fills warns, and still lets it go; advice only.

function isoDay(d: Date): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(d);
}

test("a workshop day in a short week warns, and the booking still goes", async ({ page }) => {
  // The forecast's first week starts the Monday two weeks from now, and its chilled demand fills the reefers.
  const today = new Date(`${isoDay(new Date())}T00:00:00Z`);
  const monday = new Date(today.getTime() + (14 - ((today.getUTCDay() + 6) % 7)) * 86_400_000);
  const start = monday.toISOString().slice(0, 10);
  const base = forecast();
  const week = {
    ...base.weeks[0]!,
    weekStart: start,
    brands: [{ brandCode: "Fresh", totalM3: "300", chilledM3: "380" }],
    totalM3: "300",
    chilledM3: "380",
    capacity: { vehicles: 2, refrigeratedVehicles: 1, fleetM3: "801.6", refrigeratedM3: "400.8" },
  };
  const desk = await serve(page, { forecast: { ...base, weeks: [week] } });
  await page.goto("/#/vehicles");

  await page.getByRole("cell", { name: "VEH043", exact: true }).click();
  const drawer = page.getByRole("dialog", { name: "Vehicle VEH043" });
  await drawer.getByRole("button", { name: "Send to workshop" }).click();
  // Today is outside the forecast: nothing to warn about.
  await expect(drawer.getByText("That week is forecast short")).toHaveCount(0);

  const wednesday = new Date(monday.getTime() + 2 * 86_400_000).toISOString().slice(0, 10);
  await drawer.getByLabel("Which day?").fill(wednesday);
  await expect(drawer.getByText("That week is forecast short")).toBeVisible();
  await expect(drawer).toContainText("380 m³ of chilled demand; without VEH043 that day the week has 334 m³ of refrigerated capacity.");

  await drawer.getByLabel(/Why is VEH043 going to the workshop/).fill("Brake noise reported by the driver");
  await drawer.getByRole("button", { name: "Send VEH043 to workshop" }).click();
  await expect.poll(() => desk.commands.find((c) => c.kind === "vehicle:SetDayStatus")?.payload).toMatchObject({
    vehicleId: "VEH043", status: "in_workshop", serviceDate: wednesday,
  });
});
