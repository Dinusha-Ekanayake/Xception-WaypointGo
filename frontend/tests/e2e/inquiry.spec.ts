import { expect, test, type Page } from "@playwright/test";

// Roadmap FF-03: the Operations workspace. An inquiry is recorded with a
// write-off value, and a write-off someone else recorded is approved here.

const ME = "0198a000-0000-7000-8000-0000000000a1";
const ISSUE = "0198a000-0000-7000-8000-0000000000b1";
const WRITE_OFF = "0198a000-0000-7000-8000-0000000000c1";

async function serve(page: Page, role: "ops_inquiry" | "ops_head", recordedBy: string, needsHead: boolean) {
  const commands: Array<{ kind: string; expectedVersion: number | null; payload: Record<string, unknown> }> = [];
  const issue = {
    issueId: ISSUE, type: "DAMAGED_GOODS", severity: "HIGH", status: "OPEN", depotCode: "KDY", outletId: "OUT085",
    subjects: [{ type: "order", id: "order-1" }], description: "ORD0092336: Damaged: Fresh milk 1 L x2. 1 photo.",
    assignee: null, resolutionAction: null, resolutionNote: null, raisedBy: ME, raisedAt: "2026-10-07T03:10:00Z",
    resolvedAt: null, rowVersion: 1, attachments: [],
  };
  const writeOff = {
    writeOffId: WRITE_OFF, issueId: ISSUE, outcome: "Two cartons crushed", valueLkr: "30000.00", techLine: false, needsHead,
    attachmentIds: [], recordedBy, recordedAt: "2026-10-07T04:00:00Z", status: "recorded", decidedBy: null, decidedAt: null,
    decisionNote: null, rowVersion: 1,
  };
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const { pathname } = new URL(request.url());
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/api/session") return json({ userId: ME, displayName: "Kumari Silva", roles: [role], operator: null, scope: ["depot:KDY"] });
    if (pathname === "/api/issues") return json({ items: [issue], nextCursor: null });
    if (pathname === "/api/issues/settled") return json([]);
    if (pathname === `/api/issues/${ISSUE}`) return json(issue);
    if (pathname === `/api/issues/${ISSUE}/history`) return json([{ from: null, to: "OPEN", action: "raised", reason: "raised", actorId: null, at: "2026-10-07T03:10:00Z" }]);
    if (pathname === `/api/issues/${ISSUE}/write-offs`) return json([writeOff]);
    if (pathname === "/api/commands" && request.method() === "POST") {
      const body = request.postDataJSON();
      commands.push(body);
      return json({ commandId: body.commandId, kind: body.kind, replayed: false, result: {} });
    }
    return json({ items: [], nextCursor: null });
  });
  return commands;
}

test("Operations records an inquiry with a write-off value", async ({ page }) => {
  const commands = await serve(page, "ops_inquiry", ME, false);
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Inquiries" })).toBeVisible();
  await page.getByLabel("What was found").fill("Pallet crushed at the dock, photos confirm");
  await page.getByLabel("Write-off value in rupees (optional)").fill("4,500");
  await page.getByRole("button", { name: "Record inquiry" }).click();
  await expect.poll(() => commands.length).toBe(1);
  expect(commands[0]).toMatchObject({ kind: "issue:RecordInquiry", payload: { issueId: ISSUE, outcome: "Pallet crushed at the dock, photos confirm", writeOffLkr: "4500" } });
  // My own write-off is never mine to approve (P-DG-1).
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
});

test("the Head of Distribution approves a write-off above the limit that someone else recorded", async ({ page }) => {
  const commands = await serve(page, "ops_head", "0198a000-0000-7000-8000-0000000000d1", true);
  await page.goto("/");
  await expect(page.getByText("Above the depot limit · Head of Distribution")).toBeVisible();
  await page.getByLabel("Why approve or reject").fill("Photos confirm the damage");
  await page.getByRole("button", { name: "Approve" }).click();
  await expect.poll(() => commands.length).toBe(1);
  expect(commands[0]).toMatchObject({ kind: "issue:ApproveWriteOff", expectedVersion: 1, payload: { writeOffId: WRITE_OFF, approve: true } });
});

test("an Operations manager cannot approve above the depot limit", async ({ page }) => {
  await serve(page, "ops_inquiry", "0198a000-0000-7000-8000-0000000000d1", true);
  await page.goto("/");
  await expect(page.getByText("Above the depot limit · Head of Distribution")).toBeVisible();
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
});
