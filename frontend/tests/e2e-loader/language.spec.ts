import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// Figma "08 Loader · Phone" Settings: Language (සිං / த / EN), kept per device.
// The theme is only the sun and moon button in the top bar, also kept per device.

test("settings switch the loader's language, the top bar the theme, and both survive a reload", async ({ page }) => {
  const current = manifest("trip-lang", false, 2);
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  const workspace = page.locator(".loader-workspace");

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
  // Dark is the default on a new device.
  await expect(workspace).toHaveAttribute("data-theme", "dark");

  await page.getByRole("button", { name: "Use the light theme" }).first().click();
  await expect(workspace).toHaveAttribute("data-theme", "light");
  await page.getByRole("button", { name: "Settings", exact: true }).first().click();
  await expect(page.getByRole("heading", { name: "Settings" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Light", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "සිංහල" }).click();
  await expect(page.locator("html")).toHaveAttribute("lang", "si");
  await page.getByRole("button", { name: "වෙනස්කම් යොදන්න" }).click();
  await expect(page.getByRole("heading", { name: "අද රාත්‍රී පිටත්වීම්" })).toBeVisible();

  await page.reload();
  await expect(page.getByRole("heading", { name: "අද රාත්‍රී පිටත්වීම්" })).toBeVisible();
  await expect(workspace).toHaveAttribute("data-theme", "light");

  // The moon button in the top bar turns the theme back to dark.
  await page.getByRole("button", { name: "අඳුරු තේමාව භාවිත කරන්න" }).first().click();
  await expect(workspace).toHaveAttribute("data-theme", "dark");

  await page.getByRole("button", { name: "සැකසුම්" }).first().click();
  await page.getByRole("button", { name: "தமிழ்" }).click();
  await page.getByRole("button", { name: "மாற்றங்களைப் பயன்படுத்து" }).click();
  await expect(page.getByRole("heading", { name: "இன்றிரவு புறப்பாடுகள்" })).toBeVisible();
});
