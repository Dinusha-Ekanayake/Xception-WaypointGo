import { expect, test } from "@playwright/test";
import { forecast, serve } from "./mocks.ts";

test("the forecast shows ten weeks against the fleet, with the peak and what it needs", async ({ page }) => {
  await serve(page);
  await page.goto("/#/forecast");

  await expect(page.getByRole("heading", { name: "Forecast" })).toBeVisible();
  await expect(page.getByText("Peak week · Wk 2 Poson")).toBeVisible();
  await expect(page.getByText("612 m³").first()).toBeVisible();

  const chart = page.getByRole("region", { name: "Weekly demand" });
  await expect(chart.getByRole("img", { name: /Weekly demand for the next 10 weeks/ })).toBeVisible();
  await expect(chart).toContainText("datathon-task2a@2026.1");

  const actions = page.getByRole("region", { name: "Suggested actions" });
  await expect(actions).toContainText("Wk 2: keep all refrigerated vehicles out");
  await expect(actions).toContainText("Wk 3 has only 3 operating days");

  const needs = page.getByRole("region", { name: "What the busiest days need" });
  await expect(needs.getByRole("row", { name: /Wk 2/ })).toBeVisible();
});

test("a brand filter shows only that brand's demand", async ({ page }) => {
  await serve(page);
  await page.goto("/#/forecast");

  await page.getByRole("tab", { name: "Style" }).click();
  await expect(page.getByText("Next week · Wk 1")).toBeVisible();
  await expect(page.getByText("80 m³").first()).toBeVisible();
  await expect(page.getByText("0% chilled · Style 100%")).toBeVisible();
});

test("a forecast from the fallback says the model is not answering", async ({ page }) => {
  await serve(page, { forecast: forecast({ modelLabel: "deterministic", degraded: true }) });
  await page.goto("/#/forecast");

  await expect(page.getByText("The demand model is not answering")).toBeVisible();
  await expect(page.getByRole("region", { name: "Weekly demand" })).toContainText("Recent weekday averages");
});

test("before the first run the screen says there is no forecast yet", async ({ page }) => {
  await serve(page, { forecast: forecast({ status: "NONE", modelLabel: null, generatedAt: null }) });
  await page.goto("/#/forecast");

  await expect(page.getByText("No forecast yet")).toBeVisible();
  await expect(page.getByRole("region", { name: "Weekly demand" })).toHaveCount(0);
});

test("the last and next run read in depot time, with a countdown to the next", async ({ page }) => {
  await page.clock.install({ time: new Date("2027-02-28T18:06:19Z") });
  await serve(page);
  await page.goto("/#/forecast");

  const runs = page.getByRole("region", { name: "Forecast runs" });
  await expect(runs).toContainText("Mon 22 Feb · 9:30 AM");
  await expect(runs).toContainText("datathon-task2a@2026.1");
  await expect(runs).toContainText("Mon 1 Mar · 12:00 AM");
  await expect(runs.getByRole("timer")).toHaveText("in 23:41");
});

test("when the run is due the screen says so and shows the new forecast once it lands", async ({ page }) => {
  await page.clock.install({ time: new Date("2027-02-28T18:29:50Z") });
  const desk = await serve(page, { forecast: forecast({ status: "NONE", modelLabel: null, generatedAt: null, weeks: [] }) });
  await page.goto("/#/forecast");

  const runs = page.getByRole("region", { name: "Forecast runs" });
  await expect(runs).toContainText("Not run yet");
  await expect(page.getByText("No forecast yet")).toBeVisible();

  await page.clock.fastForward(15_000);
  await expect(runs).toContainText("Running now");

  desk.forecast = forecast({ generatedAt: "2027-02-28T18:30:04Z", nextRunAt: "2027-03-07T18:30:00Z" });
  await page.clock.fastForward(20_000);
  await expect(runs).toContainText("Mon 1 Mar · 12:00 AM");
  await expect(runs).toContainText("Mon 8 Mar · 12:00 AM");
  await expect(page.getByText("No forecast yet")).toHaveCount(0);
});
