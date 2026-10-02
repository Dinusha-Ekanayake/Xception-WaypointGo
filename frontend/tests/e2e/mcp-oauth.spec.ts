import { test, expect } from "@playwright/test";
const query = "?client_id=client&redirect_uri=https%3A%2F%2Fassistant.test%2Fcallback&response_type=code&code_challenge=challenge&code_challenge_method=S256&state=state&resource=https%3A%2F%2Fwaypoint.test%2Fmcp";
test("MCP consent identifies the client and submits credentials only on approval", async ({ page }) => {
  let approvals = 0;
  await page.route("**/api/oauth/authorize**", async route => {
    if (route.request().method() === "GET") return route.fulfill({ json: { clientName: "Example assistant", redirectHost: "assistant.test" } });
    approvals++;
    expect(route.request().postDataJSON()).toMatchObject({ email: "reader@example.test", password: "test-secret", resource: "https://waypoint.test/mcp" });
    expect(route.request().url()).not.toContain("test-secret");
    return route.fulfill({ status: 401, json: { code: "UNAUTHENTICATED" } });
  });
  await page.goto("/oauth/authorize" + query);
  await expect(page.getByText("Example assistant", { exact: true })).toBeVisible();
  await expect(page.getByText("assistant.test", { exact: true })).toBeVisible();
  expect(approvals).toBe(0);
  await page.getByLabel("Email", { exact: true }).fill("reader@example.test");
  await page.getByLabel("Password", { exact: true }).fill("test-secret");
  await page.getByRole("button", { name: "Authorize read-only access" }).click();
  await expect(page.locator("main").getByRole("alert")).toHaveText("Email or password is incorrect.");
  await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
  expect(approvals).toBe(1);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: "/tmp/mcp87-consent-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("No access was granted");
  expect(approvals).toBe(1);
});
test("invalid authorization request has no credentials form", async ({ page }) => {
  await page.route("**/api/oauth/authorize**", route => route.fulfill({ status: 422, json: {} }));
  await page.goto("/oauth/authorize");
  await expect(page.locator("main").getByRole("alert")).toContainText("invalid or unavailable");
  await expect(page.getByLabel("Password", { exact: true })).toHaveCount(0);
});
test("successful consent returns to the registered client", async ({ page }) => {
  await page.route("https://assistant.test/**", route => route.fulfill({ body: "Connected" }));
  await page.route("**/api/oauth/authorize**", route => route.fulfill({ json: route.request().method() === "GET" ? { clientName: "Example", redirectHost: "assistant.test" } : { redirectTo: "https://assistant.test/callback?code=opaque&state=state" } }));
  await page.goto("/oauth/authorize" + query);
  await page.getByLabel("Email", { exact: true }).fill("reader@example.test");
  await page.getByLabel("Password", { exact: true }).fill("test-secret");
  await page.getByRole("button", { name: "Authorize read-only access" }).click();
  await expect(page).toHaveURL("https://assistant.test/callback?code=opaque&state=state");
});

test("offline OAuth navigation never falls back to the signed-in shell", async ({ page, context }) => {
  await page.goto("/");
  await page.evaluate(async () => { await navigator.serviceWorker.register("/sw.js"); await navigator.serviceWorker.ready; });
  await page.reload();
  await context.setOffline(true);
  try {
    await page.goto("/oauth/authorize").catch(() => {});
    await expect(page.getByRole("heading", { name: "Connect your assistant" })).toHaveCount(0);
    expect(await page.evaluate(() => document.body.innerText).catch(() => "")).not.toContain("Order. Plan. Deliver.");
  } finally { await context.setOffline(false); }
});
