import { expect, test } from "@playwright/test";
import { serve, startTrip, takeVehicle } from "./mocks.ts";

// Issue #161 on the live run (#114): positions while a run is open, through the
// offline queue, and a map that hands off to the phone's maps app only for an
// exact store location.

test.use({ geolocation: { latitude: 7.25, longitude: 80.6 }, permissions: ["geolocation"] });

test("points recorded with no signal survive a reload and are sent when the signal returns", async ({ page, context }) => {
  const server = await serve(page);
  await page.clock.install();
  await page.goto("/");
  await takeVehicle(page);
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await server.goOffline(context);

  await startTrip(page, true);
  await expect(page.getByText("Next • stop 01 of 02")).toBeVisible();
  // The flush after a minute puts the batch in the phone's queue.
  await page.clock.runFor(61_000);
  expect(server.commands.filter((c) => c.kind === "delivery:RecordPositions")).toHaveLength(0);

  await page.reload();
  await expect(page.getByRole("status").filter({ hasText: /^Offline · / })).toBeVisible();
  await server.goOnline(context);
  await expect.poll(() => server.commands.filter((c) => c.kind === "delivery:RecordPositions").length, { timeout: 15_000 }).toBeGreaterThan(0);
  const sent = server.commands.find((c) => c.kind === "delivery:RecordPositions")!;
  expect((sent.payload as { vehicleId: string }).vehicleId).toBe("VEH043");
});

test("Navigate appears only for a store with an exact location", async ({ page }) => {
  await serve(page);
  await page.goto("/");
  await takeVehicle(page);
  await startTrip(page, true);
  await page.getByRole("button", { name: "Open map" }).click();
  await expect(page.getByRole("link", { name: "Navigate to OUT0101" })).toHaveAttribute("href", /destination=7\.291,80\.633/);
});

test("a store with only a district location gets no Navigate, and declining location never blocks the run", async ({ page }) => {
  const server = await serve(page);
  server.stops[0]!.outletId = "OUT0303";
  await page.goto("/");
  await takeVehicle(page);
  await startTrip(page);
  await page.getByRole("button", { name: "Open map" }).click();
  await expect(page.getByText(/No exact location for this store yet/)).toBeVisible();
  await expect(page.getByRole("link", { name: /Navigate to/ })).toHaveCount(0);
  await expect(page.getByText("Location off · the dispatcher sees your stops only")).toBeVisible();
});
