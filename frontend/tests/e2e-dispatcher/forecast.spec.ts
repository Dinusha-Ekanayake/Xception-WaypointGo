import { expect, test } from "@playwright/test";
import { forecast, serve } from "./mocks.ts";

test("the forecast shows ten weeks against the fleet, with the peak and what it needs", async ({ page }) => {
  await serve(page);
  await page.goto("/#/forecast");

  await expect(page.getByRole("heading", { name: "Forecast", exact: true })).toBeVisible();
  await expect(page.getByText("Peak week · Wk 2 Poson")).toBeVisible();
  await expect(page.getByText("612 m³").first()).toBeVisible();

  const chart = page.getByRole("region", { name: "Weekly demand" });
  await expect(chart.getByRole("img", { name: /Weekly demand for the next 10 weeks/ })).toBeVisible();
  // Issue #119: the error the model measured, not per brand; the model's own name stays off the screen.
  await expect(page.getByText("Forecast error · ±4% total · ±4% chilled")).toBeVisible();
  await expect(page.locator("main")).not.toContainText("datathon");

  const actions = page.getByRole("region", { name: "Suggested actions" });
  await expect(actions).toContainText("Wk 2: keep all refrigerated vehicles out");
  await expect(actions).toContainText("Wk 3 has only 3 operating days");

  const needs = page.getByRole("region", { name: "What the busiest days need" });
  await expect(needs.getByRole("row", { name: /Wk 2/ })).toBeVisible();
});

// Roadmap MF-06: with enough finished weeks each week has a likely range, on the Next and Peak week cards.
test("the next and peak weeks say how much is likely", async ({ page }) => {
  const base = forecast();
  const ranged = {
    ...base,
    weeks: base.weeks.map((w) => ({
      ...w,
      brands: w.brands.map((b) => ({ ...b, lowM3: String(Number(b.totalM3) * 0.9), highM3: String(Number(b.totalM3) * 1.2) })),
    })),
  };
  await serve(page, { forecast: ranged });
  await page.goto("/#/forecast");
  const peak = Number(base.weeks[1]!.totalM3);
  const ranges = page.getByTestId("likely-range");
  await expect(ranges).toHaveCount(2);
  await expect(ranges.nth(1)).toHaveText(`likely ${Math.round(peak * 0.9)} to ${Math.round(peak * 1.2)}`);
  await expect(page.getByText("+24% on a typical week")).toBeVisible();
});

test("without enough finished weeks the forecast gives no range", async ({ page }) => {
  await serve(page);
  await page.goto("/#/forecast");
  await expect(page.getByText("Peak week · Wk 2 Poson")).toBeVisible();
  await expect(page.getByTestId("likely-range")).toHaveCount(0);
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
  await expect(page.getByRole("region", { name: "Forecast runs" })).toContainText("Recent averages");
  await expect(page.getByText("Recent averages · forecast error not measured")).toBeVisible();
});

test("before the first run the screen says there is no forecast yet", async ({ page }) => {
  await serve(page, { forecast: forecast({ status: "NONE", modelLabel: null, generatedAt: null }) });
  await page.goto("/#/forecast");

  await expect(page.getByText("No forecast yet")).toBeVisible();
  await expect(page.getByRole("region", { name: "Weekly demand" })).toHaveCount(0);
});

test("the last and next run read in depot time, with a countdown to the next", async ({ page }) => {
  // The time is held still: a running clock made the countdown depend on how fast the page loaded.
  await page.clock.setFixedTime(new Date("2027-02-28T18:06:19Z"));
  await serve(page);
  await page.goto("/#/forecast");

  const runs = page.getByRole("region", { name: "Forecast runs" });
  await expect(runs).toContainText("Mon 22 Feb · 09:30");
  await expect(runs).toContainText("Mon 1 Mar · 00:00");
  await expect(runs.getByRole("timer")).toHaveText(/^in 23:4[01]$/);
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
  await expect(runs).toContainText("Mon 1 Mar · 00:00");
  await expect(runs).toContainText("Mon 8 Mar · 00:00");
  await expect(page.getByText("No forecast yet")).toHaveCount(0);
});

test("with the fleet far above demand the bars follow demand and the fleet is stated, with the vehicles a day needs", async ({ page }) => {
  const f = forecast();
  f.weeks = f.weeks.map((w) => ({
    ...w,
    capacity: { vehicles: 38, refrigeratedVehicles: 9, fleetM3: String(2025 * w.operatingDays), refrigeratedM3: String(415 * w.operatingDays) },
  }));
  await serve(page, { forecast: f });
  await page.goto("/#/forecast");

  const chart = page.getByRole("region", { name: "Weekly demand" });
  await expect(chart).toContainText("fleet ≈ 12,150 m³/wk, above the scale · peak week uses 5%");
  await expect(chart).toContainText("Share of refrigerated capacity, peak 16%");
  await expect(chart).toContainText("Capacity 2,490 m³/wk · 9 vehicles");
  await expect(chart).toContainText("2 of 9");
});

test("a decision about a short week is recorded with its reason and listed (roadmap T2-01)", async ({ page }) => {
  const desk = await serve(page);
  await page.goto("/#/forecast");

  const card = page.getByRole("region", { name: "Decisions" });
  await expect(card).toContainText("No decisions recorded for the weeks ahead.");
  await card.getByRole("button", { name: "Record a decision" }).click();

  const form = card.getByRole("form", { name: "Record a decision" });
  await form.getByLabel("Week").selectOption({ index: 1 });
  await form.getByLabel("How many vehicles").fill("2");
  // Without a reason nothing can be recorded (rule 8).
  await expect(form.getByRole("button", { name: "Record" })).toBeDisabled();
  await form.getByLabel("Why").fill("Five reefers in service before Poson");
  await form.getByRole("button", { name: "Record" }).click();

  await expect(card).toContainText("Hire 2 refrigerated vehicles · week");
  await expect(card).toContainText("Five reefers in service before Poson");
  const sent = desk.commands.find((c) => c.kind === "ml:RecordCapacityDecision");
  expect(sent).toMatchObject({ expectedVersion: null, payload: { action: "HIRE_VEHICLES", vehicles: 2, refrigerated: true } });
});

test("how the forecast did on the last finished weeks, beside what was ordered (T2-03)", async ({ page }) => {
  const week = (isoWeek: number, brands: Array<[string, number, number]>) => ({
    isoYear: 2027, isoWeek, modelLabel: "datathon-task2a@2026.1", forecastM3: 0, orderedM3: 0, wape: null,
    brands: brands.map(([brandCode, forecastM3, orderedM3]) => ({ brandCode, forecastM3, orderedM3 })),
  });
  await serve(page, { accuracy: { weeks: [week(7, [["Fresh", 410, 400], ["Style", 95, 100]]), week(8, [["Fresh", 300, 420]])], wape: null } });
  await page.goto("/#/forecast");

  const panel = page.getByRole("region", { name: "How the forecast did" });
  await expect(panel).toContainText("Off by 15% over the last 2 weeks");
  await expect(panel.getByRole("row", { name: /15 Feb/ })).toContainText("505 m³");
  await expect(panel.getByRole("row", { name: /15 Feb/ })).toContainText("3%");
  // A week off by more than a quarter is marked.
  await expect(panel.getByRole("row", { name: /22 Feb/ })).toContainText("29%");
  await expect(page.locator("main")).not.toContainText("datathon");
});

test("the forecast is compared with the 8-week average on the same weeks (T2-03)", async ({ page }) => {
  const week = (isoWeek: number, brands: Array<[string, number, number, number | null]>) => ({
    isoYear: 2027, isoWeek, modelLabel: "datathon-task2a@2026.1", forecastM3: 0, orderedM3: 0, wape: null,
    brands: brands.map(([brandCode, forecastM3, orderedM3, baselineM3]) => ({ brandCode, forecastM3, orderedM3, baselineM3 })),
  });
  await serve(page, { accuracy: { weeks: [week(7, [["Fresh", 410, 400, 340]]), week(8, [["Fresh", 400, 420, 360]])], wape: null } });
  await page.goto("/#/forecast");

  const panel = page.getByRole("region", { name: "How the forecast did" });
  // Forecast (10 + 20) / 820 = 4%; the average (60 + 60) / 820 = 15%.
  await expect(panel.getByTestId("forecast-versus-average")).toHaveText("Better than the 8-week average: off by 4% against 15% on the same weeks");
  await expect(panel.getByRole("row", { name: /15 Feb/ })).toContainText("15%");
});
