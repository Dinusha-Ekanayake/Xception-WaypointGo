import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

const RESOURCE = "https://preview.waypointgo.live/mcp";

test("connect AI shows the shared MCP address and the steps, only where MCP is on", async ({ page }) => {
  await serve(page);
  await page.route("**/.well-known/oauth-protected-resource/mcp", (route) =>
    route.fulfill({ json: { resource: RESOURCE, authorization_servers: ["https://preview.waypointgo.live"] } }),
  );
  await page.goto("/");
  await page.getByRole("button", { name: "Connect AI assistant" }).first().click();
  const sheet = page.getByRole("dialog", { name: "Connect AI assistant" });
  await expect(sheet).toContainText(RESOURCE);
  await expect(sheet).toContainText("Add custom connector");
  await sheet.getByRole("button", { name: "Done" }).click();
  await expect(sheet).toBeHidden();
});

test("with MCP off the button is not shown", async ({ page }) => {
  await serve(page);
  await page.route("**/.well-known/oauth-protected-resource/mcp", (route) => route.fulfill({ status: 404, json: {} }));
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Switch user" }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Connect AI assistant" })).toHaveCount(0);
});
