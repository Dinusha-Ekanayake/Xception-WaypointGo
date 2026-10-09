import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

// Issue #177, R-IAM-40: the dispatcher connects an AI assistant from Settings. The
// server says whether this person may connect and names its own MCP address;
// with a no, or MCP not set up here, there is no button.

const MCP = "https://waypointgo.live/mcp";

test("settings offer the assistant connection with the server's address and the steps", async ({ page }) => {
  await serve(page);
  await page.route("**/api/mcp/access", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ connect: true }) }),
  );
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
  await page.route("**/api/mcp/access", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ connect: true }) }),
  );
  await page.route("**/api/oauth/protected-resource", (route) =>
    route.fulfill({ status: 404, contentType: "application/problem+json", body: JSON.stringify({ title: "Not found", status: 404 }) }),
  );
  await page.goto("/");
  await page.getByRole("button", { name: /^Settings: / }).first().click();
  const settings = page.getByRole("dialog", { name: "Settings" });
  await expect(settings.getByRole("button", { name: "Sign out" })).toBeVisible();
  await expect(settings.getByRole("button", { name: "Connect AI assistant" })).toHaveCount(0);
});

test("a dispatcher an administrator blocked sees no assistant button", async ({ page }) => {
  await serve(page);
  await page.route("**/api/mcp/access", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ connect: false }) }),
  );
  await page.route("**/api/oauth/protected-resource", (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ resource: MCP }) }),
  );
  await page.goto("/");
  await page.getByRole("button", { name: /^Settings: / }).first().click();
  const settings = page.getByRole("dialog", { name: "Settings" });
  await expect(settings.getByRole("button", { name: "Sign out" })).toBeVisible();
  await expect(settings.getByRole("button", { name: "Connect AI assistant" })).toHaveCount(0);
});
