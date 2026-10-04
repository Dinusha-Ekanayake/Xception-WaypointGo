import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// UX polish: every store dialog closes with Escape, keeps focus inside while
// open, gives it back to what opened it, and holds the page still behind it.
// With reduced motion nothing animates.

async function openProfile(page: import("@playwright/test").Page) {
  await mockStore(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Account: Nuwan Perera" }).click();
  await page.getByRole("dialog", { name: "Account" }).getByRole("button", { name: /Your profile/ }).click();
  return page.getByRole("dialog", { name: "Your profile" });
}

test("a store dialog keeps focus inside, closes with Escape and locks the page behind it", async ({ page }) => {
  const dialog = await openProfile(page);
  await expect(dialog).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe("hidden");
  expect(await dialog.evaluate((d) => d.contains(document.activeElement))).toBe(true);

  for (let i = 0; i < 8; i += 1) await page.keyboard.press("Tab");
  expect(await dialog.evaluate((d) => d.contains(document.activeElement))).toBe(true);

  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe("");
});

test("with reduced motion a dialog appears without animating", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  const dialog = await openProfile(page);
  await expect(dialog).toBeVisible();
  // Reduced motion shortens every animation to an instant (0.01 ms); none may last longer.
  const longest = await page.evaluate(() =>
    Math.max(0, ...document.getAnimations().map((a) => Number(a.effect?.getComputedTiming().duration) || 0)),
  );
  expect(longest).toBeLessThanOrEqual(1);
});
