import { expect, test } from "@playwright/test";

test("an offline loader check syncs once under the recorded operator", async ({ page, context }) => {
  let checkedOnServer = false;
  let syncBatch: { operations: Array<{ command: { commandId: string; actingUserId?: string; kind: string } }> } | null = null;
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname === "/api/session") {
      return route.fulfill(json({
        userId: "device-user", displayName: "Depot supervisor", roles: ["loader"], scope: ["depot:KDY"],
        operator: { userId: "loader-user", displayName: "Isuru", employeeCode: "L-01", since: "2026-10-01T08:00:00Z" },
      }));
    }
    if (pathname === "/api/loading/trips") {
      return route.fulfill(json([{ tripId: "trip-offline", vehicleId: "VEH043", tripNumber: 1, plannedDeparture: "16:30:00", status: "IN_PROGRESS" }]));
    }
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/loading/trips/trip-offline/manifest") {
      return route.fulfill(json({
        tripId: "trip-offline", planId: "plan-test", planVersion: 1, vehicleId: "VEH043", tripNumber: 1,
        status: "IN_PROGRESS", rowVersion: checkedOnServer ? 5 : 4,
        lines: [{
          loadSequence: 1, stopSequence: 1, orderId: "order-offline", outletId: "OUT001",
          temperature: "chilled", itemCount: 2, weightKg: "20", volumeM3: "0.2",
          status: checkedOnServer ? "LOADED" : "PENDING", loadedUnits: checkedOnServer ? 2 : 0, attempt: 1,
        }],
      }));
    }
    if (pathname === "/api/sync" && route.request().method() === "POST") {
      syncBatch = route.request().postDataJSON();
      checkedOnServer = true;
      return route.fulfill(json({ results: syncBatch!.operations.map((operation) => ({
        operationId: operation.command.commandId,
        sequence: 1, status: "APPLIED", problemCode: null, detail: null, replayed: false,
      })) }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  const markLoaded = page.getByRole("button", { name: /Mark order .* loaded/ });
  await expect(markLoaded).toBeVisible();
  await context.setOffline(true);
  await markLoaded.click();
  await expect(page.getByRole("status").filter({ hasText: "1 saved on this device" }).first()).toBeVisible();
  expect(syncBatch).toBeNull();

  await context.setOffline(false);
  await expect.poll(() => syncBatch?.operations.length).toBe(1);
  await expect(page.getByRole("status").filter({ hasText: "1 saved on this device" })).toHaveCount(0);
  expect(syncBatch!.operations[0]!.command).toMatchObject({ kind: "loading:Check", actingUserId: "loader-user" });
});
