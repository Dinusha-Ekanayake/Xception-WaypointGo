import { expect, test } from "@playwright/test";

// R-ML-27: the administrator sees where the language models and Jev fail, per
// use, with the newest failures, their prompt version and their masked input.

const HEALTH = {
  days: 14,
  calls: [
    { purpose: "explain_plan", day: "2026-10-21", used: 6, unfaithful: 3, failed: 1, capped: 0, p50Millis: 800, p95Millis: 2400 },
    { purpose: "explain_order", day: "2026-10-21", used: 9, unfaithful: 0, failed: 0, capped: 1, p50Millis: 600, p95Millis: 900 },
  ],
  failures: [
    { at: "2026-10-21T06:30:00Z", purpose: "explain_plan", outcome: "unfaithful", model: "gpt-small", promptVersion: "3f2a9c1b7d04", millis: 1800, sentFacts: "{\"headline\":\"2 orders\",\"reason\":\"call [phone]\"}" },
  ],
  jev: { pending: 2, answered: 20, agreed: 18, failed: 1, byVersion: [{ promptVersion: "a1b2c3d4e5f6", model: "jev-1", answered: 15, agreed: 13, source: "issue" }, { promptVersion: "0f9e8d7c6b5a", model: "jev-1", answered: 5, agreed: 5, source: "report" }] },
  suggestions: [{ situation: "STORE_CLOSED", decided: 30, chosen: 25, chosenFirst: 21 }],
};

test("an administrator sees which AI use fails, the latest failure masked, and Jev's agreement", async ({ page }) => {
  await page.route("**/api/**", (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/api/session") {
      return json({ userId: "0198a000-0000-7000-8000-000000000001", displayName: "Admin One", roles: ["admin"], operator: null, scope: [] });
    }
    if (pathname === "/api/ml/ai-health") return json(HEALTH);
    return json({ items: [], nextCursor: null });
  });
  await page.goto("/#ai");

  const screen = page.getByRole("region", { name: "AI health" });
  const plans = screen.getByRole("row", { name: /Plan explanations/ });
  await expect(plans).toContainText("40%");
  await expect(screen.getByRole("row", { name: /Order explanations/ })).toContainText("10%");
  await expect(screen).toContainText("prompt 3f2a9c1b7d04");
  await screen.getByText("What was sent (masked)").click();
  await expect(screen).toContainText("call [phone]");
  await expect(screen).toContainText("18 of 20 agreed (90%)");
  // R-ML-33: issues and trip reports are measured apart.
  await expect(screen).toContainText("Issues · Prompt a1b2c3d4e5f6 · jev-1 · 13 of 15 agreed (87%)");
  await expect(screen).toContainText("Trip reports · Prompt 0f9e8d7c6b5a · jev-1 · 5 of 5 agreed (100%)");
  await expect(screen.getByRole("row", { name: /store closed/i })).toContainText("21 (70%)");
});
