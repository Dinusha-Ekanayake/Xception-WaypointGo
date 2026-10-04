import { expect, test } from "@playwright/test";
import { mockStore, shift } from "./mocks.ts";

// Issue #199 (R-ORD-13): a Tech store is told which nearby day a trip already
// serves its district, and one tap orders for that day instead.

test("a Tech store can move its order onto a day a trip already serves", async ({ page }) => {
  const busy = shift(3);
  const { sent } = await mockStore(page, { rideAlong: [{ date: busy, stopsBooked: 2 }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();

  const card = page.getByRole("region", { name: "Shared trip" });
  await expect(card).toContainText("2 other stores booked");
  await card.getByRole("button", { name: /^Deliver / }).click();
  // On the chosen day itself there is nothing better to suggest.
  await expect(card).toHaveCount(0);

  await page.getByRole("searchbox", { name: "Add an item" }).fill("bas");
  await page.getByRole("button", { name: "Add Basmati rice 5 kg" }).click();
  await page.getByRole("button", { name: "Submit order" }).click();
  await expect(page.getByRole("dialog", { name: "Order sent" })).toBeVisible();
  const place = sent.find((c) => c.kind === "order:Place");
  expect((place?.payload as { requestedDate: string }).requestedDate).toBe(busy);
});

test("when the suggestion cannot be read, the screen says so", async ({ page }) => {
  await mockStore(page, { rideAlong: "down" });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await expect(page.getByRole("status").filter({ hasText: "Shared trip suggestions are unavailable" })).toBeVisible();
});

test("a Fresh store is never offered another day", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await expect(page.getByRole("heading", { name: "Place order" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Shared trip" })).toHaveCount(0);
});

test("a day checked for room says so, and that the room is estimated (R-ORD-14)", async ({ page }) => {
  await mockStore(page, { rideAlong: [{ date: shift(3), stopsBooked: 2 }], roomChecked: true });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();

  const card = page.getByRole("region", { name: "Shared trip" });
  await expect(card).toContainText("2 other stores booked · room on the vehicle");
  await expect(card).toContainText("Room is estimated from your usual order.");
});
