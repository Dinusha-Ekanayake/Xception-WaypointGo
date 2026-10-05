import { expect, test } from "@playwright/test";
import { mockStore, shift } from "./mocks.ts";

// Issue #199 (R-ORD-13, R-ORD-15): a store is told which nearby day a trip
// already serves its district, beside the day it picked and why it is better,
// and one tap orders for that day instead. A day the outlook calls busy or at
// risk is never offered.

test("a Tech store can move its order onto a day a trip already serves", async ({ page }) => {
  const busy = shift(3);
  const { sent } = await mockStore(page, { rideAlong: [{ date: busy, stopsBooked: 2 }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();

  const card = page.getByRole("region", { name: "A better day for this order" });
  await expect(card).toContainText("2 other stores booked");
  await expect(card).toContainText("No other store booked yet");
  await card.getByRole("button", { name: /^Move to / }).click();
  // On the chosen day itself there is nothing better to suggest; the move is confirmed and can be undone.
  await expect(card).toHaveCount(0);
  await expect(page.getByRole("status").filter({ hasText: "to share the trip already going to" })).toBeVisible();

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
  await expect(page.getByRole("region", { name: "A better day for this order" })).toHaveCount(0);
});

test("a day the outlook calls at risk is never offered", async ({ page }) => {
  const busy = shift(3);
  await mockStore(page, { rideAlong: [{ date: busy, stopsBooked: 2 }], outlook: { [busy]: "AT_RISK" } });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();
  await expect(page.getByRole("heading", { name: "Place order" })).toBeVisible();
  await expect(page.getByRole("region", { name: "A better day for this order" })).toHaveCount(0);
});

test("a day checked for room says so, and that the room is estimated (R-ORD-14)", async ({ page }) => {
  await mockStore(page, { rideAlong: [{ date: shift(3), stopsBooked: 2 }], roomChecked: true });
  await page.goto("/");
  await page.getByRole("button", { name: /Place order for/ }).click();

  const card = page.getByRole("region", { name: "A better day for this order" });
  await expect(card).toContainText("2 other stores booked · room on the vehicle");
  await expect(card).toContainText("Room is estimated from your usual order.");
});
