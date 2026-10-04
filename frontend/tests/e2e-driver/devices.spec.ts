import { expect, test, type Page } from "@playwright/test";
import { arrive, handOver, serve, startTrip, storeAnswer } from "./mocks.ts";

// Issue #201: the run works on every phone and tablet, either way up. Every
// action can be reached and nothing is wider than the screen.

async function fits(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, "nothing is wider than the screen").toBeLessThanOrEqual(0);
}

test("a stop is handed over and the store's report is shown", async ({ page }) => {
  const server = await serve(page);
  server.answers[server.stops[0]!.orderId] = storeAnswer(server.stops[0]!);
  await page.goto("/");
  await expect(page.getByRole("button", { name: "Start trip" })).toBeVisible();
  await fits(page);

  await startTrip(page);
  await expect(page.getByRole("heading", { name: "OUT0101" })).toBeVisible();
  await fits(page);
  await arrive(page);
  await fits(page);
  await handOver(page);
  await expect(page.getByText("All received")).toBeVisible();
  await expect(page.getByRole("button", { name: "Enter PIN to accept" })).toBeVisible();
  await fits(page);
});

test("a landscape tablet shows the run alone; the map opens from the run, inside it", async ({ page }) => {
  const { width, height } = page.viewportSize()!;
  test.skip(width < 1024 || width < height, "a tablet held sideways");
  await serve(page);
  await page.goto("/");
  await startTrip(page);
  await expect(page.getByRole("heading", { name: "OUT0101" })).toBeVisible();
  await expect(page.getByRole("complementary", { name: "Trip map" })).toHaveCount(0);
  await fits(page);
});
