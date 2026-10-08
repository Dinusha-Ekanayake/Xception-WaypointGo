import { expect, test } from "@playwright/test";
import { serve } from "./mocks.ts";

// Roadmap B-05: the pilot's business KPIs, recorded by the depot beside what the
// app counted, and a week recorded with only the figures typed in.

const today = new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Colombo" }).format(new Date());
const monday = (() => {
  const d = new Date(`${today}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
})();

test("the pilot KPIs show the depot's figures beside the app's, and a week is recorded", async ({ page }, testInfo) => {
  await serve(page);
  const sent: Array<{ kind: string; payload: Record<string, unknown> }> = [];
  await page.route("**/api/ml/kpis**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        depotCode: "Kandy",
        kpis: ["fresh_on_time_pct", "fresh_before_8_pct", "deferrals_per_run", "repeat_deferrals", "disputes_per_100", "write_off_lkr", "calls_per_trip", "adoption_pct"],
        countedByApp: ["fresh_on_time_pct", "fresh_before_8_pct", "adoption_pct"],
        weeks: [
          {
            weekStart: monday,
            recorded: {
              calls_per_trip: { value: 3.5, note: "Paper run sheets", enteredBy: "u-1", enteredAt: `${monday}T10:00:00Z` },
              fresh_on_time_pct: { value: 71.0, note: null, enteredBy: "u-1", enteredAt: `${monday}T10:00:00Z` },
            },
            counted: { fresh_on_time_pct: { value: 66.7, stops: 3 }, adoption_pct: { value: 75.0, stops: 4 } },
          },
        ],
      }),
    }),
  );
  await page.route("**/api/commands", async (route) => {
    const body = route.request().postDataJSON() as { kind: string; payload: Record<string, unknown> };
    if (body.kind !== "ml:RecordKpiWeek") return route.fallback();
    sent.push(body);
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ result: { recorded: ["calls_per_trip"] } }) });
  });

  await page.goto("/#/kpis");
  await expect(page.getByRole("heading", { name: "Pilot KPIs" })).toBeVisible();
  const table = page.getByRole("table");
  await expect(table).toContainText("Fresh on time");
  await expect(table).toContainText("71.0%");
  await expect(table).toContainText("App 66.7% · 3 stops");
  await expect(table).toContainText("App 75.0% · 4 stops");
  await expect(table).toContainText("3.5");
  await page.screenshot({ path: testInfo.outputPath("pilot-kpis.png"), fullPage: true });

  await page.getByRole("button", { name: "Record a week" }).click();
  const form = page.getByRole("form", { name: "Record a week" });
  await form.getByLabel("Calls per trip").fill("2.5");
  await form.getByLabel("Stops recorded in the app").fill("120");
  await expect(form.getByText("Stops recorded in the app is a share and cannot pass 100")).toBeVisible();
  await expect(form.getByRole("button", { name: "Record" })).toBeDisabled();
  await form.getByLabel("Stops recorded in the app").fill("");
  await form.getByLabel("Where the figures come from (optional)").fill("Phone log, week of the pilot");
  await form.getByRole("button", { name: "Record" }).click();

  await expect.poll(() => sent.length).toBe(1);
  expect(sent[0]!.payload).toEqual({
    depotCode: "Kandy",
    weekStart: monday,
    values: { calls_per_trip: 2.5 },
    note: "Phone log, week of the pilot",
  });
});
