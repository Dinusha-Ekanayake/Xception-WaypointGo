import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

test("the loader switches to Sinhala and Tamil, and the choice survives a reload", async ({ page }) => {
  const current = manifest("trip-lang", false, 2);
  const json = (body: unknown) => ({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  await page.route("**/api/**", async (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname === "/api/session") return route.fulfill(json(SESSION));
    if (pathname === "/api/loading/trips") return route.fulfill(json(board(current)));
    if (pathname === "/api/reference/outlets") return route.fulfill(json([]));
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();

  await page.getByLabel("Language").first().selectOption("si");
  await expect(page.getByRole("heading", { name: "අද රාත්‍රී පිටත්වීම්" })).toBeVisible();
  await expect(page.getByRole("button", { name: "දිගටම කරන්න" })).toBeVisible();
  await expect(page.locator("html")).toHaveAttribute("lang", "si");

  await page.reload();
  await expect(page.getByRole("heading", { name: "අද රාත්‍රී පිටත්වීම්" })).toBeVisible();

  await page.getByLabel("භාෂාව").first().selectOption("ta");
  await expect(page.getByRole("heading", { name: "இன்றிரவு புறப்பாடுகள்" })).toBeVisible();
});
