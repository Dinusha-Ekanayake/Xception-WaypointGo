import { expect, test, type Page } from "@playwright/test";

// Issue #177: the live AI assistants console beside the admin sample console,
// blocking an app as a versioned command with a reason.

const CLIENT = "0198a000-0000-7000-8000-0000000000c1";
const PERSON = "0198a000-0000-7000-8000-0000000000d1";

async function signedInAdmin(page: Page, commands: unknown[]) {
  let blocked = false;
  let personBlocked = false;
  let personConnections = 2;
  await page.route("**/api/**", (route) => {
    const { pathname } = new URL(route.request().url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
    if (pathname === "/api/session") {
      return json({ userId: "0198a000-0000-7000-8000-000000000001", displayName: "Admin One", roles: ["admin"], operator: null, scope: [] });
    }
    if (pathname === "/api/mcp/clients") {
      return json([{ clientId: CLIENT, clientName: "Example assistant", registeredAt: "2026-10-03T01:00:00Z", lastUsedAt: "2026-10-03T04:30:00Z",
        activeConnections: blocked ? 0 : 3, blockedAt: blocked ? "2026-10-03T05:00:00Z" : null, blockReason: blocked ? "probing other depots" : null,
        rowVersion: blocked ? 2 : 1 }]);
    }
    if (pathname === "/api/audit/mcp-usage") {
      return json([
        { tool: "list_orders", clientId: CLIENT, calls: 40, ok: 38, denied: 2, rateLimited: 0, notFound: 0, errors: 0, p95Ms: 41.2, attention: false },
        { tool: "list_pending_receipts", clientId: CLIENT, calls: 12, ok: 0, denied: 12, rateLimited: 0, notFound: 0, errors: 0, p95Ms: 9, attention: true },
      ]);
    }
    if (pathname === "/api/mcp/access/roles") {
      return json([
        { principalType: "role", principalId: "admin", label: "Accounts", policies: [], liveConnections: 0 },
        { principalType: "role", principalId: "driver", label: "Executes stops", policies: [], liveConnections: 0 },
        { principalType: "role", principalId: "store_manager", label: "Places orders", policies: ["WaypointMcpNoWrites"], liveConnections: 0 },
      ]);
    }
    if (pathname === "/api/mcp/access/people") {
      return json({ items: [{ principalType: "user", principalId: PERSON, label: "Dana Driver",
        policies: personBlocked ? ["WaypointMcpBlocked"] : [], liveConnections: personConnections }], nextCursor: null });
    }
    if (pathname === "/api/policies") {
      return json({ items: [
        { policyId: "p1", name: "WaypointMcpBlocked", versionNumber: 1, rowVersion: 4 },
        { policyId: "p2", name: "WaypointMcpNoWrites", versionNumber: 1, rowVersion: 2 },
        { policyId: "p3", name: "WaypointMcpPersonalReader", versionNumber: 1, rowVersion: 1 },
      ], nextCursor: null });
    }
    if (pathname === "/api/commands") {
      const command = route.request().postDataJSON();
      commands.push(command);
      if (command.kind === "mcp:BlockClient" || command.kind === "mcp:UnblockClient") blocked = command.kind === "mcp:BlockClient";
      if (command.kind === "iam:AttachPolicy") personBlocked = true;
      if (command.kind === "mcp:RevokeUserConnections") personConnections = 0;
      return json({ commandId: command.commandId, kind: command.kind, replayed: false, result: {} });
    }
    return json({ items: [], nextCursor: null });
  });
}

test("an administrator sees assistant apps and their usage and blocks one with a reason", async ({ page }) => {
  const commands: { kind: string; expectedVersion: number; payload: { clientId: string; reason?: string } }[] = [];
  await signedInAdmin(page, commands);
  await page.goto("/#assistants");

  await expect(page.getByRole("heading", { name: "AI assistants" })).toBeVisible();
  const apps = page.getByRole("region", { name: "Assistant apps" });
  await expect(apps.getByText("Example assistant", { exact: true })).toBeVisible();
  await expect(apps.getByText("3 connected", { exact: false })).toBeVisible();
  await expect(page.getByRole("cell", { name: "Pending receipts" })).toBeVisible();
  await expect(page.getByText("Needs attention", { exact: true })).toBeVisible();
  await expect(page.getByText("list_pending_receipts")).toHaveCount(0);

  await apps.getByRole("button", { name: "Block", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Block Example assistant" });
  await expect(dialog.getByRole("button", { name: "Block app" })).toBeDisabled();
  await dialog.getByLabel(/Reason/).fill("probing other depots");
  await dialog.getByRole("button", { name: "Block app" }).click();

  await expect(page.getByRole("status")).toContainText("Example assistant is blocked");
  expect(commands).toHaveLength(1);
  expect(commands[0]).toMatchObject({ kind: "mcp:BlockClient", expectedVersion: 1, payload: { clientId: CLIENT, reason: "probing other depots" } });
  await expect(apps.getByText("Blocked", { exact: true })).toBeVisible();

  await apps.getByRole("button", { name: "Unblock" }).click();
  await expect(page.getByRole("status")).toContainText("is unblocked");
  expect(commands[1]).toMatchObject({ kind: "mcp:UnblockClient", expectedVersion: 2 });

  await page.getByRole("button", { name: "People" }).click();
  await expect(page.getByRole("heading", { name: "People & access" })).toBeVisible();
});

test("an administrator switches assistants off for one person and ends their connections with a reason", async ({ page }) => {
  const commands: { kind: string; expectedVersion: number | null; payload: Record<string, string> }[] = [];
  await signedInAdmin(page, commands);
  await page.goto("/#assistants");

  const people = page.getByRole("region", { name: "People and roles" });
  await expect(people.getByText("Store managers", { exact: true })).toBeVisible();
  await expect(people.getByText("store_manager")).toHaveCount(0);
  await expect(people.getByText("Dana Driver", { exact: true })).toBeVisible();
  await expect(people.getByText("2 connections now")).toBeVisible();
  const admins = people.locator("div.border-b", { hasText: "Administrators" });
  await expect(admins.getByRole("checkbox", { name: "Assistants" })).toBeDisabled();

  const dana = people.locator("div.border-b", { hasText: "Dana Driver" });
  const assistants = dana.getByRole("checkbox", { name: "Assistants" });
  await expect(assistants).toBeChecked();
  await assistants.click();
  await expect(people.getByRole("status")).toContainText("Assistants off for Dana Driver");
  expect(commands[0]).toMatchObject({ kind: "iam:AttachPolicy", expectedVersion: 4,
    payload: { name: "WaypointMcpBlocked", principalType: "user", principalId: PERSON } });
  await expect(assistants).not.toBeChecked();

  await dana.getByRole("button", { name: "End connections" }).click();
  const dialog = page.getByRole("dialog", { name: "End connections of Dana Driver" });
  await expect(dialog.getByRole("button", { name: "End connections" })).toBeDisabled();
  await dialog.getByLabel(/Reason/).fill("lost phone");
  await dialog.getByRole("button", { name: "End connections" }).click();
  await expect(people.getByRole("status")).toContainText("Dana Driver is disconnected");
  expect(commands[1]).toMatchObject({ kind: "mcp:RevokeUserConnections", expectedVersion: null, payload: { userId: PERSON, reason: "lost phone" } });
  await expect(dana.getByRole("button", { name: "End connections" })).toBeDisabled();
});
