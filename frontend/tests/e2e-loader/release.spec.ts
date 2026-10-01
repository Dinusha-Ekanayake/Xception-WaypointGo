import { expect, test } from "@playwright/test";

test("a chilled trip releases after exactly the three confirmed checks", async ({ page }) => {
  let releaseCommand: unknown;

  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/api/session") {
      return json({
        userId: "device-user",
        displayName: "Depot supervisor",
        roles: ["loader"],
        scope: ["depot:KDY"],
        operator: { userId: "loader-user", displayName: "Isuru", employeeCode: "L-01", since: "2026-10-01T08:00:00Z" },
      });
    }
    if (pathname === "/api/loading/trips") {
      return json([{ tripId: "trip-test", vehicleId: "VEH043", tripNumber: 1, plannedDeparture: "16:30:00", status: "READY" }]);
    }
    if (pathname === "/api/reference/outlets") return json([]);
    if (pathname === "/api/loading/trips/trip-test/manifest") {
      return json({
        tripId: "trip-test", planId: "plan-test", planVersion: 1, vehicleId: "VEH043", tripNumber: 1,
        status: "READY", rowVersion: 4,
        lines: [{
          loadSequence: 1, stopSequence: 1, orderId: "order-test", outletId: "OUT001",
          temperature: "chilled", itemCount: 2, weightKg: "20", volumeM3: "0.2",
          status: "LOADED", loadedUnits: 2, attempt: 1,
        }],
      });
    }
    if (pathname === "/api/commands" && route.request().method() === "POST") {
      releaseCommand = route.request().postDataJSON();
      return json({ commandId: "receipt-test", kind: "loading:Release", replayed: false, result: { rowVersion: 5 } });
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: "Release vehicle" }).click();

  const dialog = page.getByRole("dialog", { name: "Confirm and release" });
  const release = dialog.getByRole("button", { name: "Release vehicle" });
  await expect(dialog.getByRole("group", { name: "Release checklist" })).toBeVisible();
  await expect(release).toBeDisabled();
  await expect(release).toHaveClass(/bg-go-mint/);
  await expect(dialog).not.toContainText(/reefer|4\s*°C|seal number/i);

  await dialog.getByRole("checkbox", { name: "Doors sealed" }).check();
  await dialog.getByRole("checkbox", { name: "Orders secured" }).check();
  await expect(release).toBeDisabled();
  await dialog.getByRole("checkbox", { name: "Driver present" }).check();
  await expect(release).toBeEnabled();
  await release.click();

  await expect(dialog).toHaveCount(0);
  expect(releaseCommand).toMatchObject({
    kind: "loading:Release", actingUserId: "loader-user", expectedVersion: 4,
    payload: { tripId: "trip-test", doorsSealed: true, ordersSecured: true, driverPresent: true },
  });
});
