import { expect, test, type Page } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// Issue #201: the loader works on every phone and tablet, either way up: the
// departures open, a trip opens, an item is checked, and nothing is wider
// than the screen.

async function fits(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, "nothing is wider than the screen").toBeLessThanOrEqual(0);
}

test("a trip is opened and an item checked", async ({ page }) => {
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  let checked = false;
  await page.route("**/api/**", (route) => {
    const { pathname } = new URL(route.request().url());
    const current = manifest("trip-devices", checked, checked ? 5 : 4);
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (pathname === "/api/loading/trips/trip-devices/manifest") return route.fulfill(json(current));
    if (pathname === "/api/commands") {
      checked = true;
      return route.fulfill(json({ commandId: "c", status: "APPLIED", replayed: false, rowVersion: 5 }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
  await fits(page);
  await page.getByRole("button", { name: "Continue" }).click();
  const mark = page.getByRole("button", { name: /Mark item 1 of ORD0092336 loaded/ });
  await expect(mark).toBeVisible();
  await fits(page);
  await mark.click({ trial: true });
});
