import { expect, test } from "@playwright/test";
import { mockStore, PRODUCTS } from "./mocks.ts";

// docs/issues/product-names/PLAN.md: the store searches by the warehouse's name
// and reads it on the order, still labelled inferred.

test("the store finds a product by the warehouse's name and the order reads it by that name", async ({ page }) => {
  await mockStore(page);
  const prawns = { ...PRODUCTS[0]!, productId: "C32_FRESH_019_AMB", name: "Prawns 500g x 13", category: "Meat & Fish" };
  await page.route("**/api/warehouse/catalogue?**", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ items: [...PRODUCTS, prawns], nextCursor: null }) }));
  await page.route("**/api/warehouse/catalogue/names?**", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ C32_FRESH_019_AMB: "Prawns 500g x 13" }) }));
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await page.getByRole("searchbox", { name: "Add an item" }).fill("prawns");
  const matches = page.getByRole("list", { name: "Catalogue matches" });
  await expect(matches).toContainText("Prawns 500g x 13");
  await expect(matches).toContainText("inferred");
  await page.getByRole("button", { name: "Add Prawns 500g x 13" }).click();
  // The row reads the name once, with "inferred" beneath it; the note names it too, never by its id.
  // A screen reader hears it as inferred on the amount's buttons.
  await expect(page.getByRole("group", { name: "Prawns 500g x 13 (inferred)" })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: "Prawns 500g x 13 added" })).toBeVisible();
  await expect(page.getByText("C32_FRESH_019_AMB")).toHaveCount(0);
});
