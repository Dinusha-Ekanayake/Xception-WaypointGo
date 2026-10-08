import { expect, test } from "@playwright/test";
import { mockStore } from "./mocks.ts";

// Roadmap FF-07: the store sees who is at its door. The server answers the
// driver's photo on the day of the delivery only; otherwise, or with no photo,
// the circle shows the driver's initials.

// A 1x1 PNG.
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=", "base64");

test("the driver's photo is shown beside their name", async ({ page }) => {
  await mockStore(page);
  await page.route("**/api/execution/deliveries/*/driver-photo", (route) => route.fulfill({ status: 200, contentType: "image/png", body: PNG }));
  await page.goto("/");
  await expect(page.getByRole("img", { name: "Rashmika Dilshan" }).first()).toBeVisible();
});

test("with no photo the driver's initials are shown instead", async ({ page }) => {
  await mockStore(page);
  await page.goto("/");
  await expect(page.getByText("Rashmika Dilshan").first()).toBeVisible();
  await expect(page.getByText("RD", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("img", { name: "Rashmika Dilshan" })).toHaveCount(0);
});
