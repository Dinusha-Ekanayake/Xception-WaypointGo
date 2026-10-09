import { expect, test } from "@playwright/test";
import { serve, startTrip } from "./mocks.ts";

// Figma "Driving mode", R-EXE-28: the driving screen comes on by itself once the
// vehicle moves, with the next stop and nothing to tap; with no location it
// never does, and the driver keeps the slider.

test.use({ geolocation: { latitude: 7.25, longitude: 80.6 }, permissions: ["geolocation"] });

test("moving turns the driving screen on by itself", async ({ page, context }) => {
  test.setTimeout(90_000);
  await serve(page, undefined, { askLocation: true });
  await page.goto("/");
  await startTrip(page);
  await page.getByRole("button", { name: "Share location" }).click();
  const screen = page.getByText("Driving · Controls Hidden");
  await expect(screen).toHaveCount(0);
  let lat = 7.25;
  await expect
    .poll(async () => {
      lat += 0.001;
      await context.setGeolocation({ latitude: lat, longitude: 80.6 });
      await page.waitForTimeout(2_500);
      return screen.count();
    }, { timeout: 60_000, intervals: [0] })
    .toBe(1);
  await expect(page.getByText("Slide to exit")).toBeVisible();
});

test("with no location the driving screen never comes on by itself", async ({ page }) => {
  await serve(page, undefined, { askLocation: true });
  await page.goto("/");
  await startTrip(page);
  await page.getByRole("button", { name: "Not now" }).click();
  await page.waitForTimeout(6_000);
  await expect(page.getByText("Driving · Controls Hidden")).toHaveCount(0);
});
