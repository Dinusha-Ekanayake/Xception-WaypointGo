import { test, expect } from "@playwright/test";

test("super admin creates an admin; demo persists and records the reason", async ({ page }) => {
  await page.goto("/super-admin/demo");
  await page.getByRole("button", { name: "Administrators SA", exact: true }).click();
  await page.getByRole("button", { name: "Create admin", exact: true }).click();
  await page.getByLabel("Full name").fill("Test Administrator");
  await page.getByLabel("Email address").fill("new.admin@example.com");
  await page.getByLabel("Reason for change").fill("Manage the Kandy team");
  await page.getByRole("button", { name: "Create demo account" }).click();
  await expect(page.getByText("new.admin@example.com")).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "Administrators SA", exact: true }).click();
  await expect(page.getByText("new.admin@example.com")).toBeVisible();
  await page.getByRole("button", { name: "Activity log", exact: true }).click();
  await expect(page.getByText("Manage the Kandy team")).toBeVisible();
});

test("admin only assigns field roles; permission changes require a reason and survive reload", async ({ page }) => {
  await page.goto("/admin/demo");
  await expect(page.getByRole("button", { name: "Administrators SA", exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "Add team member" }).click();
  await expect(page.getByRole("combobox", { name: "Role", exact: true }).locator("option")).toHaveCount(4);
  await page.getByRole("button", { name: "Close account dialog" }).click();
  await page.getByRole("button", { name: "Role permissions", exact: true }).click();
  const toggle = page.getByRole("switch", { name: "Publish delivery plans" });
  await toggle.click();
  await page.getByRole("button", { name: "Save permissions", exact: true }).click();
  await expect(page.getByText("Add a reason before saving permission changes.")).toBeVisible();
  await page.getByLabel("Reason for change").fill("Publication restricted during training");
  await page.getByRole("button", { name: "Save permissions", exact: true }).click();
  await expect(page.getByText("Review your permission changes")).toHaveCount(0);
  await page.reload();
  await page.getByRole("button", { name: "Role permissions", exact: true }).click();
  await expect(page.getByRole("switch", { name: "Publish delivery plans" })).toHaveAttribute("aria-checked", "false");
});

test("search, scoped assignment, suspension and individual overrides work", async ({ page }) => {
  await page.goto("/admin/demo");
  await page.getByRole("button", { name: "Team members", exact: true }).click();
  await page.getByRole("textbox", { name: "Search people" }).fill("Nethmi");
  await page.getByRole("button", { name: "Manage Nethmi Fernando" }).click();
  await page.getByLabel("Account status").selectOption("Suspended");
  await page.getByLabel("Assign depot").selectOption("Kandy");
  await page.getByRole("button", { name: "Remove Peliyagoda" }).click();
  await page.getByRole("button", { name: "Individual permissions" }).click();
  await page.getByLabel("Build and adjust allocations override").selectOption("deny");
  await page.getByLabel("Reason for change").fill("Assignment review");
  await page.getByRole("button", { name: "Save account changes" }).click();
  await expect(page.getByRole("cell", { name: "Suspended", exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "Kandy", exact: true })).toBeVisible();
  await expect(page.getByText("Custom overrides")).toBeVisible();
});

test("live super admin route rejects an admin session and never shows demo accounts", async ({ page }) => {
  await page.route("**/api/session", route => route.fulfill({ json: { userId: "a", displayName: "Administrator", roles: ["admin"], scope: [] } }));
  await page.goto("/super-admin");
  await expect(page.getByRole("heading", { name: "Access restricted" })).toBeVisible();
  await expect(page.getByText("Amaya Perera")).toHaveCount(0);
  await page.goto("/admin");
  await expect(page.getByRole("heading", { name: "Account administration is not connected yet" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Add team member" })).toHaveCount(0);
});

test("desktop and mobile layouts fit and navigation works", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.setViewportSize({ width: 1440, height: 1080 });
  await page.goto("/super-admin/demo");
  await expect(page.getByRole("heading", { name: "Access, made clear." })).toBeVisible();
  await page.screenshot({ path: "test-results/admin-desktop.png", fullPage: true, animations: "disabled" });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "Open navigation" }).click();
  await page.getByRole("button", { name: "Role permissions", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Dispatcher permissions" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: "test-results/admin-mobile.png", fullPage: true, animations: "disabled" });
  expect(errors).toEqual([]);
});
