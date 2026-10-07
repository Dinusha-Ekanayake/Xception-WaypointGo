import { expect, test, type Page } from "@playwright/test";

// Roadmap MF-04, R-ML-16: each late-risk model shows how its own predictions
// did over the last eight finished weeks beside the built-in estimate, and the
// screen says so when that evidence cannot be read.

const MODELS = [
  { name: "risk", version: "2", kind: "delivery_risk", status: "REGISTERED", metrics: {}, trainedFrom: "2026-01-01", trainedTo: "2026-08-31", activatedAt: null, retiredReason: null, rowVersion: 1 },
  { name: "risk", version: "1", kind: "delivery_risk", status: "ACTIVE", metrics: {}, trainedFrom: "2025-06-01", trainedTo: "2026-05-31", activatedAt: "2026-08-01T00:00:00Z", retiredReason: null, rowVersion: 2 },
];

const BACKTEST = {
  from: "2026-08-10",
  to: "2026-10-04",
  minLabels: 200,
  models: [
    { modelLabel: "deterministic", stops: 900, labelled: 860, coverage: "0.9556", brier: "0.2100", logLoss: "0.61", calibrationSlope: "0.9", calibrationIntercept: "0.1", verdict: "BASELINE", weeks: [] },
    { modelLabel: "risk@1", stops: 250, labelled: 230, coverage: "0.9200", brier: "0.1500", logLoss: "0.45", calibrationSlope: "1.0", calibrationIntercept: "0", verdict: "BETTER", weeks: [] },
  ],
};

async function signedInAdmin(page: Page, backtestUp: boolean) {
  await page.route("**/api/**", (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/api/session") {
      return json({ userId: "0198a000-0000-7000-8000-000000000001", displayName: "Admin One", roles: ["admin"], operator: null, scope: [] });
    }
    if (pathname === "/api/ml/models") return json(MODELS);
    if (pathname === "/api/ml/models/backtest") {
      return backtestUp ? json(BACKTEST) : json({ type: "about:blank", title: "Service unavailable", status: 503 }, 503);
    }
    return json({ items: [], nextCursor: null });
  });
}

test("an administrator sees each late-risk model's backtest beside the built-in estimate", async ({ page }) => {
  await signedInAdmin(page, true);
  await page.goto("/#models");
  await expect(page.getByTestId("backtest-built-in")).toHaveText(
    "Built-in estimate: Last 8 weeks · 860 of 900 stops labelled (96%) · late error 0.210 · The figure every model is judged against",
  );
  const used = page.getByRole("listitem", { name: "risk 1" });
  await expect(used.getByTestId("backtest")).toHaveText(
    "Last 8 weeks · 230 of 250 stops labelled (92%) · late error 0.150 (estimate 0.210) · Better than the built-in estimate",
  );
  const ready = page.getByRole("listitem", { name: "risk 2" });
  await expect(ready.getByTestId("backtest")).toHaveText("Last 8 weeks · no predictions yet, so nothing to judge");
  await expect(ready.getByRole("button", { name: "Use this model" })).toBeVisible();
});

test("with the backtest unreadable the screen says so and still lets a model be chosen", async ({ page }) => {
  await signedInAdmin(page, false);
  await page.goto("/#models");
  await expect(page.getByTestId("backtest-built-in")).toContainText("Backtest unavailable right now");
  await expect(page.getByRole("listitem", { name: "risk 2" }).getByRole("button", { name: "Use this model" })).toBeVisible();
});
