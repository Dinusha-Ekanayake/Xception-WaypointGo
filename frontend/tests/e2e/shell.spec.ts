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
