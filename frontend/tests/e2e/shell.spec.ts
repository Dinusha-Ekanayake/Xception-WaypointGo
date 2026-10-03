import { expect, test } from "@playwright/test";

// The smallest proof the production build serves: the shell loads with its
// security headers and the Content-Security-Policy blocks nothing it needs.
test("the app shell loads under its Content-Security-Policy", async ({ page }) => {
  const violations: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error" && /Content Security Policy/i.test(message.text())) {
      violations.push(message.text());
    }
  });

  // Signed out, as every browser suite answers its own API: with no backend the
  // page would keep retrying its session and never settle (#120).
  await page.route("**/api/**", (route) =>
    route.fulfill({
      status: 401,
      contentType: "application/problem+json",
      body: JSON.stringify({ type: "about:blank", title: "Unauthenticated", status: 401, code: "UNAUTHENTICATED", detail: "Sign in", violations: [] }),
    }));
  const response = await page.goto("/");
  expect(response?.status()).toBe(200);
  expect(response?.headers()["content-security-policy"]).toContain("frame-ancestors 'none'");
  await page.waitForLoadState("networkidle");
  expect(violations).toEqual([]);
});

test("the health route answers without the backend", async ({ request }) => {
  const response = await request.get("/healthz");
  expect(response.ok()).toBe(true);
  expect(await response.json()).toEqual({ status: "UP" });
});

// The admin role gets the sample console on every address, not only on
// admin-preview: production served the "not built yet" placeholder instead.
test("a signed-in admin reaches the sample console on any address", async ({ page }) => {
  await page.route("**/api/**", (route) => {
    const { pathname } = new URL(route.request().url());
    const body = pathname === "/api/session"
      ? { userId: "0198a000-0000-7000-8000-000000000001", displayName: "Admin One", roles: ["admin"], operator: null, scope: [] }
      : { items: [], nextCursor: null };
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });

  await page.goto("/");
  await expect(page.locator("main.access-demo")).toBeVisible();
  await expect(page.getByText("The admin console is not built yet.")).toHaveCount(0);
});
