import { expect, test } from "@playwright/test";
import { serve, stop, today } from "./mocks.ts";

// "My trips" (agreed by @jv-ransika, 2026-10-23): the driver's own trips of the
// last 7 days, from the profile sheet; read only, online only.

const yesterday = () => new Date(Date.parse(`${today()}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10);

test("the profile sheet opens My trips: yesterday's trip with what happened at each store", async ({ page }) => {
  await serve(page);
  await page.route("**/api/execution/run-sheets**", (route) => {
    const date = new URL(route.request().url()).searchParams.get("date");
    if (date === today()) return route.fallback();
    if (date !== yesterday()) return route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
    const done = { ...stop(1, "OUT0301"), tripId: "trip-y", outcome: "DELIVERED", startedAt: `${yesterday()}T00:10:00Z`, completedAt: `${yesterday()}T00:42:00Z`, deliveredUnits: 40 };
    const skipped = { ...stop(2, "OUT0302"), tripId: "trip-y", outcome: "SKIPPED" };
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([{ vehicleId: "VEH043", serviceDate: yesterday(), stops: [done, skipped] }]) });
  });
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();

  await page.getByRole("button", { name: /Settings:/ }).click();
  await page.getByRole("dialog", { name: "Settings" }).getByRole("button", { name: "My trips" }).click();
  await expect(page.getByRole("heading", { name: "My trips" })).toBeVisible();
  const card = page.getByRole("button", { name: /VEH043 · Trip 1/ }).last();
  await expect(card).toContainText("2 stops · 1 delivered · 1 skipped");
  await expect(card).toContainText("40 units handed over");
  await expect(card).toContainText("05:40-06:12");
  await card.click();
  const stops = page.getByRole("list", { name: "Stops" });
  await expect(stops).toContainText("1. OUT0301");
  await expect(stops).toContainText("Delivered · 06:12 · 40 units");
  await expect(stops).toContainText("Skipped");
  if (process.env.SHOTS_DIR) await page.screenshot({ path: `${process.env.SHOTS_DIR}/28-driver-my-trips.png` });

  await page.getByRole("button", { name: "Back" }).click();
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
});

test("with no signal, My trips says past trips need a connection", async ({ page, context }) => {
  const server = await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
  await server.goOffline(context);
  await page.getByRole("button", { name: /Settings:/ }).click();
  await page.getByRole("dialog", { name: "Settings" }).getByRole("button", { name: "My trips" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Past trips need a connection" })).toBeVisible();
});
