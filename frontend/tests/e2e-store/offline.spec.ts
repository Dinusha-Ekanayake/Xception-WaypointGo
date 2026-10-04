import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// Issue #201: a store phone reloaded with no network still opens on the
// outlet's orders, kept on the device, and the top bar says from when.

test("reloaded offline, the store opens on its kept orders and says from when", async ({ page, context }) => {
  await mockStore(page);
  let outage = false;
  // Registered last, so it is asked first: in the outage every API call fails as with no signal.
  await page.route("**/api/**", (route) => (outage ? route.abort("internetdisconnected") : route.fallback()));

  await page.goto("/");
  await page.getByRole("navigation", { name: "Store" }).getByRole("button", { name: /^Orders/ }).click();
  await expect(page.getByRole("button", { name: /ORD0092336/ })).toBeVisible();
  await page.evaluate(() => navigator.serviceWorker.register("/sw.js").then(() => navigator.serviceWorker.ready).then(() => undefined));
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);

  outage = true;
  await context.setOffline(true);
  await page.reload();
  await expect(page.getByText(/Offline · showing \d\d:\d\d/).first()).toBeVisible();
  await page.getByRole("navigation", { name: "Store" }).getByRole("button", { name: /^Orders/ }).click();
  await expect(page.getByRole("button", { name: /ORD0092336/ })).toBeVisible();
  await context.setOffline(false);
});
