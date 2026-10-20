import { expect, test, type Page } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// R-LOD-13: a trip is loaded only once its vehicle's driver has reported at
// the depot. The board lists arrived vehicles by default, says why a trip whose
// vehicle is not here cannot be taken, and a tick before the trip is taken says
// to take it first.

const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });

async function serve(page: Page, arrived: boolean) {
  const m = { ...manifest("trip-arrival", false, 1), status: "NOT_STARTED" as const, holder: null };
  const trips = board(m).map((t) => ({ ...t, driverAtDepotAt: arrived ? "2026-07-02T10:00:00Z" : null }));
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(trips));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/loading/trips/trip-arrival/manifest") return route.fulfill(json(m));
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  return m;
}

test("a vehicle not at the depot is left off the board, and taking its trip says why", async ({ page }) => {
  const m = await serve(page, false);
  await page.goto("/");
  await expect(page.getByText("No vehicle has arrived yet")).toBeVisible();

  // One filter at a time (the board never shows two picked): All lists every trip.
  await expect(page.getByRole("button", { name: /Arrived only/ })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: /^All \(/ }).click();
  await expect(page.getByRole("button", { name: /Arrived only/ })).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByText("Vehicle not at the depot yet").first()).toBeVisible();
  await page.getByRole("button", { name: "Take trip" }).first().click();
  await expect(page.getByText(`${m.vehicleId} has not arrived yet`)).toBeVisible();
});

test("once the vehicle is here its trip opens, and a tick before Start loading says to take the trip first", async ({ page }) => {
  await serve(page, true);
  await page.goto("/");
  await page.getByRole("button", { name: "Take trip" }).first().click();
  await page.getByRole("button", { name: /Mark item 1 of .* loaded/ }).first().click();
  await expect(page.getByText("Take the trip first")).toBeVisible();
});
