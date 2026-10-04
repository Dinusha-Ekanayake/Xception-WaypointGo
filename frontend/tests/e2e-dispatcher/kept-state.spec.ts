import { expect, test } from "@playwright/test";
import { issue, serve } from "./mocks.ts";

// UX polish 2: leaving a screen and coming back keeps its tab, as a native app
// does; a reload in the same tab keeps it too.

test("the issues tab is kept across Overview and back, and across a reload", async ({ page }) => {
  await serve(page, { issues: [issue(1, { severity: "LOW" })] });
  await page.goto("/#/issues");

  await page.getByRole("radio", { name: /^In progress/ }).click();
  await expect(page.getByRole("radio", { name: /^In progress/ })).toBeChecked();

  await page.evaluate(() => (window.location.hash = "/overview"));
  await expect(page.getByRole("radio", { name: /^In progress/ })).toHaveCount(0);
  await page.evaluate(() => (window.location.hash = "/issues"));
  await expect(page.getByRole("radio", { name: /^In progress/ })).toBeChecked();

  await page.reload();
  await expect(page.getByRole("radio", { name: /^In progress/ })).toBeChecked();
});
