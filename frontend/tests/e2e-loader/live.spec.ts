import { expect, test } from "@playwright/test";

const ready = ["LOADER_LIVE_BASE_URL", "LOADER_LIVE_EMAIL", "LOADER_LIVE_PASSWORD", "LOADER_LIVE_PIN"]
  .every((name) => Boolean(process.env[name]));

test.skip(!ready, "Requires an isolated seeded Loader database and LOADER_LIVE_* variables");

test("a real shared-device loader signs in, saves offline, syncs and releases", async ({ page, context }, testInfo) => {
  await page.goto("/");
  await page.getByRole("textbox", { name: "Email" }).fill(process.env.LOADER_LIVE_EMAIL!);
  await page.getByLabel("Password").fill(process.env.LOADER_LIVE_PASSWORD!);
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page.getByRole("heading", { name: "Who's loading?" })).toBeVisible();
  await page.getByRole("button", { name: /Isuru Test/ }).click();
  const pin = page.getByLabel("4-digit PIN");
  await pin.fill("1111");
  await page.getByRole("button", { name: "Confirm" }).click();
  await expect(page.getByText(/^Incorrect PIN\./)).toBeVisible();
  await pin.fill(process.env.LOADER_LIVE_PIN!);
  await page.getByRole("button", { name: "Confirm" }).click();

  await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
  const tripAction = page.getByRole("button", { name: /^(Take trip|Continue)$/ });
  await expect(tripAction).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("loader-phone-board.png") });
  await tripAction.click();
  const start = page.getByRole("button", { name: "Start loading" });
  if (await start.isVisible()) await start.click();
  const stop = page.getByRole("button", { name: /^Stop 01/ });
  if (await stop.getAttribute("aria-expanded") === "false") await stop.click();
  // Item by item (decision 2026-10-01): start from a clean sheet, then tick each item.
  const undo = page.getByRole("button", { name: /^Undo loaded, item/ });
  while (await undo.count() > 0) {
    await undo.first().click();
    await page.getByRole("button", { name: "Done" }).click({ trial: false }).catch(() => undefined);
  }
  const markLoaded = page.getByRole("button", { name: /^Mark item \d+ of .* loaded$/ });
  await expect(markLoaded.first()).toBeEnabled();

  await context.setOffline(true);
  await markLoaded.first().click();
  await expect(page.getByRole("status").filter({ hasText: "1 saved on this device" }).first()).toBeVisible();
  await context.setOffline(false);
  await expect(page.getByRole("status").filter({ hasText: "1 saved on this device" })).toHaveCount(0);
  while (await markLoaded.count() > 0) await markLoaded.first().click();
  await expect(markLoaded).toHaveCount(0);
  await expect(page.getByText(/All loaded/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Lock loader" })).toBeEnabled();

  await page.getByRole("button", { name: "Release vehicle" }).click();
  const dialog = page.getByRole("dialog", { name: "Confirm and release" });
  const release = dialog.getByRole("button", { name: "Hold to release vehicle" });
  await expect(release).toBeDisabled();
  await dialog.getByRole("checkbox", { name: "Doors sealed" }).check();
  await dialog.getByRole("checkbox", { name: "Orders secured" }).check();
  await dialog.getByRole("checkbox", { name: "Driver present" }).check();
  await expect(release).toBeEnabled();
  await release.hover();
  await page.mouse.down();
  await page.waitForTimeout(1500);
  await page.mouse.up();
  await expect(page.getByRole("heading", { name: / released$/ })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("loader-phone-released.png") });
  await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible({ timeout: 6000 });

  await page.getByRole("button", { name: "Lock loader" }).click();
  await expect(page.getByRole("heading", { name: "Device locked" })).toBeVisible();
  await page.setViewportSize({ width: 768, height: 1024 });
  await page.screenshot({ path: testInfo.outputPath("loader-tablet-locked.png") });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(768);
});
