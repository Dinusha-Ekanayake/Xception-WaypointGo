import { expect, test, type Page } from "@playwright/test";
import { draftPlan, serve } from "./mocks.ts";

// Once the day's plan is published it is the only plan: no saved candidates,
// no Compare. It changes only by moving a whole trip, picked from a pop-up with
// a search and filters, and only until 16:00 depot time on its day (R-PLN-43).

const published = () => ({ ...draftPlan(1), status: "PUBLISHED" as const, publishedAt: "2027-02-28T17:00:00Z" });

async function openTrip(page: Page): Promise<void> {
  await page.goto("/#/plan");
  await page.getByRole("tab", { name: /View plan/ }).click();
  await page.getByRole("region", { name: "Trips by vehicle" }).getByRole("button", { name: /VEH043 trip 1:/ }).click();
}

test("a published plan hides the candidate plans and moves a trip through the vehicle pop-up", async ({ page }) => {
  await serve(page, { published: published() });
  await openTrip(page);

  await expect(page.getByRole("button", { name: "Save snapshot" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Compare" })).toHaveCount(0);
  await expect(page.getByText("changes until 16:00")).toBeVisible();

  const drawer = page.getByRole("dialog", { name: /VEH043/ });
  await drawer.getByRole("button", { name: "Edit this trip" }).click();
  const window = page.getByRole("dialog", { name: "Edit trip" });
  await expect(window).toContainText("until 16:00 on its day");

  await window.getByRole("button", { name: "Move trip to" }).click();
  const picker = page.getByRole("dialog", { name: "Move the trip to" });
  const vehicles = picker.getByRole("list", { name: "Vehicles" });
  await expect(vehicles.getByRole("button")).toHaveCount(1);
  await expect(vehicles).toContainText("VEH044");
  await expect(vehicles).toContainText("Free");

  await picker.getByRole("searchbox", { name: "Find vehicle" }).fill("VEH999");
  await expect(picker).toContainText("No vehicle matches.");
  await picker.getByRole("searchbox", { name: "Find vehicle" }).fill("044");
  await picker.getByRole("button", { name: "Free", exact: true }).click();
  await expect(vehicles.getByRole("button")).toHaveCount(1);

  // A click anywhere outside closes the pop-up without choosing.
  await window.getByRole("heading").first().click();
  await expect(picker).toBeHidden();

  await window.getByRole("button", { name: "Move trip to" }).click();
  await page.getByRole("dialog", { name: "Move the trip to" }).getByRole("button", { name: /VEH044/ }).click();
  await expect(window.getByRole("button", { name: "Move to VEH044" })).toBeVisible();
});

test("after 16:00 on its day a published plan is final: no trip move, no revision", async ({ page }) => {
  // 16:30 in Colombo on the plan's service day.
  await page.clock.install({ time: new Date("2027-03-01T11:00:00Z") });
  await serve(page, { published: published() });
  await openTrip(page);

  await expect(page.getByText("final", { exact: false }).first()).toBeVisible();
  await expect(page.getByRole("dialog", { name: /VEH043/ }).getByRole("button", { name: "Edit this trip" })).toHaveCount(0);
  await page.keyboard.press("Escape");

  await page.getByRole("tab", { name: /Publish/ }).click();
  await expect(page.getByRole("button", { name: "Edit plan" })).toBeDisabled();
  await expect(page.getByText("This plan is final: changes closed at 16:00 on its day.")).toBeVisible();
});
