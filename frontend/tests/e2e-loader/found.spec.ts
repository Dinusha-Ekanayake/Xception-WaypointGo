import { expect, test } from "@playwright/test";
import { board, manifest, SESSION } from "./mocks.ts";

// R-ISS-17: a loader who finds a reported item before release takes the flag
// back with a reason; the issue is resolved "found at the depot" and the item ticked.

async function flagged(page: import("@playwright/test").Page, issues: unknown[]) {
  const open = manifest("trip-test", false, 4);
  open.lines[0]!.items[0] = { ...open.lines[0]!.items[0]!, status: "MISSING", attempt: 1 };
  const commands: Array<{ kind: string }> = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (url.pathname === "/api/session") return json(SESSION);
    if (url.pathname === "/api/loading/trips") return json(board(open));
    if (url.pathname === "/api/reference/outlets") return json([]);
    if (url.pathname === "/api/loading/trips/trip-test/manifest") return json(open);
    if (url.pathname === "/api/issues/by-subject") return json(issues);
    if (url.pathname === "/api/commands" && request.method() === "POST") {
      commands.push(request.postDataJSON() as { kind: string });
      return json({ commandId: "c", kind: "x", replayed: false, result: { rowVersion: 5 } });
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });
  return commands;
}

test("a flag still on its way to the dispatcher is not taken back yet", async ({ page }) => {
  const commands = await flagged(page, []);
  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: /Edit report for/ }).first().click();
  const found = page.getByRole("dialog", { name: "Report an issue" }).getByRole("group", { name: "Found it" });
  await found.getByLabel("Where it was").fill("behind the next pallet");
  await found.getByRole("button", { name: "Found, remove flag" }).click();
  await expect(page.getByText("The report has not reached the dispatcher yet. Try again in a minute.")).toBeVisible();
  expect(commands).toEqual([]);
});

test("a found item takes its flag back and is ticked loaded", async ({ page }) => {
  const open = manifest("trip-test", false, 4);
  open.lines[0]!.items[0] = { ...open.lines[0]!.items[0]!, status: "MISSING", attempt: 1 };
  const commands: Array<{ kind: string; expectedVersion: number | null; payload: Record<string, unknown> }> = [];
  const issue = {
    issueId: "issue-9", type: "LOADING_SHORTFALL", severity: "MEDIUM", status: "OPEN", depotCode: "KDY", outletId: "OUT001",
    subjects: [{ type: "trip", id: "trip-test" }, { type: "order", id: "order-test" }], description: "MISSING at loading: 2 units.",
    assignee: null, resolutionAction: null, resolutionNote: null, raisedBy: "u", raisedAt: "2026-10-01T08:00:00Z", resolvedAt: null, rowVersion: 3,
  };

  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (url.pathname === "/api/session") return json(SESSION);
    if (url.pathname === "/api/loading/trips") return json(board(open));
    if (url.pathname === "/api/reference/outlets") return json([]);
    if (url.pathname === "/api/loading/trips/trip-test/manifest") return json(open);
    if (url.pathname === "/api/issues/by-subject") return json(url.searchParams.get("id") === "trip-test" ? [issue] : []);
    if (url.pathname === "/api/commands" && request.method() === "POST") {
      const body = request.postDataJSON() as (typeof commands)[number];
      commands.push(body);
      return json({ commandId: "c", kind: body.kind, replayed: false, result: { rowVersion: body.kind.startsWith("loading:") ? 5 : 4 } });
    }
    return route.fulfill({ status: 404, body: "not mocked" });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Continue" }).click();
  await page.getByRole("button", { name: /Edit report for/ }).first().click();
  const found = page.getByRole("dialog", { name: "Report an issue" }).getByRole("group", { name: "Found it" });
  await expect(found.getByRole("button", { name: "Found, remove flag" })).toBeDisabled();
  await found.getByLabel("Where it was").fill("behind the next pallet");
  await found.getByRole("button", { name: "Found, remove flag" }).click();

  await expect.poll(() => commands.map((c) => c.kind)).toEqual(["issue:RecordReplacement", "loading:Check"]);
  expect(commands[0]).toMatchObject({
    expectedVersion: 3,
    payload: { issueId: "issue-9", tripId: "trip-test", orderId: "order-test", note: "found at the depot: behind the next pallet" },
  });
  expect(commands[1]).toMatchObject({ payload: { orderId: "order-test", lineNo: 1, status: "LOADED" } });
  await expect(page.getByText("Flag removed")).toBeVisible();
  await expect(page.getByText("Saved offline")).toHaveCount(0);
});
