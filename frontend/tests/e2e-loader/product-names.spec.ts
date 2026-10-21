import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// docs/issues/product-names/PLAN.md: the loader reads each item by the
// warehouse's name, still labelled inferred; an item it has not named keeps its id.

test("the manifest shows the warehouse's name of each item, labelled inferred", async ({ page }) => {
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  const asked: string[] = [];
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const current = manifest("trip-names", false, 1);
    if (url.pathname === "/api/session") return route.fulfill(json(SESSION));
    if (url.pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (url.pathname === "/api/reference/outlets") return route.fulfill(json([]));
    if (url.pathname === "/api/loading/trips/trip-names/manifest") return route.fulfill(json(current));
    if (url.pathname === "/api/warehouse/catalogue/names") {
      asked.push(url.searchParams.get("ids") ?? "");
      return route.fulfill(json({ "P-1": "Prawns 500g x 13" }));
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await expect(page.getByText("Prawns 500g x 13 (inferred)").first()).toBeVisible();
  await expect(page.getByText("P-2").first()).toBeVisible();
  expect(asked.join(",")).toContain("P-1");
});
