import { expect, test } from "@playwright/test";
import { LIVE_NOW, liveDay, serve } from "./mocks.ts";

// Figma "05 Live": needs you (189:20983), timeline (189:21127), the vehicle
// panel (189:21358, 189:21746) and the trip (189:21943), at 16:12 in Colombo.

test.beforeEach(async ({ page }) => {
  await page.clock.install({ time: new Date(LIVE_NOW) });
  await serve(page, liveDay());
  await page.goto("/#/live");
});

test("needs you puts a failed stop first, then windows about to close, and the trip board shows every vehicle", async ({ page }) => {
  const needs = page.getByRole("region", { name: "Needs you" });
  const cards = needs.getByRole("listitem");
  await expect(cards.nth(0)).toContainText("OUT010 · not delivered");
  await expect(cards.nth(1)).toContainText("48 min left");
  await expect(cards.nth(1)).toContainText("may miss its window");
  await expect(needs.getByText("VEH029 · driver offline")).toBeVisible();
  await expect(needs.getByText("no signal 12 min")).toBeVisible();
  // Messages to stores are drawn as designed but cannot be sent yet.
  await expect(cards.nth(1).getByRole("button", { name: "Notify store" })).toBeDisabled();

  const board = page.getByRole("region", { name: "Trip board" });
  await expect(board.getByRole("button")).toHaveCount(6);
  await expect(board.getByRole("button", { name: /^VEH029, Offline/ })).toContainText("Tech · Kurunegala");
  await expect(page.getByRole("region", { name: "Handled by the system" })).toContainText("closed on their own");
});

test("book make-up opens the issue on the Issues screen", async ({ page }) => {
  const card = page.getByRole("region", { name: "Needs you" }).getByRole("listitem").filter({ hasText: "1 unit missing" });
  await expect(card).toContainText("reported 16:02");
  await card.getByRole("button", { name: "Book make-up" }).click();
  await expect(page).toHaveURL(/#\/issues$/);
  await expect(page.getByText("1 unit missing").first()).toBeVisible();
});

test("the timeline lists each run with its trip, and a status filter narrows it", async ({ page }) => {
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  const timeline = page.getByRole("region", { name: "Timeline" });
  await expect(timeline).toContainText("Style · Matara · T2");
  await expect(timeline).toContainText("No signal · last seen 16:00");
  await expect(timeline).toContainText("Now 16:12");

  await page.getByRole("button", { name: "Offline 1" }).click();
  const road = page.getByRole("complementary", { name: "Vehicles on the road" });
  await expect(road.getByRole("button")).toHaveCount(1);
  await expect(road.getByRole("button").first()).toContainText("VEH029 · Tech · Kurunegala");
});

test("the trip shows its driver, limits, stops and activity, and an update cannot be sent yet", async ({ page }) => {
  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByRole("button", { name: "VEH020 · Style · Matara, At risk" }).click();

  await expect(page.getByRole("heading", { name: "VEH020 · Style · Matara" })).toBeVisible();
  await expect(page.getByText("Dilan R. (DRV-00133) · Trip 2 of 2 · left 14:20").first()).toBeVisible();
  await expect(page.getByText("112 / 480 min")).toBeVisible();
  await expect(page.getByText("7.5 / 34 m³")).toBeVisible();
  await expect(page.getByRole("row", { name: /OUT063/ })).toContainText("At risk");
  await expect(page.getByText("Left Kandy depot")).toBeVisible();
  await expect(page.getByRole("button", { name: "Send to 1 outlet" })).toBeDisabled();

  await page.getByRole("button", { name: "Back" }).click();
  await expect(page.getByRole("region", { name: "Timeline" })).toBeVisible();
});
