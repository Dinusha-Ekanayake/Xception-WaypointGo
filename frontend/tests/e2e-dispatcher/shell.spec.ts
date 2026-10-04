import { expect, test } from "@playwright/test";
import { SESSION, draftPlan, serve } from "./mocks.ts";

// One sidebar on every screen (Figma "Shell / Sidebar" and its rail): folding
// it is the dispatcher's choice, kept across screens and reloads, and the rail
// keeps everything the full bar has.

test("the sidebar folds to the rail on any screen, stays folded, and the rail still reaches Settings and the account", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/overview");
  const sidebar = page.getByRole("complementary", { name: "Sidebar" });
  await expect(sidebar.getByRole("link", { name: "Overview" })).toContainText("Overview");
  // The open draft shows on Plan as a word, not a made-up deadline.
  await expect(sidebar.getByRole("link", { name: /Plan/ })).toContainText("Draft");

  await sidebar.getByRole("button", { name: "Fold the sidebar" }).click();
  await expect(sidebar.getByRole("link", { name: "Overview" })).not.toContainText("Overview");
  await expect(sidebar.getByRole("link", { name: "Plan, Draft" })).toBeVisible();

  await sidebar.getByRole("link", { name: "Plan, Draft" }).click();
  await expect(page).toHaveURL(/#\/plan/);
  await expect(sidebar.getByRole("button", { name: "Expand the sidebar" })).toBeVisible();
  await page.reload();
  await expect(sidebar.getByRole("button", { name: "Expand the sidebar" })).toBeVisible();

  // The picture opens Settings, as in every role, with the account and Sign out below it.
  await sidebar.getByRole("button", { name: /^Settings: / }).click();
  const account = page.getByRole("dialog", { name: "Settings" });
  await expect(account).toContainText("Dinusha Bawantha");
  await expect(account).toContainText("Kandy");
  await expect(account.getByRole("button", { name: "Sign out" })).toBeVisible();
  await page.keyboard.press("Escape");

  await sidebar.getByRole("button", { name: "Expand the sidebar" }).click();
  await expect(sidebar.getByRole("link", { name: /Overview/ })).toContainText("Overview");
});

test("a change that went through is confirmed with a toast", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.goto("/#/plan");
  await page.getByRole("button", { name: "Keep the rest deferred" }).click();
  const list = page.getByRole("region", { name: "Orders needing a decision" });
  await list.getByRole("button", { name: "Outlet asked to skip" }).click();
  await list.getByRole("button", { name: "Keep them deferred" }).click();
  await expect(page.getByRole("status").filter({ hasText: "Kept deferred." })).toContainText("The store gets the reason at publish");
});

test("with both depots in view the plan is one view, narrowed by the header's depot switch", async ({ page }) => {
  await serve(page, { draft: draftPlan() });
  await page.route("**/api/session", (route) => route.fulfill({ json: { ...SESSION, scope: ["depot:Kandy", "depot:Peliyagoda"] } }));
  // Peliyagoda has no plan yet; Kandy has the draft.
  await page.route(/\/api\/plans\/(draft|published)\?depot=Peliyagoda/, (route) => route.fulfill({ status: 404, json: { status: 404, title: "Not found" } }));
  await page.goto("/#/plan");
  await expect(page.getByRole("region", { name: "Plan" })).toHaveCount(1);
  await expect(page.getByText("Which depot are you planning?")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: /^No plan yet for Peliyagoda on / })).toBeVisible();
  await expect(page.getByRole("tablist", { name: "Plan steps" })).toHaveCount(1);
  // The depot switch is in the page header, not the sidebar.
  await expect(page.getByRole("complementary", { name: "Sidebar" }).getByRole("radio")).toHaveCount(0);
  await page.getByRole("radiogroup", { name: "Depots" }).getByRole("radio", { name: "Kandy" }).click();
  await expect(page.getByRole("heading", { name: /^No plan yet for Peliyagoda/ })).toHaveCount(0);
  // The same switch, in the same place, on every screen.
  await page.goto("/#/orders");
  await expect(page.getByRole("radiogroup", { name: "Depots" }).getByRole("radio", { name: "Kandy" })).toHaveAttribute("aria-checked", "true");
  await expect(page.getByRole("button", { name: /^Notifications/ })).toBeVisible();
});
