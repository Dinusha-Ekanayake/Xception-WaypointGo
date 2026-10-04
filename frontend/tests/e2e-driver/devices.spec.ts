import { expect, test, type Page } from "@playwright/test";
import { arrive, openForm, serve, sign, startTrip } from "./mocks.ts";

// Issue #201: the run works on every phone and tablet, either way up. Every
// action can be reached and nothing is wider than the screen.

async function fits(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, "nothing is wider than the screen").toBeLessThanOrEqual(0);
}

test("a stop is delivered from start to saved", async ({ page }) => {
  await serve(page);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start run" })).toBeVisible();
  await fits(page);

  await startTrip(page);
  await expect(page.getByRole("heading", { name: "OUT0101" })).toBeVisible();
  await fits(page);
  await arrive(page);
  await openForm(page);
  await expect(page.getByText("Stop 01 of 02 · Delivery report")).toBeVisible();
  await fits(page);

  await page.getByLabel("Received by").fill("Kumari Silva");
  await sign(page);
  await expect(page.getByText("Signed", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Confirm" }).click();
  await expect(page.getByRole("dialog", { name: "Delivery confirmed" })).toBeVisible();
});

test("a landscape tablet shows the trip map beside the run", async ({ page }) => {
  const { width, height } = page.viewportSize()!;
  test.skip(width < 1024 || width < height, "beside the run only on a tablet held sideways");
  await serve(page);
  await page.goto("/");
  await startTrip(page);
  await expect(page.getByRole("complementary", { name: "Trip map" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "OUT0101" })).toBeVisible();
});
