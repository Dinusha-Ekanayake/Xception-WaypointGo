import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

// Issue #177: the dispatcher connects an AI assistant from Settings. The server
// names its own MCP address; where MCP is not set up it refuses, and there is
// no button to a connection that cannot work.

const MCP = "https://waypointgo.live/mcp";

test("settings offer the assistant connection with the server's address and the steps", async ({ page }) => {
  await serve(page);
  await page.route("**/api/oauth/protected-resource", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ resource: MCP }) }),
  );
  await page.route("**/api/mcp/connections", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify([]) }),
  );
  await page.goto("/");
  await page.getByRole("button", { name: /^Settings: / }).first().click();
  const settings = page.getByRole("dialog", { name: "Settings" });
  await settings.getByRole("button", { name: "Connect AI assistant" }).click();

  const sheet = page.getByRole("dialog", { name: "Connect AI assistant" });
  await expect(sheet.getByText(MCP)).toBeVisible();
  await expect(sheet).toContainText("Your password is never shared with the assistant.");
  await expect(sheet).toContainText("No assistant is connected to your account.");
});

test("with MCP not set up here, settings show no assistant button", async ({ page }) => {
  await serve(page);
  await page.route("**/api/oauth/protected-resource", (route) =>
    route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ title: "Not found", status: 404 }) }),
  );
  await page.goto("/");
  await page.getByRole("button", { name: /^Settings: / }).first().click();
  const settings = page.getByRole("dialog", { name: "Settings" });
  await expect(settings.getByRole("button", { name: "Sign out" })).toBeVisible();
  await expect(settings.getByRole("button", { name: "Connect AI assistant" })).toHaveCount(0);
});
