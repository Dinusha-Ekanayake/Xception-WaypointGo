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
  await page.getByRole("button", { name: "Unlock loader" }).click();
  await expect(page.getByText("That PIN didn't match.")).toBeVisible();
  await pin.fill(process.env.LOADER_LIVE_PIN!);
  await page.getByRole("button", { name: "Unlock loader" }).click();

  await expect(page.getByRole("heading", { name: "Tonight's departures" })).toBeVisible();
  const tripAction = page.getByRole("button", { name: /^(Take trip|Continue)$/ });
  await expect(tripAction).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("loader-phone-board.png") });
  await tripAction.click();
  const start = page.getByRole("button", { name: "Start loading" });
  if (await start.isVisible()) await start.click();
  const stop = page.getByRole("button", { name: /Stop 01 of 01/ });
  if (await stop.getAttribute("aria-expanded") === "false") await stop.click();
  const markLoaded = page.getByRole("button", { name: /Mark order .* loaded/ });
  const undoLoaded = page.getByRole("button", { name: /Undo loaded, order/ });
  if (await undoLoaded.isVisible()) {
    await undoLoaded.click();
    await expect(markLoaded).toBeEnabled();
  }
  await expect(markLoaded).toBeEnabled();

  await context.setOffline(true);
  await markLoaded.click();
  await expect(page.getByRole("status").filter({ hasText: "1 saved on this device" }).first()).toBeVisible();
  await context.setOffline(false);
  await expect(page.getByRole("status").filter({ hasText: "1 saved on this device" })).toHaveCount(0);
  await expect(page.getByText("1 of 1 orders loaded")).toBeVisible();
  await expect(page.getByRole("button", { name: "Lock loader" })).toBeEnabled();

  await page.getByRole("button", { name: "Release vehicle" }).click();
  const dialog = page.getByRole("dialog", { name: "Confirm and release" });
  const release = dialog.getByRole("button", { name: "Release vehicle" });
  await expect(release).toBeDisabled();
  await dialog.getByRole("checkbox", { name: "Doors sealed" }).check();
  await dialog.getByRole("checkbox", { name: "Orders secured" }).check();
  await dialog.getByRole("checkbox", { name: "Driver present" }).check();
  await expect(release).toBeEnabled();
  await release.click();
  await expect(page.getByText("Vehicle released")).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("loader-phone-released.png") });

  await page.getByRole("button", { name: "Lock loader" }).click();
  await expect(page.getByRole("heading", { name: "Who's loading?" })).toBeVisible();
  await page.setViewportSize({ width: 768, height: 1024 });
  await page.screenshot({ path: testInfo.outputPath("loader-tablet-locked.png") });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(768);
});
