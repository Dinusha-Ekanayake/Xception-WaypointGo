import { expect, test } from "@playwright/test";
import { SESSION, issue, serve } from "./mocks.ts";

test("the inbox lists the most severe first, and a dispatcher takes an issue and resolves it with a reason", async ({ page }) => {
  const desk = await serve(page, {
    issues: [issue(1, { severity: "LOW" }), issue(2, { type: "DAMAGED_GOODS", severity: "CRITICAL" })],
  });
  await page.goto("/#/issues");

  const list = page.getByRole("region", { name: "Open issues" });
  await expect(list.getByRole("button").first()).toContainText("Damaged goods");
  await expect(list.getByRole("button").first()).toContainText("Critical");

  const detail = page.getByRole("region", { name: "Selected issue" });
  await detail.getByRole("button", { name: "Take it" }).click();
  await expect(page.getByRole("status").filter({ hasText: "assigned to you" })).toBeVisible();
  // Taken, it moves from Open to In progress and stays open here.
  await expect(page.getByRole("radio", { name: "In progress 1" })).toBeVisible();
  expect(desk.commands.at(-1)).toMatchObject({ kind: "issue:Assign", expectedVersion: 1, payload: { issueId: "issue-2", assigneeUserId: SESSION.userId } });

  await detail.getByRole("button", { name: "Resolve" }).click();
  const form = detail.getByRole("form", { name: "Resolve" });
  await form.getByLabel("Outcome").selectOption("no_fault_found");
  await expect(form.getByRole("button", { name: "Resolve" })).toBeDisabled();
  await form.getByLabel("Reason").fill("Store recounted, all there");
  await form.getByRole("button", { name: "Resolve" }).click();
  expect(desk.commands.at(-1)).toMatchObject({ kind: "issue:Resolve", expectedVersion: 2, payload: { action: "no_fault_found", note: "Store recounted, all there" } });

  // Resolved, it leaves the open list but stays here to be closed.
  await expect(list).not.toContainText("Damaged goods");
  await expect(detail.getByRole("list", { name: "Issue history" })).toContainText("Store recounted, all there");
  await detail.getByRole("button", { name: "Close" }).click();
  expect(desk.commands.at(-1)).toMatchObject({ kind: "issue:Close", expectedVersion: 3 });
});

test("a redelivery is offered only when nothing arrived, and a stale issue is refused and read again", async ({ page }) => {
  const desk = await serve(page, {
    issues: [issue(1, { type: "FAILED_DELIVERY", severity: "HIGH" }), issue(2, { type: "RECEIPT_DISPUTE", severity: "LOW" })],
  });
  await page.goto("/#/issues");
  const detail = page.getByRole("region", { name: "Selected issue" });

  await expect(detail.getByRole("button", { name: "Schedule redelivery" })).toBeVisible();
  desk.refuse = { kind: "issue:ScheduleRedelivery", status: 409, code: "VERSION_CONFLICT", detail: "issue-1 changed since version 1" };
  await detail.getByRole("button", { name: "Schedule redelivery" }).click();
  const form = detail.getByRole("form", { name: "Schedule redelivery" });
  await form.getByLabel("Reason").fill("Outlet closed, retry tomorrow");
  await form.getByRole("button", { name: "Schedule redelivery" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "Someone else changed this first" })).toBeVisible();
  expect(desk.commands.at(-1)).toMatchObject({ kind: "issue:ScheduleRedelivery", payload: { orderId: "order-1" } });

  await page.getByRole("region", { name: "Open issues" }).getByRole("button", { name: /Receipt disputed/ }).click();
  await expect(detail.getByRole("button", { name: "Resolve" })).toBeVisible();
  await expect(detail.getByRole("button", { name: "Schedule redelivery" })).toHaveCount(0);
});

test("the overview counts the day from orders and issues, and names the outlets that must go first", async ({ page }) => {
  await serve(page, {
    issues: [issue(1, { severity: "CRITICAL" }), issue(2)],
    deferrals: [{ orderId: "order-9", outletId: "OUT077", serviceDate: "2027-03-01", ruleId: "R-PLN-06", reason: "no room", skipCount: 2 }],
  });
  await page.goto("/#/overview");
  const summary = page.getByRole("region", { name: "Summary" });
  await expect(summary).toContainText("Issues reported2");
  const orders = page.getByRole("region", { name: "Orders" });
  await expect(orders).toContainText("Deferred today1");
  await expect(orders).toContainText("No room on the vehicle · 1");
  await expect(orders).toContainText("Must-deliver deferred1");
  await expect(orders).toContainText("OUT077");
});

test("going offline turns the inbox read only and says so", async ({ page, context }) => {
  await serve(page, { issues: [issue(1)] });
  await page.goto("/#/issues");
  const detail = page.getByRole("region", { name: "Selected issue" });
  await expect(detail.getByRole("button", { name: "Take it" })).toBeEnabled();
  await context.setOffline(true);
  await expect(detail.getByRole("button", { name: "Take it" })).toBeDisabled();
  await expect(detail).toContainText("issues are read only");
  await context.setOffline(false);
});
