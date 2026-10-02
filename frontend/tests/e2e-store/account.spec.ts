import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// Figma "Overlay · Account menu": who is signed in, for which outlet, dock and
// window, and Sign out. Staff ID and the sign-in time are left out by decision.

test("the account menu says who is signed in and where, and closes with Escape", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  const trigger = page.getByRole("button", { name: "Account: Nuwan Perera" });
  await trigger.click();

  const menu = page.getByRole("dialog", { name: "Account" });
  await expect(menu).toContainText("Nuwan Perera");
  for (const [label, value] of [
    ["Role", "Store manager"],
    ["Brand", "FRESH"],
    ["Outlet", "Kadugannawa · OUT085"],
    ["Receiving", "Rear dock · 05:00-07:30"],
    ["Depot", "KDY"],
  ]) {
    await expect(menu.getByRole("definition").filter({ hasText: value! })).toBeVisible();
    await expect(menu.getByRole("term").filter({ hasText: label! })).toBeVisible();
  }
  await expect(menu).not.toContainText("Staff ID");
  await expect(menu).not.toContainText("Signed in");

  await page.keyboard.press("Escape");
  await expect(menu).toBeHidden();
  await expect(trigger).toBeFocused();
});

test("Sign out in the account menu signs the manager out", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Account: Nuwan Perera" }).click();
  await page.getByRole("dialog", { name: "Account" }).getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByText("You are signed out.")).toBeVisible();
});
