import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// R-IAM-40: connecting an AI assistant is the dispatcher's. A store manager sees
// no button unless an administrator allowed this person; the server answers.

const allow = (page: import("@playwright/test").Page, connect: boolean) =>
  page.route("**/api/mcp/access", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ connect }) }),
  );

test("by default the store manager has no assistant button", async ({ page }) => {
  await mockStore(page);
  await allow(page, false);
  await page.goto("/");
  await page.getByRole("button", { name: "Account: Nuwan Perera" }).click();
  const menu = page.getByRole("dialog", { name: "Account" });
  await expect(menu.getByRole("button", { name: "Sign out" })).toBeVisible();
  await expect(menu.getByRole("button", { name: "Connect AI assistant" })).toHaveCount(0);
});

test("a store manager an administrator allowed sees the button", async ({ page }) => {
  await mockStore(page);
  await allow(page, true);
  await page.route("**/api/oauth/protected-resource", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ resource: "https://waypointgo.live/mcp" }) }),
  );
  await page.goto("/");
  await page.getByRole("button", { name: "Account: Nuwan Perera" }).click();
  await expect(page.getByRole("dialog", { name: "Account" }).getByRole("button", { name: "Connect AI assistant" })).toBeVisible();
});
